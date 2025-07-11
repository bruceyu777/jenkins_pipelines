// runTests.groovy
// (Modified to add PROVISION_VMPC/VMPC_NAMES and PROVISION_DOCKER)

def getArchiveGroupName(String group) {
    def parts = group.tokenize('.')
    if (parts.size() >= 2) {
        return "${parts[0]}.${parts[1]}"
    } else {
        return group
    }
}

def getTestGroups(params) {
    def testGroups = []
    if (params.TEST_GROUPS) {
        if (params.TEST_GROUPS instanceof String) {
            def tg = params.TEST_GROUPS.trim()
            if (tg.startsWith("\"") && tg.endsWith("\"")) {
                tg = tg.substring(1, tg.length()-1).trim()
            }
            if (tg.startsWith("[")) {
                try {
                    def parsed = readJSON text: tg
                    if (parsed instanceof List) {
                        testGroups = parsed
                    } else {
                        testGroups = tg.split(",").collect { it.trim() }
                    }
                } catch (e) {
                    echo "Error parsing TEST_GROUPS as JSON: ${e}. Falling back to splitting by comma."
                    testGroups = tg.split(",").collect { it.trim() }
                }
            } else {
                testGroups = tg.split(",").collect { it.trim() }
            }
        } else if (params.TEST_GROUPS instanceof List) {
            testGroups = params.TEST_GROUPS
        } else {
            testGroups = [params.TEST_GROUPS.toString()]
        }
    }
    if (!testGroups || testGroups.isEmpty()) {
        testGroups = [params.TEST_GROUP_CHOICE]
    }
    return testGroups
}

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

def computedTestGroups = []  // Global variable to share across stages

