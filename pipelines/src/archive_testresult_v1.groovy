pipeline {
    parameters {
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
            name: 'TEST_GROUP_CHOICE',
            defaultValue: 'grp.avfortisandbox_fortistack.full',
            trim: true,
            description: 'Test group file name'
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
                    currentBuild.displayName = "#${currentBuild.number} ${params.NODE_NAME}-${params.TEST_GROUP_CHOICE}"
                }
            }
        }
        stage('Archive Test Results') {
            steps {
                script {
                    def outputsDir = "/home/fosqa/${params.LOCAL_LIB_DIR}/outputs"
                    // Search two levels deep for directories matching the test group.
                    def latestSubFolder = sh(
                        returnStdout: true,
                        script: """
                            find ${outputsDir} -mindepth 2 -maxdepth 2 -type d -name "*--group--${params.TEST_GROUP_CHOICE}" -printf '%T@ %p\\n' | sort -nr | head -1 | cut -d' ' -f2-
                        """
                    ).trim()
                    
                    if (!latestSubFolder) {
                        echo "Warning: No test results folder found for test group '${params.TEST_GROUP_CHOICE}' in ${outputsDir}. Skipping archiving."
                    } else {
                        echo "Latest results folder for test group '${params.TEST_GROUP_CHOICE}': ${latestSubFolder}"
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
