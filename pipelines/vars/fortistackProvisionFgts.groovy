def call() {

fortistackMasterParameters(only: ['NODE_NAME','RELEASE','BUILD_NUMBER','FGT_TYPE'])

pipeline {
    
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

        stage('GIT Pull') {
            steps {
                script {
                    withCredentials([usernamePassword(credentialsId: 'LDAP', usernameVariable: 'SVN_USER', passwordVariable: 'SVN_PASS')]) {
                        // Local Git update
                        echo "=== Step 1: Local Git update ==="
                        def innerGitCmd = "sudo -u fosqa bash -c 'cd /home/fosqa/resources/tools && " +
                                          "if [ -n \"\$(git status --porcelain)\" ]; then git stash push -m \"temporary stash\"; fi; " +
                                          "git pull; " +
                                          "if git stash list | grep -q \"temporary stash\"; then git stash pop; fi'"
                        echo "Executing local git pull command: ${innerGitCmd}"
                        try {
                            sh innerGitCmd
                        } catch (Exception e) {
                            echo "Local git pull failed: ${e.getMessage()}. Continuing without updating."
                        }

                    }
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
                        sudo -E PYTHONUNBUFFERED=1 stdbuf -oL -eL ./venv/bin/python3 -u wait_until_aio_pipeline_not_running.py
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
        // stage('Get Node information summary') {
        //     steps {
        //         echo "Summarize useful information..."
        //         sh """
        //           cd /home/fosqa/resources/tools
        //           sudo ./venv/bin/python3 get_node_info.py
        //         """
        //     }
        // }

    }

    post {
        always {
        script {
            // 1) generate the node_info file
            sh """
            cd /home/fosqa/resources/tools
            sudo ./venv/bin/python3 get_node_info.py
            """

            // 2) slurp it in
            def nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.txt').trim()
            def dispName = currentBuild.displayName
            def isSuccess = (currentBuild.currentResult == 'SUCCESS')

            // 3) common pieces
            def subject = isSuccess
            ? "Build ${env.BUILD_NUMBER} Succeeded on ${dispName}"
            : "Build ${env.BUILD_NUMBER} FAILED on ${dispName}"

            // 4) pick your body
            def body = """
            <h2>Build: ${dispName}</h2>
            <h3>Node Info</h3>
            <pre style="font-family:monospace; white-space:pre-wrap;">${nodeInfo}</pre>
            <p style="color:${isSuccess ? 'green' : 'red'};">
                ${ isSuccess 
                    ? '✅ Build completed successfully!'
                    : "❌ Build failed. See <a href='${env.BUILD_URL}'>console</a>."
                }
            </p>
            """

            // 5) send it once
            sendFosqaEmail(
            to:      'yzhengfeng@fortinet.com',
            subject: subject,
            body:    body
            )
        }
        }
    }

    // post {
    //     // success {
    //     //     sendFosqaEmail(
    //     //         to:       'yzhengfeng@fortinet.com',
    //     //         subject:  "Build #${env.BUILD_NUMBER} Succeeded",
    //     //         body:     "<p>Good news: job <b>${env.JOB_NAME}</b> completed at ${new Date()}</p>"
    //     //     )
    //     // }
    //     // failure {
    //     //     sendFosqaEmail(
    //     //         to:      'yzhengfeng@fortinet.com',
    //     //         subject: "Build #${env.BUILD_NUMBER} FAILED",
    //     //         body:    "<p>Check console output: ${env.BUILD_URL}</p>"
    //     //     )
    //     // }
    //     success {
    //         script {
    //             // 1. Read the node_info we just wrote
    //             def nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.txt').trim()
    //             // 2. Grab the display name you set earlier
    //             def dispName = currentBuild.displayName

    //             // 3. Build an HTML body that shows both
    //             def htmlBody = """
    //             <h2>Build: ${dispName}</h2>
    //             <h3>Node Info</h3>
    //             <pre style="font-family:monospace; white-space:pre-wrap;">${nodeInfo}</pre>
    //             <p>Everything completed successfully!</p>
    //             """

    //             // 4. Send it
    //             sendFosqaEmail(
    //             to:      'yzhengfeng@fortinet.com',
    //             subject: "Build ${env.BUILD_NUMBER} Succeeded on ${dispName}",
    //             body:    htmlBody
    //             )
    //         }
    //     }
    //     failure {
    //         script {
    //             def nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.txt').trim()
    //             def dispName = currentBuild.displayName
    //             def htmlBody = """
    //             <h2>Build: ${dispName}</h2>
    //             <h3>Node Info</h3>
    //             <pre style="font-family:monospace; white-space:pre-wrap;">${nodeInfo}</pre>
    //             <p style="color:red;">❌ Build failed. Check the console log: ${env.BUILD_URL}</p>
    //             """
    //             sendFosqaEmail(
    //             to:      'yzhengfeng@fortinet.com',
    //             subject: "Build ${env.BUILD_NUMBER} FAILED on ${dispName}",
    //             body:    htmlBody
    //             )
    //         }
    //     }

    // }
}
}