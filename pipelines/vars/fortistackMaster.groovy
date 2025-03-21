def call() {
  fortistackMasterParameters()

  pipeline {
    // Run the master pipeline on the master node to avoid deadlock.
    agent { label 'master' }

    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
    }

    stages {
      stage('Set Build Display Name') {
            steps {
                script {
                    currentBuild.displayName = "#${currentBuild.number} ${params.NODE_NAME}-${params.FEATURE_NAME}-${params.TEST_GROUP_CHOICE}"
                }
            }
        }
      
      stage('Debug Parameters') {
        steps {
          script {
            echo "=== Debug: Printing All Parameters ==="
            params.each { key, value ->
              echo "${key} = ${value}"
            }
          }
        }
      }
      
      stage('Trigger Provision Pipeline') {
        // This stage runs on the agent specified by NODE_NAME.
        agent { label "${params.NODE_NAME}" }
        when {
          expression { return !params.SKIP_PROVISION }
        }
        steps {
          script {
            // Parse the JSON parameter into a Map.
            def paramsMap = new groovy.json.JsonSlurper()
                             .parseText(params.PARAMS_JSON)
                             .collectEntries { k, v -> [k, v] }
            def provisionParams = [
              string(name: 'NODE_NAME', value: params.NODE_NAME),
              string(name: 'BUILD_NUMBER', value: params.BUILD_NUMBER),
              string(name: 'FGT_TYPE', value: paramsMap.FGT_TYPE)
            ]
            echo "Triggering fortistack_provision_fgts pipeline with parameters: ${provisionParams}"
            build job: 'fortistack_provision_fgts', parameters: provisionParams, wait: true
          }
        }
      }
      
      stage('Trigger Test Pipeline') {
        // This stage runs on the agent specified by NODE_NAME.
        agent { label "${params.NODE_NAME}" }
        when {
          expression { return !params.SKIP_TEST }
        }
        steps {
          script {
            def paramsMap = new groovy.json.JsonSlurper()
                             .parseText(params.PARAMS_JSON)
                             .collectEntries { k, v -> [k, v] }
            def testParams = [
              string(name: 'BIULD_NUMBER', value: params.BUILD_NUMBER),
              string(name: 'NODE_NAME', value: params.NODE_NAME),
              string(name: 'LOCAL_LIB_DIR', value: paramsMap.LOCAL_LIB_DIR),
              string(name: 'SVN_BRANCH', value: paramsMap.SVN_BRANCH),
              string(name: 'FEATURE_NAME', value: params.FEATURE_NAME),
              string(name: 'TEST_CASE_FOLDER', value: params.TEST_CASE_FOLDER),
              string(name: 'TEST_CONFIG_CHOICE', value: params.TEST_CONFIG_CHOICE),
              string(name: 'TEST_GROUP_CHOICE', value: params.TEST_GROUP_CHOICE),
              string(name: 'DOCKER_COMPOSE_FILE_CHOICE', value: params.DOCKER_COMPOSE_FILE_CHOICE),
              booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', value: params.FORCE_UPDATE_DOCKER_FILE),
              string(name: 'build_name', value: paramsMap.build_name),
              string(name: 'send_to', value: paramsMap.send_to)
            ]
            echo "Triggering fortistack_runtest pipeline with parameters: ${testParams}"
            build job: 'fortistack_runtest', parameters: testParams, wait: true
          }
        }
      }
    }

    post {
      always {
        echo "Master pipeline completed."
      }
    }
  }
}
