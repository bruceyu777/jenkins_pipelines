println "fortistackMasterParameters loaded successfully 1!"
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript
// This function returns the parameter definitions for the master pipeline.
println "fortistackMasterParameters loaded successfully 2 !"
def call() {
    return [
      parameters: [
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
        string(
          name: 'BUILD_NUMBER',
          defaultValue: '3473',
          description: 'Enter the build number'
        ),
        string(
          name: 'NODE_NAME',
          defaultValue: 'node1',
          description: 'Enter the node name (e.g., node1, node2, …)'
        ),
        choice(
          name: 'FEATURE_NAME',
          choices: ["avfortisandbox", "webfilter"].join("\n"),
          description: 'Select the feature'
        ),
        [$class: 'CascadeChoiceParameter',
          name: 'TEST_CONFIG_CHOICE',
          description: 'Select test config based on feature',
          referencedParameters: 'FEATURE_NAME',
          choiceType: 'PT_SINGLE_SELECT',
          script: [
            $class: 'GroovyScript',
            script: new org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript(
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
        [$class: 'CascadeChoiceParameter',
          name: 'TEST_GROUP_CHOICE',
          description: 'Select test group based on feature',
          referencedParameters: 'FEATURE_NAME',
          choiceType: 'PT_SINGLE_SELECT',
          script: [
            $class: 'GroovyScript',
            script: new org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript(
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
}

