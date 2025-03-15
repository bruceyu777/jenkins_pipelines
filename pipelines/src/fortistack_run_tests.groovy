// Helper function to get the archive group name (first two portions).
def getArchiveGroupName(String group) {
    def parts = group.tokenize('.')
    if (parts.size() >= 2) {
        return "${parts[0]}.${parts[1]}"
    } else {
        return group
    }
}

// Helper function to compute test groups based on parameters.
def getTestGroups(params) {
    def testGroups = []
    if (params.TEST_GROUPS) {
        if (params.TEST_GROUPS instanceof String) {
            def tg = params.TEST_GROUPS.trim()
            // Remove surrounding quotes if present.
            if (tg.startsWith("\"") && tg.endsWith("\"")) {
                tg = tg.substring(1, tg.length()-1).trim()
            }
            if (tg.startsWith("[")) {
                try {
                    def parsed = readJSON text: tg
                    if (parsed instanceof List) {
                        testGroups = parsed
                    } else {
                        testGroups = tg.split(",").collect { it.trim() }
                    }
                } catch (e) {
                    echo "Error parsing TEST_GROUPS as JSON: ${e}. Falling back to splitting by comma."
                    testGroups = tg.split(",").collect { it.trim() }
                }
            } else {
                testGroups = tg.split(",").collect { it.trim() }
            }
        } else if (params.TEST_GROUPS instanceof List) {
            testGroups = params.TEST_GROUPS
        } else {
            testGroups = [params.TEST_GROUPS.toString()]
        }
    }
    if (!testGroups || testGroups.isEmpty()) {
        testGroups = [params.TEST_GROUP_CHOICE]
    }
    return testGroups
}

def computedTestGroups = []  // Global variable to share across stages

def call() {
  fortistackMasterParameters()

  pipeline {
    agent { label "${params.NODE_NAME}" }
    options {
      buildDiscarder(logRotator(numToKeepStr: '100'))
    }
    
    stages {
      stage('Initialize Test Groups') {
        steps {
          script {
            computedTestGroups = getTestGroups(params)
            echo "Computed test groups: ${computedTestGroups}"
          }
        }
      }
      
      stage('Set Build Display Name') {
        steps {
          script {
            currentBuild.displayName = "#${currentBuild.number} ${params.NODE_NAME}-${params.BUILD_NUMBER}-${params.FEATURE_NAME}-${computedTestGroups.join(',')}"
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
      
      stage('Test Preparation') {
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

              // Update Docker file
              def forceArg = params.FORCE_UPDATE_DOCKER_FILE ? "--force" : ""
              sh """
                cd /home/fosqa/resources/tools
                sudo /home/fosqa/resources/tools/venv/bin/python get_dockerfile_from_cdn.py --feature ${params.FEATURE_NAME} ${forceArg}
              """

              // Prepare SVN code directory and update SVN repository
              def baseTestDir = "/home/fosqa/${params.LOCAL_LIB_DIR}/testcase/${params.SVN_BRANCH}"
              sh "mkdir -p ${baseTestDir}"
              def folderPath = "${baseTestDir}/${params.FEATURE_NAME}"
              echo "Checking folder: ${folderPath}"
              def folderExists = sh(script: "if [ -d '${folderPath}' ]; then echo exists; else echo notexists; fi", returnStdout: true).trim()
              echo "Folder check result: ${folderExists}"

              if (folderExists == "notexists") {
                def svnStatus = sh(script: "cd ${baseTestDir} && sudo svn checkout https://qa-svn.corp.fortinet.com/svn/qa/FOS/${params.TEST_CASE_FOLDER}/${params.SVN_BRANCH}/${params.FEATURE_NAME} --username \$SVN_USER --password \$SVN_PASS --non-interactive", returnStatus: true)
                if (svnStatus != 0) {
                  echo "SVN checkout failed with exit status ${svnStatus}. Continuing pipeline..."
                }
              } else {
                sh "cd ${folderPath} && sudo svn update --username \$SVN_USER --password \$SVN_PASS --non-interactive"
              }

              // Create Docker file soft link
              sh """
                cd /home/fosqa/testcase/${params.SVN_BRANCH}/${params.FEATURE_NAME}
                sudo rm -f docker_filesys
                sudo ln -s /home/fosqa/docker_filesys/${params.FEATURE_NAME} docker_filesys
              """

              // Login to Harbor
              sh "docker login harbor-robot.corp.fortinet.com -u \$SVN_USER -p \$SVN_PASS"

              // Remove all existing Docker containers
              sh "docker ps -aq | xargs -r docker rm -f"
            }
          }
        }
      }
      
      stage('Test Running') {
        steps {
          script {
            withCredentials([usernamePassword(credentialsId: 'LDAP', usernameVariable: 'SVN_USER', passwordVariable: 'SVN_PASS')]) {
              sh """
                cd /home/fosqa/resources/tools && python3 set_docker_network.py
                sudo python3 set_route_for_docker.py 
                docker compose -f /home/fosqa/testcase/${params.SVN_BRANCH}/${params.FEATURE_NAME}/docker/${params.DOCKER_COMPOSE_FILE_CHOICE} up --build -d

                cd /home/fosqa/${params.LOCAL_LIB_DIR}
                sudo chmod -R 777 .
                . /home/fosqa/${params.LOCAL_LIB_DIR}/venv/bin/activate
                python3 autotest.py -e testcase/${params.SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_CONFIG_CHOICE} -g testcase/${params.SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_GROUP_CHOICE} -d
              """
              sh "cd /home/fosqa/resources/tools && sudo python3 simple_http_server_as_service.py"
            }
          }
        }
      }
    }
    
    post {
      always {
        script {
          // Archive Test Results in the post block.
          def outputsDir = "/home/fosqa/${params.LOCAL_LIB_DIR}/outputs"
          // Clean up previous archiving work.
          sh "rm -rf ${WORKSPACE}/test_results"
          sh "rm -f ${WORKSPACE}/summary_*.html"
          
          def archivedFolders = []
          // Iterate over computed test groups.
          for (group in computedTestGroups) {
            def archiveGroup = getArchiveGroupName(group)
            def folder = sh(
              returnStdout: true,
              script: """
                find ${outputsDir} -mindepth 2 -maxdepth 2 -type d -name "*--group--${archiveGroup}" -printf '%T@ %p\\n' | sort -nr | head -1 | cut -d' ' -f2-
              """
            ).trim()
            
            if (!folder) {
              echo "Warning: No test results folder found for test group '${archiveGroup}' in ${outputsDir}."
            } else {
              echo "Found folder for group '${archiveGroup}': ${folder}"
              archivedFolders << folder
              sh "mkdir -p ${WORKSPACE}/test_results/${archiveGroup}"
              sh "cp -r ${folder} ${WORKSPACE}/test_results/${archiveGroup}/"
              sh "cp ${folder}/summary/summary.html ${WORKSPACE}/summary_${archiveGroup}.html"
            }
          }
          
          if (archivedFolders.isEmpty()) {
            echo "No test results were found for any test group."
          } else {
            archiveArtifacts artifacts: "test_results/**, summary_*.html", fingerprint: false
            publishHTML(target: [
              reportDir: ".",
              reportFiles: "summary_${getArchiveGroupName(computedTestGroups[0])}.html",
              reportName: "Test Results Summary for ${getArchiveGroupName(computedTestGroups[0])}"
            ])
          }
        }
        echo "Pipeline completed. Check console output for details."
      }
    }
  }
}
