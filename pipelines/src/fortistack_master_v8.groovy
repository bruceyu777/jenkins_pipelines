// Jenkinsfile
// This master pipeline triggers two downstream jobs: 'bring_up_node_kvm' and 'runtest'.
// The parameter definitions are loaded from an external file (parameters.groovy) to improve maintainability.
// Lessons Learned:
// - Use an external file to manage parameter definitions so the Jenkinsfile remains clean.
// - Convert the JSON parsed by JsonSlurper into a plain map to avoid serialization issues.
// - Use descriptive comments to make the code self-explanatory.

def parameterDefs = load 'parameters.groovy'
properties(parameterDefs)

pipeline {
  agent any

  stages {
    stage('Trigger Provision Pipeline') {
      steps {
        script {
          // Parse the static JSON parameter and convert it to a plain HashMap.
          def paramsMap = new groovy.json.JsonSlurper().parseText(params.PARAMS_JSON)
                         .collectEntries { k, v -> [k, v] }
          
          // Build the parameters for the 'bring_up_node_kvm' job.
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
          // Parse the static JSON parameter again for test pipeline.
          def paramsMap = new groovy.json.JsonSlurper().parseText(params.PARAMS_JSON)
                         .collectEntries { k, v -> [k, v] }
          
          // Build the parameters for the 'runtest' job.
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
