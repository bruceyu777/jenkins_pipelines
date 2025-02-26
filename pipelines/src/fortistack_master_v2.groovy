// Define static parameters via the properties block.
// The following parameters appear on the Build With Parameters page:
// - PARAMS_JSON: Holds common static settings.
// - BUILD_NUMBER: Manually entered string.
// - FEATURE_NAME: Manually selected choice (e.g., "avfortisandbox" or "webfilter").
// - TEST_CONFIG_CHOICE & TEST_GROUP_CHOICE: Configured (via Active Choices Plugin or Job DSL)
//   so that the user picks an option based on FEATURE_NAME.
properties([
  parameters([
    // Static JSON parameter for common settings
    string(
      name: 'PARAMS_JSON', 
      defaultValue: '''{
  "NODE_NAME": "node1",
  "build_name": "fortistack-",
  "send_to": "yzhengfeng@fortinet.com",
  "FGT_TYPE": "ALL",
  "LOCAL_LIB_DIR": "autolibv3",
  "SVN_BRANCH": "trunk"
}''',
      description: 'Centralized JSON parameters for both pipelines'
    ),
    // Manually entered build number
    string(
      name: 'BUILD_NUMBER',
      defaultValue: '3473',
      description: 'Enter the build number'
    ),
    // Feature selection (manual choice)
    choice(
      name: 'FEATURE_NAME',
      choices: ['avfortisandbox', 'webfilter'],
      description: 'Select the feature'
    ),
    // Dynamic parameter: TEST_CONFIG_CHOICE is computed based on FEATURE_NAME.
    [$class: 'CascadeChoiceParameter',
       name: 'TEST_CONFIG_CHOICE',
       description: 'Select test config based on feature',
       referencedParameters: 'FEATURE_NAME',
       choiceType: 'PT_SINGLE_SELECT',
       script: [
         script: '''
           if (FEATURE_NAME == 'avfortisandbox') {
             return ["env.newman.FGT_KVM.avfortisandbox.conf", "env.newman.FGT_KVM.alt.conf"]
           } else if (FEATURE_NAME == 'webfilter') {
             return ["env.FGTVM64.webfilter_demo.conf", "env.FGTVM64.alt.conf"]
           } else {
             return ["unknown"]
           }
         ''',
         fallbackScript: 'return ["error"]'
       ]
    ],
    // Dynamic parameter: TEST_GROUP_CHOICE is computed based on FEATURE_NAME.
    [$class: 'CascadeChoiceParameter',
       name: 'TEST_GROUP_CHOICE',
       description: 'Select test group based on feature',
       referencedParameters: 'FEATURE_NAME',
       choiceType: 'PT_SINGLE_SELECT',
       script: [
         script: '''
           if (FEATURE_NAME == 'avfortisandbox') {
             return ["grp.avfortisandbox_fortistack.full", "grp.avfortisandbox_alt.full"]
           } else if (FEATURE_NAME == 'webfilter') {
             return ["grp.webfilter_basic.full", "grp.webfilter_alt.full"]
           } else {
             return ["unknown"]
           }
         ''',
         fallbackScript: 'return ["error"]'
       ]
    ]
  ])
])


pipeline {
  agent any

  stages {
    stage('Trigger Provision Pipeline') {
      steps {
        script {
          // Parse the static JSON and convert to a plain map
          def rawParams = new groovy.json.JsonSlurper().parseText(params.PARAMS_JSON)
          def paramsMap = rawParams.collectEntries { k, v -> [k, v] }
          
          // Build parameters for the provisioning job.
          def provisionParams = [
            string(name: 'NODE_NAME', value: paramsMap.NODE_NAME),
            string(name: 'BUILD_NUMBER', value: params.BUILD_NUMBER),  // Manually provided
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
          def rawParams = new groovy.json.JsonSlurper().parseText(params.PARAMS_JSON)
          def paramsMap = rawParams.collectEntries { k, v -> [k, v] }
          
          // Build parameters for the test job.
          def testParams = [
            string(name: 'NODE_NAME', value: paramsMap.NODE_NAME),
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
