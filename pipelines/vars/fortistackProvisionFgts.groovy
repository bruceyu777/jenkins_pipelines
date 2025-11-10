def call() {

fortistackMasterParameters(only: ['NODE_NAME','RELEASE','BUILD_NUMBER','FGT_TYPE','TERMINATE_PREVIOUS'])

pipeline {

    // Dynamically select the agent based on user input
    agent { label "${params.NODE_NAME}" }
    options {
        buildDiscarder(logRotator(daysToKeepStr: '14'))
    }

    stages {
        stage('Set Build Display Name') {
            steps {
                script {
                    currentBuild.displayName = "#${currentBuild.number} ${params.NODE_NAME}-r${params.RELEASE}-${params.BUILD_NUMBER}-${params.FGT_TYPE}"
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

        stage('GIT Pull') {
            steps {
                script {
                    echo "=== Local Git Update ==="
                    gitUpdate(
                        repoPath: '/home/fosqa/resources/tools',
                        failOnError: false
                    )
                }
            }
        }

        stage('Wait until previous build finish') {
            steps {
                script {
                   echo "Waiting for previous builds to finish..."
                   sh """
                        cd /home/fosqa/resources/tools
                        . /home/fosqa/resources/tools/venv/bin/activate
                        sudo -E PYTHONUNBUFFERED=1 stdbuf -oL -eL ./venv/bin/python3 -u wait_until_aio_pipeline_not_running.py --terminate ${params.TERMINATE_PREVIOUS}
                   """
                }
            }
        }

        stage('Provisioning FGT') {
            steps {
                echo "provisioning FGT..."
                sh """
                  cd /home/fosqa/resources/tools
                  . /home/fosqa/resources/tools/venv/bin/activate
                  pip install -r requirements.txt

                  sudo pwd
                  hostname
                  sudo make provision_fgt fgt_type='${params.FGT_TYPE}' node=${params.NODE_NAME} release=${params.RELEASE} build=${params.BUILD_NUMBER}
                  sudo make update_vm_license_valid_until
                """
            }
        }

    }

    post {
        failure {
            script {
                // Only send email on failure
                echo "Build failed - sending notification email..."

                // Generate node_info file
                sh """
                cd /home/fosqa/resources/tools
                sudo ./venv/bin/python3 get_node_info.py
                """

                // Read node info
                def nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.txt').trim()
                def dispName = currentBuild.displayName

                // Build failure email
                def subject = "❌ FGT Provisioning FAILED - ${dispName}"
                def body = """
                <h2 style="color:red;">❌ FGT Provisioning Failed</h2>
                <h3>Build: ${dispName}</h3>
                <p><b>Status:</b> <span style="color:red;">FAILED</span></p>
                <p><b>Console Log:</b> <a href="${env.BUILD_URL}console">${env.BUILD_URL}console</a></p>
                <hr>
                <h3>Node Information</h3>
                <pre style="font-family:monospace; white-space:pre-wrap; background:#f5f5f5; padding:10px; border:1px solid #ddd;">${nodeInfo}</pre>
                <hr>
                <p><b>Next Steps:</b></p>
                <ul>
                    <li>Check the console log for error details</li>
                    <li>Verify the build ${params.BUILD_NUMBER} exists for release ${params.RELEASE}</li>
                    <li>Check if the node ${params.NODE_NAME} is accessible</li>
                    <li>Retry the provisioning if needed</li>
                </ul>
                """

                sendFosqaEmail(
                    to:      'yzhengfeng@fortinet.com',
                    subject: subject,
                    body:    body
                )
            }
        }
        success {
            script {
                // Silent success - no email sent
                echo "✅ FGT provisioning completed successfully - no email notification sent"
            }
        }
    }

}
}
