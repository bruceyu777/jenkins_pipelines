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
  "LOCAL_LIB_DIR": "autolibv3",
}''',
        description: 'Centralized JSON parameters for both pipelines'
      ),
      // Manually entered build number.
      string(
        name: 'SVN_BRANCH',
        defaultValue: 'trunk',
        description: 'Enter svn branch for pulling test cases from SVN, v760, trunk etc.'
      ),
      string(
        name: 'FGT_TYPE',
        defaultValue: 'ALL',
        description: 'Enter the FGT types: ALL, FGTA, FGTB, FGTC, FGTD, "FGTA,FGTB", "FGTA,FGTD"'
      ),
      string(
        name: 'RELEASE',
        defaultValue: '7',
        description: 'Enter the release number, with 3 digits, like 7.6.4, or 8.0.0'
      ),
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
        choices: ["avfortisandbox", "webfilter", "dnsfilter"].join("\n"),
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
            '''if (FEATURE_NAME in ["avfortisandbox","dnsfilter"]) {
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
                 return ["env.newman.FGT_KVM.avfortisandbox.conf"]
               } else if (FEATURE_NAME == "webfilter") {
                 return ["env.FGTVM64.webfilter_demo.conf"]
               } else if (FEATURE_NAME == "dnsfilter") {
                 return ["env.FGTVM64_dnsfilter_fortistack.conf"]
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
        description: 'Select one or more test groups based on feature (with filter)',
        referencedParameters: 'FEATURE_NAME,TEST_GROUP_FILTER',
        choiceType: 'PT_MULTI_SELECT',
        script: [
          $class: 'GroovyScript',
          script: new SecureGroovyScript(
            '''
              def groups = []
              if (FEATURE_NAME == "avfortisandbox") {
                  groups = ["grp.avfortisandbox_fortistack.full", "grp.avfortisandbox_fortistack.short"]
              } else if (FEATURE_NAME == "webfilter") {
                  groups = ["grp.webfilter_basic.full", "grp.webfilter_basic2.full", "grp.webfilter_ha.full", "grp.webfilter_flow.full", "grp.webfilter_peruser.full", "grp.webfilter_onearm.full", "grp.webfilter_other.full", "grp.webfilter_other2.full"]
              } else if (FEATURE_NAME == "dnsfilter") {
                  groups = ["grp.dnsfilter_fortistack.crit","grp.dnsfilter.crit", "grp.dnsfilter.full"]
              } else {
                  groups = ["unknown"]
              }
              
              def filter = TEST_GROUP_FILTER?.trim()
              if (filter) {
                  groups = groups.findAll { it.toLowerCase().contains(filter.toLowerCase()) }
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
               } else if (FEATURE_NAME == "dnsfilter") {
                 return ["docker.dnsfilter_dnsfilter.yml", "other"]
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
      ),
      // Oriole Submission option.
      choice(
        name: 'ORIOLE_SUBMIT_FLAG',
        choices: ["succeeded", "all","none"].join("\n"),
        description: 'Define Oriole submit rule, "succeeded" means only passed test case will be submitted. "all" means all result will be submitted.'
      ),

      // New: comma- or semicolon-separated list of email recipients
      string(
        name: 'SEND_TO',
        defaultValue: 'yzhengfeng@fortinet.com',
        description: 'Comma- or semicolon-separated list of email addresses to notify'
      ),

    ])
  ])
}
