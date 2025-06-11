import groovy.json.JsonSlurper
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript

def call(Map config = [:]) {
    // Collect parameters to exclude (if any)
    List<String> excludes = config.get('exclude', []) as List<String>

    // Load feature definitions from disk
    def configPath = '/var/jenkins_home/feature-configs/fortistack/features.json'
    def raw        = new File(configPath).getText('UTF-8')
    def cfg        = new JsonSlurper().parseText(raw)
    def features   = cfg.keySet().sort() as List<String>

    // Build a complete list of ParameterDefinitions
    def allParams = []
    allParams << string(
        name: 'PARAMS_JSON',
        defaultValue: '''{
  "build_name": "fortistack-",
  "send_to": "yzhengfeng@fortinet.com",
  "LOCAL_LIB_DIR": "autolibv3"
}''',
        description: 'Centralized JSON parameters for both pipelines'
    )
    allParams << string(name: 'SVN_BRANCH',   defaultValue: 'trunk', description: 'Enter svn branch for pulling test cases from SVN')
    allParams << string(name: 'FGT_TYPE',      defaultValue: 'ALL',   description: 'Enter the FGT types: ALL, FGTA, FGTB, etc.')
    allParams << string(name: 'RELEASE',       defaultValue: '7',     description: 'Enter the release number (e.g. 7.6.4)')
    allParams << string(name: 'BUILD_NUMBER',  defaultValue: '3473',  description: 'Enter the build number')
    allParams << string(name: 'NODE_NAME',     defaultValue: 'node1', description: 'Enter the node name: node1, node2 ...')
    allParams << booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', defaultValue: true, description: 'Update docker file with --force option')

    // VMPC and Docker provisioning toggles
    allParams << booleanParam(name: 'PROVISION_VMPC', defaultValue: false, description: 'Enable provisioning of KVM-PC VMs')
    allParams << string(      name: 'VMPC_NAMES',    defaultValue: '',    description: 'Comma-separated list of VMPC names (e.g. "VMPC1,VMPC4")')
    allParams << booleanParam(name: 'PROVISION_DOCKER', defaultValue: true, description: 'Enable or disable Docker provisioning')

    // Feature selection
    allParams << choice(
        name: 'FEATURE_NAME',
        choices: features.join("\n"),
        description: 'Select the feature'
    )

    // Dynamic: TEST_CASE_FOLDER
    allParams << cascade(
        'TEST_CASE_FOLDER',
        'Select test case folder based on feature',
        'FEATURE_NAME',
        'PT_SINGLE_SELECT',
        'return cfg[FEATURE_NAME]?.test_case_folder ?: ["unknown"]'
    )

    // Dynamic: TEST_CONFIG_CHOICE
    allParams << cascade(
        'TEST_CONFIG_CHOICE',
        'Select test config based on feature',
        'FEATURE_NAME',
        'PT_SINGLE_SELECT',
        'return cfg[FEATURE_NAME]?.test_config ?: ["unknown"]'
    )

    // Filter
    allParams << string(
        name: 'TEST_GROUP_FILTER',
        defaultValue: '',
        description: 'Enter text to filter test-group options'
    )

    // Dynamic: TEST_GROUP_CHOICE
    allParams << cascade(
        'TEST_GROUP_CHOICE',
        'Select one or more test groups based on feature (with filter)',
        'FEATURE_NAME,TEST_GROUP_FILTER',
        'PT_MULTI_SELECT',
        '''
        def groups = cfg[FEATURE_NAME]?.test_groups ?: []
        if (TEST_GROUP_FILTER) {
          groups = groups.findAll { it.toLowerCase().contains(TEST_GROUP_FILTER.toLowerCase()) }
        }
        return groups
        '''
    )

    // Dynamic: DOCKER_COMPOSE_FILE_CHOICE
    allParams << cascade(
        'DOCKER_COMPOSE_FILE_CHOICE',
        'Select docker compose file based on feature',
        'FEATURE_NAME',
        'PT_SINGLE_SELECT',
        'return cfg[FEATURE_NAME]?.docker_compose ?: ["unknown"]'
    )

    // Skip toggles and submission flag
    allParams << booleanParam(name: 'SKIP_PROVISION', defaultValue: false, description: 'Skip the Provision stage')
    allParams << booleanParam(name: 'SKIP_TEST',      defaultValue: false, description: 'Skip the Test stage')
    allParams << choice(
        name: 'ORIOLE_SUBMIT_FLAG',
        choices: ['succeeded','all','none'].join("\n"),
        description: 'Only passed test case submissions or all/none'
    )
    allParams << string(name: 'SEND_TO', defaultValue: 'yzhengfeng@fortinet.com', description: 'Email addresses to notify')

    // Filter out excluded parameters
        // Filter out excluded parameters by property map lookup
        // Filter out excluded parameters by inspecting the toMap()
    def visible = allParams.findAll { pd ->
        def meta = pd.toMap()
        def pname = meta['name']
        return !excludes.contains(pname)
    }

    // Apply to the job
    properties([parameters: visible])([parameters: visible])([parameters: visible])([parameters: visible])
}

/**
 * Helper to define a cascade choice parameter.
 */
private def cascade(String name, String description, String referenced, String choiceType, String body) {
    return [
      $class: 'CascadeChoiceParameter',
      name: name,
      description: description,
      referencedParameters: referenced,
      choiceType: choiceType,
      script: [
        $class: 'GroovyScript',
        script: new SecureGroovyScript(
          """
            import groovy.json.JsonSlurper
            // reload config
            def cfg = new JsonSlurper().parseText(new File('/var/jenkins_home/feature-configs/fortistack/features.json').getText('UTF-8'))
            ${body}
          """, true
        )
      ]
    ]
}
