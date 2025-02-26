// parameters.groovy
// This file defines all the parameters for the master pipeline.
// Lessons Learned:
// 1. Defining all parameters in one place makes them easier to manage and re-use.
// 2. When using the properties block in a Jenkinsfile, it overrides any UI-configured parameters.
// 3. Dynamic parameters (CascadeChoiceParameter) can be set up using SecureGroovyScript for better type safety.
// 4. Using a list for choices improves readability.

import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript

return [
  parameters: [
    // Static JSON parameter for common settings.
    string(
      name: 'PARAMS_JSON',
      defaultValue: '''{
  "build_name": "fortistack-",
  "send_to": "yzhengfeng@fortinet.com",
  "FGT_TYPE": "ALL",
  "LOCAL_LIB_DIR": "autolibv3",
  "SVN_BRANCH": "trunk"
}''',
      description: 'Centralized JSON parameters for both pipelines'
    ),
    // Manually entered build number.
    string(
      name: 'BUILD_NUMBER',
      defaultValue: '3473',
      description: 'Enter the build number'
    ),
    // Node name parameter.
    string(
      name: 'NODE_NAME',
      defaultValue: 'node1',
      description: 'Enter the node name (e.g., node1, node2, …)'
    ),
    // Feature selection using a list for readability.
    choice(
      name: 'FEATURE_NAME',
      choices: ["avfortisandbox", "webfilter"].join("\n"),
      description: 'Select the feature'
    ),
    // Dynamic parameter for TEST_CONFIG_CHOICE.
    [$class: 'CascadeChoiceParameter',
      name: 'TEST_CONFIG_CHOICE',
      description: 'Select test config based on feature',
      referencedParameters: 'FEATURE_NAME',
      choiceType: 'PT_SINGLE_SELECT',
      script: [
        $class: 'GroovyScript',
        script: new SecureGroovyScript(
          '''if (FEATURE_NAME == "avfortisandbox") {
               return ["env.newman.FGT_KVM.avfortisandbox.conf", "env.newman.FGT_KVM.alt.conf"]
             } else if (FEATURE_NAME == "webfilter") {
               return ["env.FGTVM64.webfilter_demo.conf", "env.FGTVM64.alt.conf"]
             } else {
               return ["unknown"]
             }''',
          true
        )
      ]
    ],
    // Dynamic parameter for TEST_GROUP_CHOICE.
    [$class: 'CascadeChoiceParameter',
      name: 'TEST_GROUP_CHOICE',
      description: 'Select test group based on feature',
      referencedParameters: 'FEATURE_NAME',
      choiceType: 'PT_SINGLE_SELECT',
      script: [
        $class: 'GroovyScript',
        script: new SecureGroovyScript(
          '''if (FEATURE_NAME == "avfortisandbox") {
               return ["grp.avfortisandbox_fortistack.full", "grp.avfortisandbox_alt.full"]
             } else if (FEATURE_NAME == "webfilter") {
               return ["grp.webfilter_basic.full", "grp.webfilter_alt.full"]
             } else {
               return ["unknown"]
             }''',
          true
        )
      ]
    ]
  ]
]
