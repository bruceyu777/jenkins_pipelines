// used by http://10.96.227.206:8080/job/fortistack_runtest_guitest/

// Helper function to parse PARAMS_JSON and expand its keys as global variables.
def expandParamsJson(String jsonStr) {
    try {
        def jsonMap = new groovy.json.JsonSlurper().parseText(jsonStr) as Map
        jsonMap.each { key, value ->
            // Set each key-value pair as a global variable.
            binding.setVariable(key, value)
            echo "Set variable: ${key} = ${value}"
        }
        return jsonMap
    } catch (Exception e) {
        error("Failed to parse PARAMS_JSON: ${e}")
    }
}

def computedTestGroups = []  // Global variable to share across stages

def call() {
  fortistackMasterParametersGuiTest()
  // Expand the JSON parameters so that keys (e.g. build_name, send_to, FGT_TYPE)
  // are available as global variables.
  expandParamsJson(params.PARAMS_JSON)

  pipeline {
    // Use NODE_NAME from pipeline parameters.
    agent { label "${params.NODE_NAME}" }
    
    parameters {
      string(
        name: 'STACK_NAME',
        defaultValue: 'fgtA',
        description: 'Which Docker stack to bring up (e.g. fgtA)'
      )
      string(
        name: 'KEY_WORD',
        defaultValue: 'test_',
        description: 'Test-name prefix to filter which tests to run'
      )
    }


    options {
      buildDiscarder(logRotator(numToKeepStr: '100'))
    }
    environment {
        TZ = 'America/Vancouver'
    }
    
    stages {
      
      stage('Set Build Display Name') {
        steps {
          script {
            currentBuild.displayName = "#${currentBuild.number} ${params.NODE_NAME}-${params.BUILD_NUMBER}-${FEATURE_NAME}"
          }
        }
      }
      
      stage('Check Docker and KVM domains') {
        steps {
          echo "Checking Docker environment..."
          sh 'docker ps'
          sh 'docker-compose --version'
          sh 'virsh -c qemu:///system list --all'
        }
      }
      
      stage('Test Running') {
        steps {
          script {
            withCredentials([usernamePassword(credentialsId: 'LDAP', usernameVariable: 'SVN_USER', passwordVariable: 'SVN_PASS')]) {
              // Setup docker network and route, make sure telnet available and bring up docker containers
              sh """
                  docker login harbor-robot.corp.fortinet.com -u \$SVN_USER -p \$SVN_PASS
              """
              // sh """
              //     docker login harbor-robot.corp.fortinet.com -u \$SVN_USER -p \$SVN_PASS
              //     docker ps -aq | xargs -r docker rm -f
              //     cd /home/fosqa/resources/tools/
              //     make setup_docker_network_and_cleanup_telnet_ports
              //     docker compose -f /home/fosqa/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/docker/${params.DOCKER_COMPOSE_FILE_CHOICE} up --build -d
              // """
              // Run tests for each computed test group.
              
              script {
                        // Generate session id based on current timestamp in the format "yyyyMMddHHmmss_SSS"
                        println("Generate Session ID ...")
                        def currentDateTime = new Date()
                        def sessionIdFormat = new java.text.SimpleDateFormat("yyyyMMddHHmmss_'${currentDateTime.getTime() % 1000}'")
                        env.SESSION_ID = sessionIdFormat.format(currentDateTime)
                        println("Generated Session ID: ${env.SESSION_ID}")
                    }
              
              sh """
                  cd /home/fosqa/git/guitest/${params.FEATURE_NAME}
                  sudo chmod -R 777 .
                  . /home/fosqa/git/guitest/venv_fosqa/bin/activate
                  make docker_test stack=${params.STACK_NAME} name=${params.KEY_WORD} session_id=${env.SESSION_ID} submit=true browser_type=firefox
              """
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
}
