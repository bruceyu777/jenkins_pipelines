pipeline {
    parameters {
        // Node Name parameter remains a standard choice.
        choice(
            name: 'NODE_NAME',
            choices: ['node1', 'node2', 'node3'],
            description: 'Select the Jenkins node to run the pipeline on.'
        )

        // SVN Branch: choice with an "Other" option.
        choice(
            name: 'SVN_BRANCH',
            choices: ['trunk', 'v740', 'v720', 'Other'],
            description: 'Select SVN test branch or choose "Other" to specify a custom branch'
        )
        // Custom SVN Branch string parameter.
        string(
            name: 'CUSTOM_SVN_BRANCH',
            defaultValue: '',
            trim: true,
            description: 'Specify custom SVN branch if "Other" is selected above'
        )

        // Feature Name: choice with an "Other" option.
        choice(
            name: 'FEATURE_NAME',
            choices: ['avfortisandbox', 'webfilter', 'filefilter', 'Other'],
            description: 'Select SVN test feature or choose "Other" to specify a custom feature'
        )
        // Custom Feature Name string parameter.
        string(
            name: 'CUSTOM_FEATURE_NAME',
            defaultValue: '',
            trim: true,
            description: 'Specify custom SVN test feature if "Other" is selected above'
        )

        // TEST_CONFIG: choice with an "Other" option.
        choice(
            name: 'TEST_CONFIG_CHOICE',
            choices: ['env.newman.FGT_KVM.avfortisandbox.conf', 'env.FGTVM64.webfilter_demo.conf','env.default.conf', 'Other'],
            description: 'Select env file name or choose "Other" for a custom value'
        )
        // Custom TEST_CONFIG string parameter.
        string(
            name: 'CUSTOM_TEST_CONFIG',
            defaultValue: '',
            trim: true,
            description: 'Specify custom env file name if "Other" is selected above'
        )

        // TEST_GROUP: choice with an "Other" option.
        choice(
            name: 'TEST_GROUP_CHOICE',
            choices: ['grp.avfortisandbox_fortistack.full', 'grp.webfilter_basic.full', 'grp.default.full', 'Other'],
            description: 'Select test group file name or choose "Other" for a custom value'
        )
        // Custom TEST_GROUP string parameter.
        string(
            name: 'CUSTOM_TEST_GROUP',
            defaultValue: '',
            trim: true,
            description: 'Specify custom test group file name if "Other" is selected above'
        )
        // build_name
        string(
            name: 'build_name',
            defaultValue: 'fortistack-',
            trim: true,
            description: 'Specify build_name of this pipeline'
        )
        // send_to
        string(
            name: 'send_to',
            defaultValue: 'yzhengfeng@fortinet.com',
            trim: true,
            description: 'Specify email address to be sent'
        )
    }
    
    // Use the selected node.
    agent { label "${params.NODE_NAME}" }
    
    stages {
        stage('Check Docker') {
            steps {
                echo "Checking Docker environment..."
                sh 'docker ps'
                sh 'docker-compose --version'
                sh 'virsh -c qemu:///system list --all'
            }
        }

        stage('Running test cases') {
            steps {
                script {
                    def branch = (params.SVN_BRANCH == 'Other') ? params.CUSTOM_SVN_BRANCH : params.SVN_BRANCH
                    def feature = (params.FEATURE_NAME == 'Other') ? params.CUSTOM_FEATURE_NAME : params.FEATURE_NAME
                    def testConfig = (params.TEST_CONFIG_CHOICE == 'Other') ? params.CUSTOM_TEST_CONFIG : params.TEST_CONFIG_CHOICE
                    def testGroup = (params.TEST_GROUP_CHOICE == 'Other') ? params.CUSTOM_TEST_GROUP : params.TEST_GROUP_CHOICE

                    sh """
                      cd /home/fosqa/autolibv3
                      sudo chmod -R 777 .
                      . /home/fosqa/autolibv3/venv/bin/activate
                      python3 autotest.py -e testcase/${branch}/${feature}/${testConfig} -g testcase/${branch}/${feature}/${testGroup} -d
                      """
                }
            }
        }

        stage('Archive Results') {
            steps {
                script {
                    def outputsDir = "/home/fosqa/autolibv3/outputs"
                    
                    // Find the newest folder (by modification time) in the outputs directory. /home/fosqa/autolibv3/outputs/2025-02-21/
                    def latestDateFolder = sh(returnStdout: true, script: "ls -td ${outputsDir}/*/ | head -1").trim()
                    echo "Latest date folder: ${latestDateFolder}"
                    
                    // In that folder, find the newest subfolder. /home/fosqa/autolibv3/outputs/2025-02-21/15-37-16--group--grp.webfilter_basic/ 
                    def latestSubFolder = sh(returnStdout: true, script: "ls -td ${latestDateFolder}*/ | head -1").trim()
                    echo "Latest results folder: ${latestSubFolder}"
                    
                    // Copy the folder into the workspace (so Jenkins can archive it)
                    //cp -r /home/fosqa/autolibv3/outputs/2025-02-21/15-37-16--group--grp.webfilter_basic/ /home/jenkins/workspace/archive_test_result/test_results
                    sh "cp -r ${latestSubFolder} ${WORKSPACE}/test_results"

                    sh "cp -r ${latestSubFolder}summary/summary.html ${WORKSPACE}/summary.html"


                    archiveArtifacts artifacts: "test_results/**, summary.html", fingerprint: false

                    // Archive the HTML report folder so it can be viewed in Jenkins.
                    publishHTML(target: [
                        reportDir: "",
                        reportFiles: 'summary.html',
                        reportName: "summary.html"
                    ])

                }

            }
        }
    }

    post {
        always {
            echo "Pipeline completed. Check console output for details."

            script {sv

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
