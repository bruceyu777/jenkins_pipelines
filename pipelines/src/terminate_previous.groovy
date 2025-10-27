/**
 * Terminate Autotest Processes Pipeline
 *
 * This pipeline allows QA to terminate all autotest-related programs
 * running on a specific Jenkins all-in-one node.
 *
 * Parameters:
 *   - NODE_NAME: The Jenkins node to terminate processes on (e.g., node1, node2)
 */

pipeline {
    agent none

    parameters {
        string(
            name: 'NODE_NAME',
            defaultValue: 'node1',
            description: 'Name of the Jenkins node (e.g., node1, node2, node3)'
        )
    }

    options {
        buildDiscarder(logRotator(numToKeepStr: '30', daysToKeepStr: '60'))
        timestamps()
        timeout(time: 10, unit: 'MINUTES')
        disableConcurrentBuilds()
        buildName("#${BUILD_NUMBER} - ${NODE_NAME}")
    }

    stages {
        stage('Terminate Processes') {
            agent { label params.NODE_NAME }

            steps {
                script {
                    echo "=" * 80
                    echo "🛑 TERMINATE AUTOTEST PROCESSES"
                    echo "=" * 80
                    echo ""
                    echo "Target Node: ${params.NODE_NAME}"
                    echo "Triggered by: ${currentBuild.getBuildCauses()[0].userId ?: 'Unknown'}"
                    echo ""

                    // Execute the termination command
                    def makeCmd = "cd /home/fosqa/resources/tools && make terminate_previous"

                    echo "Executing: ${makeCmd}"
                    echo ""

                    def result = sh(
                        script: makeCmd,
                        returnStatus: true
                    )

                    echo ""

                    if (result == 0) {
                        echo "✅ Termination completed successfully"
                    } else if (result == 2) {
                        echo "ℹ️  No autotest processes found to terminate"
                        currentBuild.result = 'SUCCESS'
                    } else {
                        error("❌ Termination failed with exit code: ${result}")
                    }
                }
            }
        }

        stage('Summary') {
            agent { label 'master' }

            steps {
                script {
                    echo ""
                    echo "=" * 80
                    echo "📊 TERMINATION SUMMARY"
                    echo "=" * 80
                    echo "Node: ${params.NODE_NAME}"
                    echo "Status: ${currentBuild.result ?: 'SUCCESS'}"
                    echo "Duration: ${currentBuild.durationString}"
                    echo "=" * 80
                }
            }
        }
    }

    post {
        success {
            echo "✅ Pipeline completed successfully"
        }

        failure {
            echo "❌ Pipeline failed - check logs for details"
        }

        always {
            echo "Pipeline finished at: ${new Date()}"
        }
    }
}
