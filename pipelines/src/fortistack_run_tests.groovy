pipeline {
    parameters {
        // Node Name parameter remains a standard choice.
        choice(
            name: 'NODE_NAME',
            choices: ['node1', 'node2', 'node3'],
            description: 'Select the Jenkins node to run the pipeline on.'
        )
        
        // Local library directory parameter (for flexibility)
        string(
            name: 'LOCAL_LIB_DIR',
            defaultValue: 'autolibv3',
            trim: true,
            description: 'Specify the local library directory (e.g., autolibv3 or autolibv3_bak_2025).'
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
        
        // build_name parameter
        string(
            name: 'build_name',
            defaultValue: 'fortistack-',
            trim: true,
            description: 'Specify build name of this pipeline'
        )
        
        // send_to parameter
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
                      cd /home/fosqa/${params.LOCAL_LIB_DIR}
                      sudo chmod -R 777 .
                      . /home/fosqa/${params.LOCAL_LIB_DIR}/venv/bin/activate
                      python3 autotest.py -e testcase/${branch}/${feature}/${testConfig} -g testcase/${branch}/${feature}/${testGroup} -d
                      """
                }
            }
        }

        stage('Archive Results') {
            steps {
                script {
                    // Define the outputs directory based on the local library directory parameter.
                    def outputsDir = "/home/fosqa/${params.LOCAL_LIB_DIR}/outputs"
                    
                    // Find the newest date folder (e.g. /home/fosqa/autolibv3/outputs/2025-02-24/)
                    def latestDateFolder = sh(returnStdout: true, script: "ls -td ${outputsDir}/*/ | head -1").trim()
                    echo "Latest date folder: ${latestDateFolder}"
                    
                    // In that folder, find the newest subfolder (e.g. .../11-37-15--group--grp.avfortisandbox_fortistack/)
                    def latestSubFolder = sh(returnStdout: true, script: "ls -td ${latestDateFolder}*/ | head -1").trim()
                    echo "Latest results folder: ${latestSubFolder}"
                    
                    // Copy the entire latest subfolder into the workspace under 'test_results'
                    sh "cp -r ${latestSubFolder} ${WORKSPACE}/test_results"
                    
                    // Copy the summary.html file from the latest subfolder's summary directory into the workspace root
                    // If the summary file is located at: ${latestSubFolder}/summary/summary.html
                    sh "cp ${latestSubFolder}summary/summary.html ${WORKSPACE}/summary.html"
                    
                    // Archive the results folder and summary file as build artifacts
                    archiveArtifacts artifacts: "test_results/**, summary.html", fingerprint: false
                    
                    // Publish the HTML report so that only the summary.html file is viewable.
                    // Since we copied summary.html to the workspace root, we can use reportDir "."
                    publishHTML(target: [
                        reportDir: ".",
                        reportFiles: 'summary.html',
                        reportName: "Test Results Summary"
                    ])
                }
            }
        }
    }
    
    post {
        always {
            echo "Pipeline completed. Check console output for details."
            
            // Email sending block (if configured)
            script {
                def buildStatus = currentBuild.result ?: 'SUCCESS'
                def subjectLine = "Build ${params.build_name} Finished with Status: ${buildStatus}"
                def emailBody = """
                    <html>
                    <body>
                        <p>The build <strong>${params.build_name}</strong> has finished with status: <strong>${buildStatus}</strong>.</p>
                        <p>View the detailed report using the HTML Report link on the build page.</p>
                    </body>
                    </html>
                """
                emailext(
                    from: 'fosqa@fortinet.com',
                    to: "${params.send_to}",
                    subject: subjectLine,
                    body: emailBody,
                    mimeType: 'text/html'
                )
            }
        }
    }
}
