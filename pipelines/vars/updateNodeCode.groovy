

def expandParamsJson(String jsonStr) {
    try {
        def jsonMap = new groovy.json.JsonSlurper().parseText(jsonStr) as Map
        jsonMap.each { key, value ->
            binding.setVariable(key, value)
            echo "Set variable: ${key} = ${value}"
        }
        return jsonMap
    } catch (Exception e) {
        error("Failed to parse PARAMS_JSON: ${e}")
    }
}


def call() {
    def sendTo = (params.SEND_TO?.trim() ? params.SEND_TO : "yzhengfeng@fortinet.com")

    fortistackMasterParameters(exclude: ['FGT_TYPE','SKIP_PROVISION','PROVISION_DOCKER','ORIOLE_SUBMIT_FLAG','ADDITIONAL_EMAIL','SKIP_TEST','TEST_CONFIG_CHOICE','DOCKER_COMPOSE_FILE_CHOICE','PROVISION_VMPC','VMPC_NAMES','TERMINATE_PREVIOUS','TEST_GROUP_FILTER'])

    expandParamsJson(params.PARAMS_JSON)

    pipeline {
        agent { label "${params.NODE_NAME}" }

        options {
            buildDiscarder(logRotator(numToKeepStr: '100'))
        }

        environment {
            TZ = 'America/Vancouver'
        }

        stages {

            stage('Set Build Display Name') {
                steps {
                    script {
                        currentBuild.displayName = "#${currentBuild.number} " +
                            "${params.NODE_NAME}-${params.RELEASE}-${params.BUILD_NUMBER}-" +
                            "${FEATURE_NAME}}"
                    }
                }
            }

            stage('Debug Parameters') {
                steps {
                    script {
                        echo "=== Debug: Printing All Parameters ==="
                        params.each { key, value ->
                            echo "${key} = ${value}"
                        }
                    }
                }
            }

            stage('Check Docker and KVM domains') {
                steps {
                    echo "Checking Docker environment..."
                    sh 'docker ps'
                    sh 'docker-compose --version'
                    echo "Checking KVM domains..."
                    sh 'virsh -c qemu:///system list --all'
                }
            }

            stage('Test Preparation') {
                steps {
                    script {
                        withCredentials([
                            usernamePassword(
                                credentialsId: 'LDAP',
                                usernameVariable: 'SVN_USER',
                                passwordVariable: 'SVN_PASS'
                            )
                        ]) {
                            echo "=== Step 1: Local Git update ==="
                            def innerGitCmd = """
                                sudo -u fosqa bash -c '
                                  cd /home/fosqa/resources/tools && \
                                  if [ -n "\$(git status --porcelain)" ]; then \
                                    git stash push -m "temporary stash"; \
                                  fi; \
                                  git pull; \
                                  if git stash list | grep -q "temporary stash"; then \
                                    git stash pop; \
                                  fi
                                '
                            """
                            try {
                                sh innerGitCmd
                            } catch (Exception e) {
                                echo "Local git pull failed: ${e.getMessage()}. Continuing without updating."
                            }

                            def baseTestDir = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}"
                            sh "sudo mkdir -p ${baseTestDir} && sudo chmod -R 777 ${baseTestDir}"
                            def folderPath = "${baseTestDir}/${params.FEATURE_NAME}"
                            echo "Checking folder: ${folderPath}"
                            def folderExists = sh(
                                script: "if [ -d '${folderPath}' ]; then echo exists; else echo notexists; fi",
                                returnStdout: true
                            ).trim()
                            echo "Folder check result: ${folderExists}"

                            if (folderExists == "notexists") {
                                runWithRetry(4, [5, 15, 45], """
                                    cd ${baseTestDir} && \
                                    sudo svn checkout \
                                    https://qa-svn.corp.fortinet.com/svn/qa/FOS/${params.TEST_CASE_FOLDER}/${SVN_BRANCH}/${params.FEATURE_NAME} \
                                    --username "$SVN_USER" --password "$SVN_PASS" --non-interactive
                                """)
                            } else {
                                runWithRetry(4, [5, 15, 45], """
                                    cd ${folderPath} && \
                                    sudo svn update --username "$SVN_USER" --password "$SVN_PASS" --non-interactive
                                """)
                            }
                            sh "sudo chmod -R 777 ${baseTestDir}"
                        }
                    }
                }
            }

            stage('Test Running') {
                when {
                    expression { return !(params.SKIP_TEST?.toBoolean() ?: false) }
                }
                steps {
                    script {
                        withCredentials([
                            usernamePassword(
                                credentialsId: 'LDAP',
                                usernameVariable: 'SVN_USER',
                                passwordVariable: 'SVN_PASS'
                            )
                        ]) {
                            echo "🔄 Syncing autolib repo (${LOCAL_LIB_DIR}) to branch ${AUTOLIB_BRANCH}, stashing any local edits…"
                            sh """
                                REPO_DIR=/home/fosqa/${LOCAL_LIB_DIR}
                                sudo -u fosqa git config --global --add safe.directory \$REPO_DIR
                                cd \$REPO_DIR
                                sudo -u fosqa sh -c '
                                    git stash push -u -m "autolib auto-stash before pull" || true
                                    git checkout ${AUTOLIB_BRANCH}
                                    git pull --rebase --autostash
                                '
                            """
                            echo "Skip Running tests... getting node info"
                            try {
                                sh """
                                    cd /home/fosqa/resources/tools
                                    sudo ./venv/bin/python3 get_node_info.py
                                """
                                echo "✅ get_node_info.py succeeded"
                            } catch (err) {
                                echo "⚠️ get_node_info.py failed, but pipeline will continue"
                            }

                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    echo "Pipeline completed. Check console output for details."
                }
            }
        }
    }
}
