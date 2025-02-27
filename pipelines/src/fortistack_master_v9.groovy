// fortistack_master.groovy
// Master pipeline that triggers downstream jobs 'bring_up_node_kvm' and 'runtest'.
// Parameter definitions are maintained in an external file (fortistack_master_parameters.groovy)
// to keep the pipeline code clean and maintainable.
//
// Lessons Learned:
// 1. Centralizing parameter definitions in a separate file makes it easier to manage and update.
// 2. Converting the JSON from the shared parameters into a plain map prevents serialization issues.
// 3. Using descriptive comments throughout the code improves readability and maintainability.

// Load the shared parameter definitions from an external file.
// Make sure fortistack_master_parameters.groovy is in the same folder as this file.
@Library('sharedLib') _

// Load parameter definitions from the shared library function.
def parameterDefs = fortistackMasterParameters()
properties(parameterDefs)

pipeline {
  agent any

  stages {
    stage('Trigger Provision Pipeline') {
      steps {
        script {
          // Parse the static JSON parameter (from shared parameters) and convert it into a plain HashMap.
          def paramsMap = new groovy.json.JsonSlurper()
                           .parseText(params.PARAMS_JSON)
                           .collectEntries { k, v -> [k, v] }
          
          // Build parameters for the 'bring_up_node_kvm' job.
          def provisionParams = [
            string(name: 'NODE_NAME', value: params.NODE_NAME),
            string(name: 'BUILD_NUMBER', value: params.BUILD_NUMBER),
            string(name: 'FGT_TYPE', value: paramsMap.FGT_TYPE)
          ]
          echo "Triggering bring_up_node_kvm with parameters: ${provisionParams}"
          build job: 'bring_up_node_kvm', parameters: provisionParams, wait: true
        }
      }
    }
    
    stage('Trigger Test Pipeline') {
      steps {
        script {
          // Parse the static JSON parameter again for the test pipeline.
          def paramsMap = new groovy.json.JsonSlurper()
                           .parseText(params.PARAMS_JSON)
                           .collectEntries { k, v -> [k, v] }
          
          // Build parameters for the 'runtest' job.
          def testParams = [
            string(name: 'NODE_NAME', value: params.NODE_NAME),
            string(name: 'LOCAL_LIB_DIR', value: paramsMap.LOCAL_LIB_DIR),
            string(name: 'SVN_BRANCH', value: paramsMap.SVN_BRANCH),
            string(name: 'FEATURE_NAME', value: params.FEATURE_NAME),
            string(name: 'TEST_CONFIG_CHOICE', value: params.TEST_CONFIG_CHOICE),
            string(name: 'TEST_GROUP_CHOICE', value: params.TEST_GROUP_CHOICE),
            string(name: 'build_name', value: paramsMap.build_name),
            string(name: 'send_to', value: paramsMap.send_to)
          ]
          echo "Triggering runtest with parameters: ${testParams}"
          build job: 'runtest', parameters: testParams, wait: true
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