def call() {
    // Existing function that defines your "standard" set of upstream parameters:
    fortistackMasterParameters(exclude: ['FGT_TYPE','SKIP_PROVISION'])

    // Expand any JSON‐style parameters so they become global variables:
    expandParamsJson(params.PARAMS_JSON)

    pipeline {
        // Use the NODE_NAME coming from upstream (or from user input if run independently)
        agent { label "${params.NODE_NAME}" }

        options {
            buildDiscarder(logRotator(numToKeepStr: '100'))
        }

        environment {
            TZ = 'America/Vancouver'
        }


        stages {
            
            stage('Wait until previous build finish') {
                steps {
                    script {
                    echo "Waiting for previous builds to finish..."
                    sh """
                            cd /home/fosqa/resources/tools
                            . /home/fosqa/resources/tools/venv/bin/activate
                            python3 -u wait_until_aio_pipeline_not_running.py
                    """
                    }
                }
            }
            
            stage('Initialize Test Groups') {
                steps {
                    script {
                        computedTestGroups = getTestGroups(params)
                        echo "Computed test groups: ${computedTestGroups}"
                    }
                }
            }

            stage('Set Build Display Name') {
                steps {
                    script {
                        currentBuild.displayName = "#${currentBuild.number} " +
                            "${params.NODE_NAME}-${params.BUILD_NUMBER}-" +
                            "${FEATURE_NAME}-${computedTestGroups.join(',')}"
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

            /*
             * ───── New Stage: Provision VMPCs ─────
             * Only runs if PROVISION_VMPC == true.
             */
            stage('Provision VMPCs') {
                when {
                    expression { return params.PROVISION_VMPC == true }
                }
                steps {
                    script {
                        if (!params.VMPC_NAMES?.trim()) {
                            error "PROVISION_VMPC is true but VMPC_NAMES is empty. Please specify one or more VMPC names."
                        }

                        // Pass the VMPC_NAMES string directly to the python script
                        echo "Provisioning VMPCs: ${params.VMPC_NAMES}"
                        sh """
                            cd /home/fosqa/resources/tools
                            sudo make provision_kvm_pc vmpc="${params.VMPC_NAMES}"
                        """
                    }
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

                            

                            // Step 2: Prepare SVN code directory and update
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
                                def svnStatus = sh(
                                    script: """
                                        cd ${baseTestDir} && \
                                        sudo svn checkout \
                                          https://qa-svn.corp.fortinet.com/svn/qa/FOS/${params.TEST_CASE_FOLDER}/${SVN_BRANCH}/${params.FEATURE_NAME} \
                                          --username \$SVN_USER --password \$SVN_PASS --non-interactive
                                    """,
                                    returnStatus: true
                                )
                                if (svnStatus != 0) {
                                    echo "SVN checkout failed with exit status ${svnStatus}. Continuing pipeline..."
                                }
                            } else {
                                sh "cd ${folderPath} && sudo svn update --username \$SVN_USER --password \$SVN_PASS --non-interactive"
                            }
                            sh "sudo chmod -R 777 ${baseTestDir}"
                           
                        }
                    }
                }
            }

            stage('Test Running') {
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

                                # mark it safe
                                sudo -u fosqa git config --global --add safe.directory \$REPO_DIR

                                cd \$REPO_DIR

                                # run all three as fosqa
                                sudo -u fosqa sh -c '
                                    git stash push -u -m "autolib auto-stash before pull" || true
                                    git checkout ${AUTOLIB_BRANCH}
                                    git pull --rebase --autostash
                                '
                                """
                            
                            /*
                             * ───── Wrap Docker provisioning in an if (params.PROVISION_DOCKER) ─────
                             */
                            if (params.PROVISION_DOCKER) {
                                echo "=== Docker provisioning is ENABLED ==="
                                // Step 3: Update Docker file, if needed
                                def forceArg = params.FORCE_UPDATE_DOCKER_FILE ? "--force" : ""
                                sh """
                                    cd /home/fosqa/resources/tools
                                    sudo /home/fosqa/resources/tools/venv/bin/python \
                                      get_dockerfile_from_cdn.py --feature ${FEATURE_NAME} ${forceArg}
                                """

                                // Step 4: Create Docker file soft link
                                sh """
                                    cd /home/fosqa/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}
                                    sudo rm -f docker_filesys
                                    sudo ln -s /home/fosqa/docker_filesys/${params.FEATURE_NAME} docker_filesys
                                """
                                sh """
                                    docker login harbor-robot.corp.fortinet.com -u \$SVN_USER -p \$SVN_PASS
                                    docker ps -aq | xargs -r docker rm -f
                                    cd /home/fosqa/resources/tools
                                    make setup_docker_network_and_cleanup_telnet_ports
                                    docker compose \
                                      -f /home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/docker/${params.DOCKER_COMPOSE_FILE_CHOICE} \
                                      up --build -d
                                """
                            } else {
                                echo "=== Skipping Docker provisioning (PROVISION_DOCKER == false) ==="
                            }

                            // Run tests for each computed test group regardless of Docker toggle
                            for (group in computedTestGroups) {
                                echo "Running tests for test group: ${group}"
                                sh """
                                    cd /home/fosqa/${LOCAL_LIB_DIR}
                                    sudo chmod -R 777 .
                                    . /home/fosqa/${LOCAL_LIB_DIR}/venv/bin/activate
                                    python3 autotest.py \
                                      -e testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_CONFIG_CHOICE} \
                                      -g testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${group} \
                                      -d -s ${params.ORIOLE_SUBMIT_FLAG}
                                """
                                // inject inside a try/catch so failures are non-fatal
                                try {
                                sh """
                                    cd /home/fosqa/resources/tools
                                    sudo /home/fosqa/resources/tools/venv/bin/python3 \
                                    inject_autolib_result.py \
                                        -r ${params.RELEASE} \
                                        -g ${group}
                                """
                                echo "✅ inject_autolib_result.py succeeded"
                                } catch (err) {
                                echo "⚠️ inject_autolib_result.py failed, but pipeline will continue"
                                }

                                // update node info by running get_node_info.py
                                try {
                                sh """
                                    cd /home/fosqa/resources/tools
                                    sudo ./venv/bin/python3 get_node_info.py --feature ${group}
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
        }

        post {
            always {
                script {
                    def outputsDir = "/home/fosqa/${LOCAL_LIB_DIR}/outputs"
                    sh "rm -rf ${WORKSPACE}/test_results"
                    sh "rm -f ${WORKSPACE}/summary_*.html"

                    def archivedFolders = []
                    for (group in computedTestGroups) {
                        def archiveGroup = getArchiveGroupName(group)
                        def folder = sh(
                            returnStdout: true,
                            script: """
                                find ${outputsDir} \
                                    -mindepth 2 -maxdepth 2 -type d \
                                    -name "*--group--${archiveGroup}" \
                                    -printf '%T@ %p\\n' \
                                  | sort -nr \
                                  | head -1 \
                                  | cut -d' ' -f2-
                            """
                        ).trim()

                        if (!folder) {
                            echo "Warning: No test results folder found for test group '${archiveGroup}' in ${outputsDir}."
                        } else {
                            echo "Found folder for group '${archiveGroup}': ${folder}"
                            archivedFolders << folder
                            sh "mkdir -p ${WORKSPACE}/test_results/${archiveGroup}"
                            sh "cp -r ${folder} ${WORKSPACE}/test_results/${archiveGroup}/"
                            sh "cp ${folder}/summary/summary.html ${WORKSPACE}/summary_${archiveGroup}.html"
                        }
                    }

                    if (archivedFolders.isEmpty()) {
                        echo "No test results were found for any test group."
                    } else {
                        archiveArtifacts artifacts: "test_results/**, summary_*.html", fingerprint: false
                        publishHTML(target: [
                            reportDir   : ".",
                            reportFiles : "summary_${getArchiveGroupName(computedTestGroups[0])}.html",
                            reportName  : "Test Results Summary for ${getArchiveGroupName(computedTestGroups[0])}"
                        ])
                    }
                }

                echo "Pipeline completed. Check console output for details."
            }

            success {
                script {
                    def base = "${env.BUILD_URL}artifact/"
                    def summaryLinks = computedTestGroups.collect { group ->
                        def name = getArchiveGroupName(group)
                        "<a href=\"${base}summary_${name}.html\">Summary: ${name}</a>"
                    }.join("<br/>\n")

                    def nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.txt').trim()

                    sendFosqaEmail(
                        to     : params.SEND_TO,
                        subject: "${env.BUILD_DISPLAY_NAME} Succeeded",
                        body   : """
                        <p>🎉 Good news! Job <b>${env.BUILD_DISPLAY_NAME}</b> completed at ${new Date()}.</p>
                        <p>📄 Test result summaries:</p>
                        ${summaryLinks}
                        <p>🔗 Console output: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                        <h3>Node Info</h3>
                        <pre style="font-family:monospace; white-space:pre-wrap;">${nodeInfo}</pre>
                        <p><em>PS: Use the above node info for debugging.</em></p>
                        """
                    )

                }
            }

            failure {
                script {
                    def base = "${env.BUILD_URL}artifact/"
                    def summaryLinks = computedTestGroups.collect { group ->
                        def name = getArchiveGroupName(group)
                        "<a href=\"${base}summary_${name}.html\">Summary: ${name}</a>"
                    }.join("<br/>\n")
                    def nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.txt').trim()
                    sendFosqaEmail(
                        to     : params.SEND_TO,
                        subject: "${env.BUILD_DISPLAY_NAME} FAILED",
                        body   : """
                        <p>❌ Job <b>${env.BUILD_DISPLAY_NAME}</b> failed.</p>
                        <p>📄 You can still peek at whatever got archived:</p>
                        ${summaryLinks}
                        <p>🔗 Console output: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                        <h3>Node Info</h3>
                        <pre style="font-family:monospace; white-space:pre-wrap;">${nodeInfo}</pre>
                        <p><em>PS: Use the above node info for debugging.</em></p>
                        """
                    )
                }
            }
        }
    }
}
