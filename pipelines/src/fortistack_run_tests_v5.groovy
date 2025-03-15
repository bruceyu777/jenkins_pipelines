// Helper function to get the archive group name (first two portions).
def getArchiveGroupName(String group) {
    def parts = group.tokenize('.')
    if (parts.size() >= 2) {
        return "${parts[0]}.${parts[1]}"
    } else {
        return group
    }
}

pipeline {
    parameters {
        string(
            name: 'BUILD_NUMBER',
            defaultValue: '3473',
            trim: true,
            description: 'FGT build number'
        )
        string(
            name: 'NODE_NAME',
            defaultValue: 'node1',
            trim: true,
            description: 'Jenkins node label'
        )
        string(
            name: 'LOCAL_LIB_DIR',
            defaultValue: 'autolibv3',
            trim: true,
            description: 'Local library directory'
        )
        string(
            name: 'SVN_BRANCH',
            defaultValue: 'trunk',
            trim: true,
            description: 'SVN test branch'
        )
        string(
            name: 'FEATURE_NAME',
            defaultValue: 'avfortisandbox',
            trim: true,
            description: 'SVN test feature'
        )
        string(
            name: 'TEST_CASE_FOLDER',
            defaultValue: 'testcase',
            trim: true,
            description: 'SVN test case folder name, like testcase or testcase_v1'
        )
        string(
            name: 'TEST_CONFIG_CHOICE',
            defaultValue: 'env.newman.FGT_KVM.avfortisandbox.conf',
            trim: true,
            description: 'Env file name'
        )
        string(
            name: 'TEST_GROUP_CHOICE',
            defaultValue: 'grp.avfortisandbox_fortistack.full',
            trim: true,
            description: 'Test group file name'
        )
        //DOCKER_COMPOSE_FILE_CHOICE
        string(
            name: 'DOCKER_COMPOSE_FILE_CHOICE',
            defaultValue: 'docker.avfortisandbox_avfortisandbox.yml',
            trim: true,
            description: 'docker compose file name'
        )
        booleanParam(
            name: 'FORCE_UPDATE_DOCKER_FILE',
            defaultValue: true,
            description: 'If true, update docker file with --force option'
        )
        string(
            name: 'build_name',
            defaultValue: 'fortistack-',
            trim: true,
            description: 'Build name'
        )
        string(
            name: 'send_to',
            defaultValue: 'yzhengfeng@fortinet.com',
            trim: true,
            description: 'Email address'
        )
    }
    
    agent { label "${params.NODE_NAME}" }
    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
    }
    
    stages {
        stage('Set Build Display Name') {
            steps {
                script {
                    currentBuild.displayName = "#${currentBuild.number} ${params.NODE_NAME}-${params.BUILD_NUMBER}-${params.FEATURE_NAME}-${params.TEST_GROUP_CHOICE}"
                }
            }
        }

        stage('Check Docker') {
            steps {
                echo "Checking Docker environment..."
                sh 'docker ps'
                sh 'docker-compose --version'
                sh 'virsh -c qemu:///system list --all'
            }
        }

        stage('Test Preparation') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'LDAP', usernameVariable: 'SVN_USER', passwordVariable: 'SVN_PASS')]) {
                        // Local Git update
                        echo "=== Step 1: Local Git update ==="
                        def innerGitCmd = "sudo -u fosqa bash -c 'cd /home/fosqa/resources/tools && " +
                                          "if [ -n \"\$(git status --porcelain)\" ]; then git stash push -m \"temporary stash\"; fi; " +
                                          "git pull; " +
                                          "if git stash list | grep -q \"temporary stash\"; then git stash pop; fi'"
                        echo "Executing local git pull command: ${innerGitCmd}"
                        try {
                            sh innerGitCmd
                        } catch (Exception e) {
                            echo "Local git pull failed: ${e.getMessage()}. Continuing without updating."
                        }

                        // Update Docker file
                        def forceArg = params.FORCE_UPDATE_DOCKER_FILE ? "--force" : ""
                        sh """
                            cd /home/fosqa/resources/tools
                            sudo /home/fosqa/resources/tools/venv/bin/python get_dockerfile_from_cdn.py --feature ${params.FEATURE_NAME} ${forceArg}
                        """

                        // Prepare SVN code directory and update SVN repository
                        def baseTestDir = "/home/fosqa/${params.LOCAL_LIB_DIR}/testcase/${params.SVN_BRANCH}"
                        sh "mkdir -p ${baseTestDir}"
                        def folderPath = "${baseTestDir}/${params.FEATURE_NAME}"
                        echo "Checking folder: ${folderPath}"
                        def folderExists = sh(script: "if [ -d '${folderPath}' ]; then echo exists; else echo notexists; fi", returnStdout: true).trim()
                        echo "Folder check result: ${folderExists}"

                        if (folderExists == "notexists") {
                            // SVN checkout if folder doesn't exist
                            def svnStatus = sh(script: "cd ${baseTestDir} && sudo svn checkout https://qa-svn.corp.fortinet.com/svn/qa/FOS/${params.TEST_CASE_FOLDER}/${params.SVN_BRANCH}/${params.FEATURE_NAME} --username \$SVN_USER --password \$SVN_PASS --non-interactive", returnStatus: true)
                            if (svnStatus != 0) {
                                echo "SVN checkout failed with exit status ${svnStatus}. Continuing pipeline..."
                            }
                        } else {
                            // SVN update if folder exists
                            // --accept theirs-full 
                            sh "cd ${folderPath} && sudo svn update --username \$SVN_USER --password \$SVN_PASS --non-interactive"
                        }

                        // Create Docker file soft link
                        sh """
                            cd /home/fosqa/testcase/${params.SVN_BRANCH}/${params.FEATURE_NAME}
                            sudo rm -f docker_filesys
                            sudo ln -s /home/fosqa/docker_filesys/${params.FEATURE_NAME} docker_filesys
                        """

                        // Login to Harbor
                        sh "docker login harbor-robot.corp.fortinet.com -u \$SVN_USER -p \$SVN_PASS"

                        // Remove all existing Docker containers
                        sh "docker ps -aq | xargs -r docker rm -f"
                    }
                }
            }
        }

        stage('Test Running') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'LDAP', usernameVariable: 'SVN_USER', passwordVariable: 'SVN_PASS')]) {
                        // Run test cases using Docker compose and execute tests
                        sh """
                            cd /home/fosqa/resources/tools && python3 set_docker_network.py
                            sudo python3 set_route_for_docker.py 
                            docker compose -f /home/fosqa/testcase/${params.SVN_BRANCH}/${params.FEATURE_NAME}/docker/${params.DOCKER_COMPOSE_FILE_CHOICE} up --build -d

                            cd /home/fosqa/${params.LOCAL_LIB_DIR}
                            sudo chmod -R 777 .
                            . /home/fosqa/${params.LOCAL_LIB_DIR}/venv/bin/activate
                            python3 autotest.py -e testcase/${params.SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_CONFIG_CHOICE} -g testcase/${params.SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_GROUP_CHOICE} -d
                        """

                        // Enable HTTP service to check test results
                        sh "cd /home/fosqa/resources/tools && sudo python3 simple_http_server_as_service.py"
                    }
                }
            }
        }
        stage('Archive Test Results') {
            steps {
                script {
                    def outputsDir = "/home/fosqa/${params.LOCAL_LIB_DIR}/outputs"
                    // Search two levels deep for directories matching the test group.
                    def archiveGroup = getArchiveGroupName(params.TEST_GROUP_CHOICE)
                    def latestSubFolder = sh(
                        returnStdout: true,
                        script: """
                            find ${outputsDir} -mindepth 2 -maxdepth 2 -type d -name "*--group--${archiveGroup}" -printf '%T@ %p\\n' | sort -nr | head -1 | cut -d' ' -f2-
                        """
                    ).trim()
                    
                    if (!latestSubFolder) {
                        echo "Warning: No test results folder found for test group '${archiveGroup}' in ${outputsDir}. Skipping archiving."
                    } else {
                        echo "Latest results folder for test group '${archiveGroup}': ${latestSubFolder}"
                        sh "cp -r ${latestSubFolder} ${WORKSPACE}/test_results"
                        sh "cp ${latestSubFolder}/summary/summary.html ${WORKSPACE}/summary.html"
                        archiveArtifacts artifacts: "test_results/**, summary.html", fingerprint: false
                        publishHTML(target: [
                            reportDir: ".",
                            reportFiles: 'summary.html',
                            reportName: "Test Results Summary"
                        ])
                    }
                }
            }
        }


    }
    
    post {
        always {
            echo "Pipeline completed. Check console output for details."
            
        }
    }
}
