def atf_branch = 'main_csf'
def fos_pom_branch = 'v760'
def fos_test_branch = 'v740'

def baseFolder = '/home/bruce/autotest/csf_tests'
def testFolder = '/home/bruce/autotest/csf_tests/test_label_check'
def docker_report_folders_list = []

@Library('sharedLib') _

pipeline {
    agent {
        label 'master'
    }

    options {
        // Keep the 60 most recent builds
        buildDiscarder(logRotator(numToKeepStr: '60'))
    }

    stages {
        stage('Checkout') {
            steps {
                script {
                    currentBuild.displayName = "#$build_name"
                }
            }
        }

        stage('Test') {
            steps {
                println "Starting test..."
                script {
                    catchError(buildResult: 'FAILURE', stageResult: 'FAILURE') {
                        try {
                            script {
                                println("Generate Session ID ...")
                                def currentDateTime = new Date()
                                def sessionIdFormat = new java.text.SimpleDateFormat("yyyyMMddHHmmss_'${currentDateTime.getTime() % 1000}'")
                                env.SESSION_ID = sessionIdFormat.format(currentDateTime)
                                println("Generated Session ID: ${env.SESSION_ID}")
                            }

                            script {
                                def json = params.test_data
                                def base64Json = Base64.encoder.encodeToString(json.getBytes())
                                env.TEST_DATA_JSON_BASE64 = base64Json
                                println("Environment test data is: ${env.TEST_DATA_JSON_BASE64}")
                            }

                            script {
                                withCredentials([string(credentialsId: 'sudoPassword', variable: 'SUDO_PASS')]) {
                                    def status = sh(script: """
                                        echo $SUDO_PASS | sudo -S chmod -R 777 ${testFolder} || true
                                        echo $SUDO_PASS | sudo -S -u bruce sh -c 'cd ${testFolder} && make docker_test force_build=true session_id=${env.SESSION_ID} test_data=${env.TEST_DATA_JSON_BASE64}'
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
        stage('Trigger Downstream Pipeline') {
            when {
                expression { return params.Trigger_Collect_Runtime_Hash }
            }
            steps {
                script {
                    try {
                        def downstreamJob = 'Collect_Runtime_Hash'

                        def downstreamBuild = build job: downstreamJob,
                            parameters: [
                                string(name: 'DUT_JSON_STR', value: params.test_data),
                                string(name: 'send_to', value: params.send_to)
                            ],
                            wait: true,
                            propagate: false // Handle failure manually

                        // Extract downstream build details
                        def downstreamBuildNumber = downstreamBuild.getNumber()
                        def downstreamBuildResult = downstreamBuild.getResult()
                        def downstreamBuildUrl = downstreamBuild.getAbsoluteUrl()

                        echo "Downstream pipeline '${downstreamJob}' triggered successfully with Build Number: ${downstreamBuildNumber} and Result: ${downstreamBuildResult}"

                        // Update the master build's description with downstream build details using HTML
                        currentBuild.description = """
                            <div>
                                <b>Downstream Build:</b>
                                <a href='${downstreamBuildUrl}'>#${downstreamBuildNumber}</a> -
                                <span style='color:${downstreamBuildResult == "SUCCESS" ? "green" : "red"}'>${downstreamBuildResult}</span>
                            </div>
                        """

                        // Update the build display name to include downstream build info
                        currentBuild.displayName = "#${currentBuild.number} - ${build_name} - Downstream #${downstreamBuildNumber} - ${downstreamBuildResult}"

                        // Optionally, mark the master build as FAILURE if downstream failed
                        // if (downstreamBuildResult != 'SUCCESS') {
                        //     currentBuild.result = 'FAILURE'
                        //     error("Downstream pipeline '${downstreamJob}' failed with status: ${downstreamBuildResult}. Aborting master pipeline.")
                        // }
                    } catch (err) {
                        echo "Failed to trigger downstream pipeline: ${err}"
                        currentBuild.description += "<br><b>Downstream Build:</b> Failed to trigger."
                        currentBuild.displayName = "#${currentBuild.number} - Downstream: Failed to Trigger"
                        currentBuild.result = 'FAILURE'
                        error("Aborting master pipeline due to downstream pipeline failure.")
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
                docker_report_folders_list = jsonOutput.docker_report_folders ?: []

                println "Add Artifacts ..."
                addArtifacts(docker_report_folders_list, WORKSPACE, BUILD_NUMBER)

                println "Publish Test Results ..."
                publishTestResults(WORKSPACE, BUILD_NUMBER)


                def buildStatus = currentBuild.result ?: 'SUCCESS' // Default to 'SUCCESS' if result is null
                def subjectLine = "Build $build_name Finished with Status: ${buildStatus}"
                def emailBody = """
                    <html>
                    <body>
                        <p>The build <strong>$build_name</strong> has finished with status: <strong>${buildStatus}</strong>.</p>
                        <p>View the detailed artifacts, report.html and logs via the following link:</p>
                        <p><a href="${env.JOB_URL}${BUILD_NUMBER}">View Logs and Reports</a></p>

                    </body>
                    </html>
                """

                emailext (
                    from: 'fosqa@fortinet.com',
                    to: "$send_to",
                    subject: subjectLine,
                    body: emailBody
                )
            }
        }
    }
}
