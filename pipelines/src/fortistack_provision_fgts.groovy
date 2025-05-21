@Library('sharedLib') _

pipeline {
    // Define parameters to be prompted to the user before the job starts
    parameters {
        // 1. Node Name Parameter (customizable)
        string(
            name: 'NODE_NAME',
            defaultValue: 'node1',
            trim: true,
            description: 'Enter the Jenkins node label to run the pipeline on (e.g., node1).'
        )
        
        // 2. Build Number Parameter
        string(
            name: 'RELEASE',
            defaultValue: '7',
            trim: true,
            description: 'Enter a 1-digit release number (e.g., 7, or 8).'
        )

        string(
            name: 'BUILD_NUMBER',
            defaultValue: '3473',
            trim: true,
            description: 'Enter a 4-digit build number (e.g., 3473).'
        )
        
        // 3. FGT Type Parameter
        choice(
            name: 'FGT_TYPE',
            choices: ['ALL', 'FGT_PFW', 'FGTA', 'FGTB', 'FGTC', 'FGTD'],
            description: 'Select the FGT type to provision.'
        )
    }
    
    // Dynamically select the agent based on user input
    agent { label "${params.NODE_NAME}" }
    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
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

        stage('Provisioning FGT') {
            steps {
                echo "provisioning FGT..."
                sh """
                  cd /home/fosqa/resources/tools
                  git config --global --add safe.directory /home/fosqa/resources/tools
                  sudo -u fosqa git pull
                  sudo pwd
                  hostname
                  sudo make provision_fgt fgt_type=${params.FGT_TYPE} node=${params.NODE_NAME} release=${params.RELEASE} build=${params.BUILD_NUMBER}
                  sudo make update_vm_license_valid_until
                """
            }
        }
        stage('Get Node information summary') {
            steps {
                echo "Summarize useful information..."
                sh """
                  cd /home/fosqa/resources/tools
                  python3 get_node_info.py
                """
            }
        }

    }

    post {
        success {
            sendFosqaEmail(
                to:       'yzhengfeng@fortinet.com',
                subject:  "Build #${env.BUILD_NUMBER} Succeeded",
                body:     "<p>Good news: job <b>${env.JOB_NAME}</b> completed at ${new Date()}</p>"
            )
        }
        failure {
            sendFosqaEmail(
                to:      'yzhengfeng@fortinet.com',
                subject: "Build #${env.BUILD_NUMBER} FAILED",
                body:    "<p>Check console output: ${env.BUILD_URL}</p>"
            )
        }
    }
}
