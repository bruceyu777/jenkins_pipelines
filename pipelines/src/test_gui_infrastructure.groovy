def html_report_file_name = "report_csf.html"
def xml_file_name = "report_csf.xml"
def sessionId = 12345
def htmlFilePath = ''
def xmlFilePath = ''
def atf_branch = 'main_csf'
def fos_pom_branch = 'v760'
def fos_test_branch = 'v740'
def test_package_branch = 'v760'
def baseFolder = '/home/bruce/autotest/csf_tests'
def testFolder = '/home/bruce/autotest/csf_tests/test_gui_infrastructure'
def docker_report_folders_list = []

pipeline {
    agent {
        label 'master'
    }

    options {
        // Keep the 30 most recent builds
        buildDiscarder(logRotator(numToKeepStr: '30'))
    }

    stages {
        stage('Checkout') {
            steps {
                script {
                    currentBuild.displayName = "#${BUILD_NUMBER},-$fgtBuild,"
                    currentBuild.description = "FGT_build: $fgtBuild FGT_IP: $ip config: $Config "
                }

                println "git pull updated autotest code"
                script {
                    try {
                        sh """
                        cd ${baseFolder}/atf
                        git checkout $atf_branch
                        git pull
                        sleep 1
                        cd ${baseFolder}/fos_pom
                        git checkout $fos_pom_branch
                        git pull
                        sleep 1
                        cd ${baseFolder}
                        git checkout $fos_test_branch
                        git pull
                        sleep 1
                        cd ${testFolder}
                        git checkout $test_package_branch
                        git pull
                        sleep 1
                        """
                    } catch (err) {
                        echo "Caught: ${err}"
                    }
                }
            }
        }

        stage('Test') {
            steps {
                println "Starting test..."
                script {
                    try {
                        script {
                            // Generate session id based on current timestamp in the format "yyyyMMddHHmmss_SSS"
                            println("Generate Session ID ...")
                            def currentDateTime = new Date()
                            def sessionIdFormat = new java.text.SimpleDateFormat("yyyyMMddHHmmss_'${currentDateTime.getTime() % 1000}'")
                            env.SESSION_ID = sessionIdFormat.format(currentDateTime)

                            println("Generated Session ID: ${env.SESSION_ID}")
                        }

                        script {
                            // Use withCredentials to inject the password into the script
                            withCredentials([string(credentialsId: 'sudoPassword', variable: 'SUDO_PASS')]) {
                                def status = sh(script: """
                                    echo $SUDO_PASS | sudo -S chmod -R 777 ${testFolder} || true
                                    echo $SUDO_PASS | sudo -S -u bruce sh -c 'cd ${testFolder} && make docker_test force_build=true name=${keywords} session_id=${env.SESSION_ID} stack=${Config} rp_launch=${rp_launch} reportportal=${reportportal} oriole_config=${oriole_config} browser_type=firefox submit=true headless=true'
                                """, returnStatus: true)

                                if (status != 0) {
                                    error("Test execution failed")
                                }
                            }
                        }
                    } catch (err) {
                        echo "Caught: ${err}"
                        currentBuild.result = 'FAILURE'
                        error("Test stage failed")
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
                    def output = sh(script: "cd ${testFolder} && . ../venv*/bin/activate && python3 docker_test_report_manager.py --method read_docker_combined_report --file_path ${testFolder}/docker_combined_report_list.txt --session_id=${env.SESSION_ID}", returnStdout: true).trim()
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
                def buildStatus = currentBuild.result ?: 'SUCCESS' // Default to 'SUCCESS' if result is null
                def subjectLine = "Build $build_name Finished with Status: ${buildStatus}"
                def emailBody = """
                    <html>
                    <body>
                        <p>The build <strong>$build_name</strong> has finished with status: <strong>${buildStatus}</strong>.</p>
                        <p>View the detailed artifacts, report.html and logs via the following link:</p>
                        <p><a href="http://172.18.57.7:8080/job/${env.JOB_NAME}/${BUILD_NUMBER}">View Logs and Reports</a></p>
                    </body>
                    </html>
                """

                emailext(
                    from: 'fosqa@fortinet.com',
                    to: "$send_to",
                    subject: subjectLine,
                    body: emailBody
                )
            }
        }
    }
}
