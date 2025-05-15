// used by http://10.96.227.206:8080/job/fortistack_runtest_guitest/

// Helper function to parse PARAMS_JSON and expand its keys as global variables.


def expandParamsJson(String jsonStr) {
    try {
        def jsonMap = new groovy.json.JsonSlurper().parseText(jsonStr) as Map
        jsonMap.each { key, value ->
            // Set each key-value pair as a global variable.
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
  fortistackMasterParametersGuiTest()
  // Expand the JSON parameters so that keys (e.g. build_name, send_to, FGT_TYPE)
  // are available as global variables.
  expandParamsJson(params.PARAMS_JSON)

  pipeline {
    // Use NODE_NAME from pipeline parameters.
    agent { label "${params.NODE_NAME}" }

    parameters {
      string(
        name: 'STACK_NAME',
        defaultValue: 'fgtA',
        description: 'Which Docker stack to bring up (e.g. fgtA)'
      )
      string(
        name: 'KEY_WORD',
        defaultValue: 'test_',
        description: 'Test-name prefix to filter which tests to run'
      )
    }


    options {
      buildDiscarder(logRotator(numToKeepStr: '100'))
    }
    environment {
        TZ = 'America/Vancouver'
        TEST_FOLDER = "/home/fosqa/git/guitest/${params.FEATURE_NAME}"
    }
    
    stages {
      
      stage('Set Build Display Name') {
        steps {
          script {
            currentBuild.displayName = "#${currentBuild.number} ${params.NODE_NAME}-${params.BUILD_NUMBER}-${FEATURE_NAME}"
          }
        }
      }
      
      stage('Check Docker and KVM domains') {
        steps {
          echo "Checking Docker environment..."
          sh 'docker ps'
          sh 'docker-compose --version'
          sh 'virsh -c qemu:///system list --all'
        }
      }
      
      stage('Test Running') {
        steps {
          script {
            withCredentials([usernamePassword(credentialsId: 'LDAP', usernameVariable: 'SVN_USER', passwordVariable: 'SVN_PASS')]) {
              // Setup docker network and route, make sure telnet available and bring up docker containers
              sh """
                  docker login harbor-robot.corp.fortinet.com -u \$SVN_USER -p \$SVN_PASS
              """
              // sh """
              //     docker login harbor-robot.corp.fortinet.com -u \$SVN_USER -p \$SVN_PASS
              //     docker ps -aq | xargs -r docker rm -f
              //     cd /home/fosqa/resources/tools/
              //     make setup_docker_network_and_cleanup_telnet_ports
              //     docker compose -f /home/fosqa/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/docker/${params.DOCKER_COMPOSE_FILE_CHOICE} up --build -d
              // """
              // Run tests for each computed test group.
              
              script {
                        // Generate session id based on current timestamp in the format "yyyyMMddHHmmss_SSS"
                        println("Generate Session ID ...")
                        def currentDateTime = new Date()
                        def sessionIdFormat = new java.text.SimpleDateFormat("yyyyMMddHHmmss_'${currentDateTime.getTime() % 1000}'")
                        env.SESSION_ID = sessionIdFormat.format(currentDateTime)
                        println("Generated Session ID: ${env.SESSION_ID}")
                    }
              
              sh """
                  cd /home/fosqa/git/guitest/${params.FEATURE_NAME}
                  sudo chmod -R 777 .
                  . /home/fosqa/git/guitest/venv_fosqa/bin/activate
                  make docker_test stack=${params.STACK_NAME} name=${params.KEY_WORD} session_id=${env.SESSION_ID} submit=true browser_type=firefox
              """
            }
          }
        }
      }
    }
    
    post {
      always {
            script {
                println "Run and Parse Python Script ..."
                try {
                    def output = sh(script: "cd ${env.TEST_FOLDER} && . ../venv*/bin/activate && python3 docker_test_report_manager.py --method read_docker_combined_report --file_path ${env.TEST_FOLDER}/docker_combined_report_list.txt --session_id=${env.SESSION_ID}", returnStdout: true).trim()
                    println("Raw Python script output: $output")

                    def jsonOutput = readJSON text: output
                    println("After JSON Parsing: ${jsonOutput}")
                    htmlFilePath = jsonOutput.html_file_path
                    xmlFilePath = jsonOutput.xml_file_path
                    docker_report_folders_list = jsonOutput.docker_report_folders ?: []
                } catch (Throwable t) {
                    echo "Caught Throwable: ${t}"
                    currentBuild.result = 'UNSTABLE'
                    echo "Failed to parse Python script output"
                }

                println "Add Artifacts ..."
                docker_report_folders_list.each { folderPath ->
                    try {
                        withCredentials([string(credentialsId: 'sudoPassword', variable: 'SUDO_PASS')]) {
                            sh """
                            echo $SUDO_PASS | sudo -S chmod -R 777 ${folderPath}/artifacts/screenshots || true
                            echo $SUDO_PASS | sudo -S mkdir -p ${WORKSPACE}/${BUILD_NUMBER}/report
                            echo $SUDO_PASS | sudo -S mkdir -p ${WORKSPACE}/${BUILD_NUMBER}/artifacts/screenshots
                            echo $SUDO_PASS | sudo -S cp -r ${folderPath}/artifacts/screenshots/* ${WORKSPACE}/${BUILD_NUMBER}/artifacts/screenshots || echo 'No screenshot files found'
                            echo $SUDO_PASS | sudo -S cp -r ${folderPath}/report* ${WORKSPACE}/${BUILD_NUMBER}/report || echo 'No report files found'
                            echo $SUDO_PASS | sudo -S chmod -R 777 ${WORKSPACE}/${BUILD_NUMBER}/report
                            echo $SUDO_PASS | sudo -S chmod -R 777 ${WORKSPACE}/${BUILD_NUMBER}/artifacts/screenshots
                            """
                        }

                        sh "ls -l ${WORKSPACE}/${BUILD_NUMBER}/artifacts/screenshots/*.png || echo 'No screenshot files copied'"
                        sh "ls -l ${WORKSPACE}/${BUILD_NUMBER}/report/* || echo 'No report files copied'"

                        archiveArtifacts allowEmptyArchive: true, artifacts: "${BUILD_NUMBER}/artifacts/screenshots/*.png, ${BUILD_NUMBER}/report/*", fingerprint: false
                        echo "archiveArtifacts done"
                    } catch (Exception err) {
                        echo "Error during file copy: ${err.toString()}"
                    }
                }

                println "Publish Test Results ..."
                // Check if report.xml exists before publishing the results
                def reportExists = sh(script: "test -f ${WORKSPACE}/${BUILD_NUMBER}/report/report.xml && echo 'exists' || echo 'not exists'", returnStdout: true).trim()
                if (reportExists == 'exists') {
                    try {
                        junit "**/report.xml"
                    } catch (err) {
                        echo "junit report.xml, Caught: ${err}"
                    }
                } else {
                    echo "No report.xml found at ${WORKSPACE}/${BUILD_NUMBER}/report/. Skipping test results publishing."
                }

                println "Send Notification ..."
                // def buildStatus = currentBuild.result ?: 'SUCCESS' // Default to 'SUCCESS' if result is null
                // def subjectLine = "Build $build_name Finished with Status: ${buildStatus}"
                // def emailBody = """
                //     <html>
                //     <body>
                //         <p>The build <strong>$build_name</strong> has finished with status: <strong>${buildStatus}</strong>.</p>
                //         <p>View the detailed artifacts, report.html and logs via the following link:</p>
                //         <p><a href="http://172.18.57.7:8080/job/${env.JOB_NAME}/${BUILD_NUMBER}">View Logs and Reports</a></p>
                //     </body>
                //     </html>
                // """

                // emailext(
                //     from: 'fosqa@fortinet.com',
                //     to: "$send_to",
                //     subject: subjectLine,
                //     body: emailBody
                // )
            }
        }
    }
  }
}
