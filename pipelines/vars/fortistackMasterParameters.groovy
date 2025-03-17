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
      booleanParam(
        name: 'FORCE_UPDATE_DOCKER_FILE',
        defaultValue: true,
        description: 'If true, update docker file with --force option'
      ),
      // Feature selection.
      choice(
        name: 'FEATURE_NAME',
        choices: ["avfortisandbox", "webfilter"].join("\n"),
        description: 'Select the feature'
      ),
      // Dynamic parameter for svn test case folder, testcase or testcase_v1.
      [$class: 'CascadeChoiceParameter',
        name: 'TEST_CASE_FOLDER',
        description: 'Select test case folder based on feature',
        referencedParameters: 'FEATURE_NAME',
        choiceType: 'PT_SINGLE_SELECT',
        script: [
          $class: 'GroovyScript',
          script: new SecureGroovyScript(
            '''if (FEATURE_NAME == "avfortisandbox") {
                 return ["testcase", "testcase_v1"]
               } else if (FEATURE_NAME == "webfilter") {
                 return ["testcase_v1", "testcase"]
               } else {
                 return ["unknown"]
               }''',
            true
          )
        ]
      ],
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
      string(
        name: 'TEST_GROUP_FILTER',
        defaultValue: '',
        description: 'Enter text to filter test group options'
      ),
      [$class: 'CascadeChoiceParameter',
        name: 'TEST_GROUP_CHOICE',
        description: 'Select one or more test groups based on feature (with filter and "All" option)',
        referencedParameters: 'FEATURE_NAME,TEST_GROUP_FILTER',
        choiceType: 'PT_MULTI_SELECT',
        script: [
          $class: 'GroovyScript',
          script: new SecureGroovyScript(
            '''
              def groups = []
              if (FEATURE_NAME == "avfortisandbox") {
                  groups = ["grp.avfortisandbox_fortistack.full", "grp.avfortisandbox_alt.full"]
              } else if (FEATURE_NAME == "webfilter") {
                  groups = ["grp.webfilter_basic.full", "grp.webfilter_basic2.full", "grp.webfilter_ha.full", "grp.webfilter_flow.full", "grp.webfilter_peruser.full", "grp.webfilter_onearm.full", "grp.webfilter_other.full", "grp.webfilter_other2.full"]
              } else {
                  groups = ["unknown"]
              }
              // Always add "All" at the beginning.
              groups.add(0, "All")
              
              def filter = TEST_GROUP_FILTER?.trim()
              if (filter) {
                  groups = groups.findAll { it == "All" || it.toLowerCase().contains(filter.toLowerCase()) }
                  // Ensure "All" is present if it doesn't match the filter.
                  if (!groups.contains("All")) {
                      groups.add(0, "All")
                  }
              }
              return groups
            ''',
            true
          )
        ]
      ],

      // New parameter: TEST_GROUPS.
      text(
        name: 'TEST_GROUPS',
        defaultValue: '',
        description: 'JSON array of test groups; if provided, overrides TEST_GROUP_CHOICE. Leave empty to use TEST_GROUP_CHOICE.'
      ),
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
