def htmlFilePath = ''
def xmlFilePath = ''
def atf_branch = 'main_csf'
def fos_pom_branch = 'v760'
def fos_test_branch = 'v740'
def test_package_branch = 'v760'
def baseFolder = '/home/bruce/autotest/csf_tests'
def testFolder = '/home/bruce/autotest/csf_tests/test_gui_application'
def docker_report_folders_list = []

@Library('sharedLib') _

pipeline {
    agent {
        label 'master'
    }

    stages {
        stage('Checkout') {
            steps {
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
                    try {
                        script {
                            def sessionIdFormat = new java.text.SimpleDateFormat("yyyyMMddHHmmss_'${new Date().getTime() % 1000}'")
                            env.SESSION_ID = sessionIdFormat.format(new Date())
                            println("Generated Session ID: ${env.SESSION_ID}")
                        }

                        script {
                            withCredentials([string(credentialsId: 'sudoPassword', variable: 'SUDO_PASS')]) {
                                def status = sh(script: """
                                    echo $SUDO_PASS | sudo -S chmod -R 777 ${testFolder} || true
                                    echo $SUDO_PASS | sudo -S -u bruce sh -c 'cd ${testFolder} && make docker_test force_build=true name=${keywords} session_id=${env.SESSION_ID} stack=${Config} rerun=${rerun} rp_launch=${rp_launch} reportportal=${reportportal} oriole_config=${oriole_config} rerun=1browser_type=firefox submit=true headless=true'
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
                def jsonOutput = runAndParsePythonScript(testFolder, env.SESSION_ID)
                htmlFilePath = jsonOutput.html_file_path
                xmlFilePath = jsonOutput.xml_file_path
                docker_report_folders_list = jsonOutput.docker_report_folders ?: []

                println "Add Artifacts ..."
                addArtifacts(docker_report_folders_list, WORKSPACE, BUILD_NUMBER)

                println "Publish Test Results ..."
                publishTestResults(WORKSPACE, BUILD_NUMBER)

                println "Send Notification ..."
                def buildStatus = currentBuild.result ?: 'SUCCESS'

                // Call the getDutInfo method to retrieve dut_release and dut_build
                def dutInfo = getDutInfo(testFolder, WORKSPACE, env, Config)

                // Send notification with the retrieved or default values
                sendNotification(currentBuild.displayName, buildStatus, env, BUILD_NUMBER, "$send_to", "$Config", "$dutInfo.dut_release", "$dutInfo.dut_build")
            }
        }
    }

}
