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
            name: 'SVN_BRANCH',
            defaultValue: 'trunk',
            trim: true,
            description: 'SVN test branch'
        )
        string(
            name: 'FEATURE_NAME',
            defaultValue: 'avfortisandbox',
            trim: true,
            description: 'SVN test feature'
        )
        string(
            name: 'TEST_CONFIG_CHOICE',
            defaultValue: 'env.newman.FGT_KVM.avfortisandbox.conf',
            trim: true,
            description: 'Env file name'
        )
        string(
            name: 'TEST_GROUP_CHOICE',
            defaultValue: 'grp.avfortisandbox_fortistack.full',
            trim: true,
            description: 'Test group file name'
        )
        //DOCKER_COMPOSE_FILE_CHOICE
        string(
            name: 'DOCKER_COMPOSE_FILE_CHOICE',
            defaultValue: 'docker.avfortisandbox_avfortisandbox.yml',
            trim: true,
            description: 'docker compose file name'
        )
        string(
            name: 'build_name',
            defaultValue: 'fortistack-',
            trim: true,
            description: 'Build name'
        )
        string(
            name: 'send_to',
            defaultValue: 'yzhengfeng@fortinet.com',
            trim: true,
            description: 'Email address'
        )
    }
    
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
                    // Use the parameters directly passed from the master pipeline.
                    def branch    = params.SVN_BRANCH
                    def feature   = params.FEATURE_NAME
                    def testConfig = params.TEST_CONFIG_CHOICE
                    def testGroup  = params.TEST_GROUP_CHOICE
                    def docker_compose_file = params.DOCKER_COMPOSE_FILE_CHOICE

                    withCredentials([usernamePassword(credentialsId: 'LDAP', usernameVariable: 'SVN_USER', passwordVariable: 'SVN_PASS')]) {
                        sh """
                        cd /home/fosqa/resources/tools
                        python3 set_kvm_docker_network.py
                        sudo /home/fosqa/resources/tools/venv/bin/python get_dockerfile_from_cdn.py --feature ${feature} --force

                        cd /home/fosqa/testcase/${branch}/${feature}
                        sudo rm docker_filesys
                        sudo ln -s /home/fosqa/docker_filesys/${feature} docker_filesys

                        cd /home/fosqa/${params.LOCAL_LIB_DIR}/testcase/${branch}
                        sudo svn checkout https://qa-svn.corp.fortinet.com/svn/qa/FOS/testcase/${branch}/${feature} \\
                            --username \$SVN_USER \\
                            --password \$SVN_PASS \\
                            --non-interactive
                        cd /home/fosqa/${params.LOCAL_LIB_DIR}/testcase/${branch}/${feature}
                        sudo svn update --username \$SVN_USER --password \$SVN_PASS --non-interactive

                        docker compose -f /home/fosqa/testcase/${branch}/${feature}/docker/${docker_compose_file} down
                        docker compose -f /home/fosqa/testcase/${branch}/${feature}/docker/${docker_compose_file} up -d

                        cd /home/fosqa/${params.LOCAL_LIB_DIR}
                        sudo chmod -R 777 .
                        . /home/fosqa/${params.LOCAL_LIB_DIR}/venv/bin/activate
                        python3 autotest.py -e testcase/${branch}/${feature}/${testConfig} -g testcase/${branch}/${feature}/${testGroup} -d

                        docker compose -f /home/fosqa/testcase/${branch}/${feature}/docker/${docker_compose_file} down
                        """
                    }
                }
            }
        }

        // stage('Running test cases') {
        //     steps {
        //         script {
        //             // Use the parameters directly passed from the master pipeline.
        //             def branch    = params.SVN_BRANCH
        //             def feature   = params.FEATURE_NAME
        //             def testConfig = params.TEST_CONFIG_CHOICE
        //             def testGroup  = params.TEST_GROUP_CHOICE
        //             def docker_compose_file = params.DOCKER_COMPOSE_FILE_CHOICE

        //             sh """
        //               cd /home/fosqa/resources/tools
        //               python3 set_kvm_docker_network.py
        //               sudo /home/fosqa/resources/tools/venv/bin/python get_dockerfile_from_cdn.py --feature ${feature} --force

        //               cd /home/fosqa/testcase/${branch}/${feature}
        //               sudo rm docker_filesys
        //               sudo ln -s /home/fosqa/docker_filesys/${feature} docker_filesys

        //               cd /home/fosqa/${params.LOCAL_LIB_DIR}/testcase/${branch}
        //               sudo svn checkout https://qa-svn.corp.fortinet.com/svn/qa/FOS/testcase/${branch}/${feature} \\
        //                 --username yzhengfeng \\
        //                 --password L0rdAroundY@u \\
        //                 --non-interactive
        //               cd /home/fosqa/${params.LOCAL_LIB_DIR}/testcase/${branch}/${feature}
        //               sudo svn update --username yzhengfeng --password L0rdAroundY@u --non-interactive

        //               docker compose -f /home/fosqa/testcase/${branch}/${feature}/docker/${docker_compose_file} down
        //               docker compose -f /home/fosqa/testcase/${branch}/${feature}/docker/${docker_compose_file} up -d

        //               cd /home/fosqa/${params.LOCAL_LIB_DIR}
        //               sudo chmod -R 777 .
        //               . /home/fosqa/${params.LOCAL_LIB_DIR}/venv/bin/activate
        //               python3 autotest.py -e testcase/${branch}/${feature}/${testConfig} -g testcase/${branch}/${feature}/${testGroup} -d

        //               docker compose -f /home/fosqa/testcase/${branch}/${feature}/docker/${docker_compose_file} down
        //             """
        //         }
        //     }
        // }
        

        stage('Archive Results') {
            steps {
                script {
                    def outputsDir = "/home/fosqa/${params.LOCAL_LIB_DIR}/outputs"
                    def latestDateFolder = sh(returnStdout: true, script: "ls -td ${outputsDir}/*/ | head -1").trim()
                    echo "Latest date folder: ${latestDateFolder}"
                    def latestSubFolder = sh(returnStdout: true, script: "ls -td ${latestDateFolder}*/ | head -1").trim()
                    echo "Latest results folder: ${latestSubFolder}"
                    
                    sh "cp -r ${latestSubFolder} ${WORKSPACE}/test_results"
                    sh "cp ${latestSubFolder}summary/summary.html ${WORKSPACE}/summary.html"
                    
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
    
    post {
        always {
            echo "Pipeline completed. Check console output for details."
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
