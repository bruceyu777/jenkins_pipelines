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

        stage('Run Python Script') {
            steps {
                echo "Running iptable script..."
                sh """
                  cd /home/fosqa/resources/tools
                  sudo pwd
                  sudo ls
                  hostname
                  cat Makefile
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
