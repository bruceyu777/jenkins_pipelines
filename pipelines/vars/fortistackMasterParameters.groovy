import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript

def call() {
  properties([
    parameters([
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
      string(
        name: 'NODE_NAME',
        defaultValue: 'node1',
        description: 'Enter the node name: node1, node2 ...'
      ),
      // Feature selection.
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
      ],
      // Dynamic parameter for DOCKER_COMPOSE_FILE_CHOICE.
      [$class: 'CascadeChoiceParameter',
        name: 'DOCKER_COMPOSE_FILE_CHOICE',
        description: 'Select docker compose file based on feature',
        referencedParameters: 'FEATURE_NAME',
        choiceType: 'PT_SINGLE_SELECT',
        script: [
          $class: 'GroovyScript',
          script: new SecureGroovyScript(
            '''if (FEATURE_NAME == "avfortisandbox") {
                 return ["docker.avfortisandbox_avfortisandbox.yml", "other"]
               } else if (FEATURE_NAME == "webfilter") {
                 return ["docker.webfilter_basic.yml", "other"]
               } else {
                 return ["unknown"]
               }''',
            true
          )
        ]
      ],
      // Toggle parameter to skip Provision Pipeline stage.
      booleanParam(
        name: 'SKIP_PROVISION',
        defaultValue: false,
        description: 'Set to true to skip the Provision Pipeline stage'
      ),
      // Toggle parameter to skip Test Pipeline stage.
      booleanParam(
        name: 'SKIP_TEST',
        defaultValue: false,
        description: 'Set to true to skip the Test Pipeline stage'
      )
    ])
  ])
}
