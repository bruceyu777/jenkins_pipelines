def html_report_file_name = "report_csf.html"
def xml_file_name = "report_csf.xml"
def sessionId = 12345789
def htmlFilePath = ''
def xmlFilePath = ''
def baseFolder = '/home/bruce/autotest/csf_tests'
def testFolder = '/home/bruce/autotest/csf_tests/test_gui_vm_others'

@Library('sharedLib') _

pipeline {

    agent {
                node {
                    label 'master'
                }
            }


    stages {
        stage('Checkout') {
            steps
            {
                script {
                    // Call the getDutInfo method to retrieve dut_release and dut_build
                    def dutInfo = getDutInfo(testFolder, WORKSPACE, env, Config)

                    currentBuild.displayName = "[FOS GUI] test_gui_application"
                    currentBuild.description = "release:$dutInfo.dut_release B$dutInfo.dut_build config: $Config"
                }

            }
        }

        stage('Test') {
            steps {
                println "Starting test..."
                script {

                        script {
                            // Generate a timestamp in the format "yyyyMMddHHmmss_SSS"
                            println("Generate Session ID ...")
                            def currentDateTime = new Date()
                            def sessionIdFormat = new java.text.SimpleDateFormat("yyyyMMddHHmmss_'${currentDateTime.getTime() % 1000}'")
                            env.SESSION_ID = sessionIdFormat.format(currentDateTime)

                            // Print the SESSION_ID for verification
                            println("Generated Session ID: ${env.SESSION_ID}")
                        }

                        try {
                            sh """
                            sudo chmod -R 777 ${baseFolder} || true
                            cd ${testFolder} && make dockers_test_log session_id=${env.SESSION_ID} stack=${Config} reportportal=${reportportal} oriole_config=${oriole_config}
                            """
                        }
                        catch(Throwable t) {
                            echo "Caught Throwable: ${t}"}
                }
            }
        }

    }
    post{
        always
            {
                println "Processing and publish html report in Jenkins"
                sleep(2)

                script {
                        println "Run and Parse Python Script ..."
                        def jsonOutput = runAndParsePythonScript(testFolder, env.SESSION_ID)
                        htmlFilePath = jsonOutput.html_file_path
                        xmlFilePath = jsonOutput.xml_file_path
                        docker_report_folders_list = jsonOutput.docker_report_folders ?: []


                        try {
                            withCredentials([string(credentialsId: 'sudoPassword', variable: 'SUDO_PASS')]) {
                                sh """
                                echo $SUDO_PASS | sudo -S chmod -R 777 ${folderPath}/artifacts/screenshots || true
                                echo $SUDO_PASS | sudo -S mkdir -p ${workspace}/${buildNumber}/report
                                echo $SUDO_PASS | sudo -S mkdir -p ${workspace}/${buildNumber}/artifacts/screenshots
                                echo $SUDO_PASS | sudo -S cp -r ${folderPath}/artifacts/screenshots/* ${workspace}/${buildNumber}/artifacts/screenshots || echo 'No screenshot files found'

                                echo $SUDO_PASS | sudo -S cp -r ${htmlFilePath} ${workspace}/${buildNumber}/report/report.html || echo 'No report files found'
                                echo $SUDO_PASS | sudo -S cp -r ${xmlFilePath} ${workspace}/${buildNumber}/report/report.xml || echo 'No report files found'
                                echo $SUDO_PASS | sudo -S chmod -R 777 ${workspace}/${buildNumber}/report
                                echo $SUDO_PASS | sudo -S chmod -R 777 ${workspace}/${buildNumber}/artifacts/screenshots
                                """
                            }
                            archiveArtifacts allowEmptyArchive: true, artifacts: "${buildNumber}/artifacts/screenshots/*.png, ${buildNumber}/report/*", fingerprint: false
                            echo "archiveArtifacts done"

                        } catch (Exception err) {
                            echo "Caught an exception while publishing the HTML reports: ${err}"
                        }

                        println "Publish Test Results ..."
                        publishTestResults(WORKSPACE, BUILD_NUMBER)
                        println "Send Notification ..."
                        def buildStatus = currentBuild.result ?: 'SUCCESS'

                        // Call the getDutInfo method to retrieve dut_release and dut_build
                        def dutInfo = getDutInfo(testFolder, WORKSPACE, env)

                        // Send notification with the retrieved or default values
                        sendNotification(currentBuild.displayName, buildStatus, env, BUILD_NUMBER, "$send_to", "$Config", "$dutInfo.dut_release", "$dutInfo.dut_build")

                    }
                }

            }

        }
