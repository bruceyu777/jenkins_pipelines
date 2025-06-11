import groovy.json.JsonSlurper
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript

def call() {
  // 1) read the JSON from disk
  def configPath = '/var/jenkins_home/feature-configs/fortistack/features.json'
  def raw       = new File(configPath).getText('UTF-8')
  def cfg       = new JsonSlurper().parseText(raw)

  // 2) top-level feature list
  def features = cfg.keySet().sort() as List

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

      // ── VMPC provisioning toggles, moved from the downstream script ──
      booleanParam(
        name: 'PROVISION_VMPC',
        defaultValue: false,
        description: 'Enable provisioning of KVM-PC VMs via provision_pc_vm_working_local.py'
      ),
      string(
        name: 'VMPC_NAMES',
        defaultValue: '',
        description: 'Comma-separated list of VMPC names to provision (e.g. "VMPC1,VMPC4")'
      ),
      booleanParam(
        name: 'PROVISION_DOCKER',
        defaultValue: true,
        description: 'Enable or disable Docker provisioning (default: true)'
      ),

      // ——— FEATURE_NAME ———
      choice(
        name: 'FEATURE_NAME',
        choices: features,
        description: 'Select the feature'
      ),

      // Dynamic: TEST_CASE_FOLDER
      [$class: 'CascadeChoiceParameter',
        name: 'TEST_CASE_FOLDER',
        description: 'Select test case folder based on feature',
        referencedParameters: 'FEATURE_NAME',
        choiceType: 'PT_SINGLE_SELECT',
        script: [
          $class: 'GroovyScript',
          script: new SecureGroovyScript("""
            import groovy.json.JsonSlurper
            // read the same file at parameter-render time
            def raw = new File('$configPath').getText('UTF-8')
            def all = new JsonSlurper().parseText(raw)
            return all[FEATURE_NAME].test_case_folder
          """, true)
        ]
      ],

      // Dynamic: TEST_CONFIG_CHOICE
      [$class: 'CascadeChoiceParameter',
        name: 'TEST_CONFIG_CHOICE',
        description: 'Select test config based on feature',
        referencedParameters: 'FEATURE_NAME',
        choiceType: 'PT_SINGLE_SELECT',
        script: [
          $class: 'GroovyScript',
          script: new SecureGroovyScript("""
            import groovy.json.JsonSlurper
            def raw = new File('$configPath').getText('UTF-8')
            def all = new JsonSlurper().parseText(raw)
            return all[FEATURE_NAME].test_config
          """, true)
        ]
      ],

      // Dynamic: TEST_GROUP_CHOICE (with filter)
      string(
        name: 'TEST_GROUP_FILTER',
        defaultValue: '',
        description: 'Enter text to filter test-group options'
      ),
      [$class: 'CascadeChoiceParameter',
        name: 'TEST_GROUP_CHOICE',
        description: 'Select one or more test groups based on feature (with filter)',
        referencedParameters: 'FEATURE_NAME,TEST_GROUP_FILTER',
        choiceType: 'PT_MULTI_SELECT',
        script: [
          $class: 'GroovyScript',
          script: new SecureGroovyScript("""
            import groovy.json.JsonSlurper
            def raw = new File('$configPath').getText('UTF-8')
            def all = new JsonSlurper().parseText(raw)
            def groups = all[FEATURE_NAME].test_groups
            if (TEST_GROUP_FILTER) {
              groups = groups.findAll {
                it.toLowerCase().contains(TEST_GROUP_FILTER.toLowerCase())
              }
            }
            return groups
          """, true)
        ]
      ],

      // Dynamic: DOCKER_COMPOSE_FILE_CHOICE
      [$class: 'CascadeChoiceParameter',
        name: 'DOCKER_COMPOSE_FILE_CHOICE',
        description: 'Select docker compose file based on feature',
        referencedParameters: 'FEATURE_NAME',
        choiceType: 'PT_SINGLE_SELECT',
        script: [
          $class: 'GroovyScript',
          script: new SecureGroovyScript("""
            import groovy.json.JsonSlurper
            def raw = new File('$configPath').getText('UTF-8')
            def all = new JsonSlurper().parseText(raw)
            return all[FEATURE_NAME].docker_compose
          """, true)
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
