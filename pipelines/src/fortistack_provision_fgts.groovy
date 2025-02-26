pipeline {
    // Define parameters to be prompted to the user before job starts
    parameters {
        // 1. Node Name Parameter
        choice(
            name: 'NODE_NAME',
            choices: ['node1', 'node2', 'node3'], // Update with your actual node labels
            description: 'Select the Jenkins node to run the pipeline on.'
        )
        
        // 2. Build Number Parameter
        string(
            name: 'BUILD_NUMBER',
            defaultValue: '',
            trim: true,
            description: 'Enter a 4-digit build number (e.g., 3473).'
        )
        
        // 3. FGT Type Parameter
        choice(
            name: 'FGT_TYPE',
            choices: ['FGT_PFW', 'FGTA', 'FGTB', 'FGTC', 'FGTD', 'ALL'],
            description: 'Select the FGT type to provision.'
        )
    }
    
    // Dynamically select the agent based on user input
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

        stage('Bring up FGTs') {
            steps {
                sh """
                  cd /home/fosqa/resources/tools
                  sudo make provision_fgt fgt_type=${params.FGT_TYPE} node=${params.NODE_NAME} build=${params.BUILD_NUMBER}
                """
            }
        }

        stage('Running test cases'){
            steps {
                sh """
                  cd /home/fosqa/autolibv3/testcase/${params.SVN_BRANCH}/${params.FEATURE_NAME}
                  sudo make provision_fgt fgt_type=${params.FGT_TYPE} node=${params.NODE_NAME} build=${params.BUILD_NUMBER}
                """
            }
        }

    }

    post {
        always {
            echo "Pipeline completed. Check console output for details."
        }
    }
}
