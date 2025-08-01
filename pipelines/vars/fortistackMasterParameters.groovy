import groovy.json.JsonSlurper
import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript

def call(Map config = [:]) {
    // Parameters to exclude
    List<String> excludes = config.get('exclude', []) as List<String>

    // Load features JSON
    def configPath = '/var/jenkins_home/feature-configs/fortistack/features.json'
    def raw        = new File(configPath).getText('UTF-8')
    def cfg        = new JsonSlurper().parseText(raw)
    def features   = cfg.keySet().sort() as List<String>

    // Helper to build a cascade parameter definition
    def cascadeDef = { name, desc, refs, type, body ->
        return [
            $class: 'CascadeChoiceParameter',
            name: name,
            description: desc,
            referencedParameters: refs,
            choiceType: type,
            script: [
                $class: 'GroovyScript',
                script: new SecureGroovyScript(
                    """
                    import groovy.json.JsonSlurper
                    def all = new JsonSlurper()
                        .parseText(new File('${configPath}').getText('UTF-8'))
                    ${body}
                    """, true
                )
            ]
        ]
    }

    // Build list of (key, definition) pairs
    def allParams = []
    allParams << [ key: 'PARAMS_JSON', defn: string(
        name: 'PARAMS_JSON',
        defaultValue: '''{
  "build_name": "fortistack-",
  "send_to": "yzhengfeng@fortinet.com",
  "LOCAL_LIB_DIR": "autolibv3"
}''',
        description: 'Centralized JSON parameters for both pipelines'
    ) ]
    allParams << [ key: 'AUTOLIB_BRANCH', defn:  string(name: 'AUTOLIB_BRANCH', defaultValue: 'main', description: 'Which branch of the autolib_v3 repo to checkout before running tests')]
    allParams << [ key: 'SVN_BRANCH',  defn: string(name: 'SVN_BRANCH',  defaultValue: 'v760', description: 'Enter svn branch for pulling test cases from SVN') ]
    allParams << [ key: 'FGT_TYPE',     defn: string(name: 'FGT_TYPE',     defaultValue: 'ALL',   description: 'Enter the FGT types: ALL, FGTA, FGTB, etc.') ]
    allParams << [ key: 'RELEASE',      defn: string(name: 'RELEASE',      defaultValue: '7.6.4',     description: 'Enter the release number (e.g. 7.6.4)') ]
    allParams << [ key: 'BUILD_NUMBER', defn: string(name: 'BUILD_NUMBER', defaultValue: '3587',  description: 'Enter the build number') ]
    allParams << [ key: 'NODE_NAME',    defn: string(name: 'NODE_NAME',    defaultValue: 'node1', description: 'Enter the node name: node1, node2 ...') ]
    allParams << [ key: 'FORCE_UPDATE_DOCKER_FILE', defn: booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', defaultValue: true, description: 'Update docker file with --force option') ]
    allParams << [ key: 'TERMINATE_PREVIOUS', defn: string(name: 'TERMINATE_PREVIOUS', defaultValue: 'false', description: 'wait_until_aio_pipeline_not_running.py --terminate <TERMINATE_PREVIOUS>, true for terminiate') ]
    allParams << [ key: 'PROVISION_VMPC', defn: booleanParam(name: 'PROVISION_VMPC', defaultValue: false, description: 'Enable provisioning of KVM-PC VMs') ]
    allParams << [ key: 'VMPC_NAMES',     defn: string(name: 'VMPC_NAMES', defaultValue: 'VMPC1,VMPC4', description: 'Comma-separated list of VMPC names') ]
    allParams << [ key: 'PROVISION_DOCKER', defn: booleanParam(name: 'PROVISION_DOCKER', defaultValue: true, description: 'Enable or disable Docker provisioning') ]

    allParams << [ key: 'FEATURE_NAME', defn: choice(
        name: 'FEATURE_NAME',
        choices: features.join('\n'),
        description: 'Select the feature'
    ) ]

    allParams << [ key: 'TEST_CASE_FOLDER', defn: cascadeDef(
        'TEST_CASE_FOLDER',
        'Select test case folder based on feature',
        'FEATURE_NAME',
        'PT_SINGLE_SELECT',
        'return all[FEATURE_NAME]?.test_case_folder ?: ["unknown"]'
    ) ]

    allParams << [ key: 'TEST_CONFIG_CHOICE', defn: cascadeDef(
        'TEST_CONFIG_CHOICE',
        'Select test config based on feature',
        'FEATURE_NAME',
        'PT_SINGLE_SELECT',
        'return all[FEATURE_NAME]?.test_config ?: ["unknown"]'
    ) ]

    allParams << [ key: 'TEST_GROUP_FILTER', defn: string(
        name: 'TEST_GROUP_FILTER',
        defaultValue: '',
        description: 'Enter text to filter test-group options'
    ) ]

    allParams << [ key: 'TEST_GROUP_CHOICE', defn: cascadeDef(
        'TEST_GROUP_CHOICE',
        'Select one or more test groups based on feature (with filter)',
        'FEATURE_NAME,TEST_GROUP_FILTER',
        'PT_MULTI_SELECT',
        '''
        def groups = all[FEATURE_NAME]?.test_groups ?: []
        if (TEST_GROUP_FILTER) {
          groups = groups.findAll { it.toLowerCase().contains(TEST_GROUP_FILTER.toLowerCase()) }
        }
        return groups
        '''
    ) ]

    allParams << [ key: 'DOCKER_COMPOSE_FILE_CHOICE', defn: cascadeDef(
        'DOCKER_COMPOSE_FILE_CHOICE',
        'Select docker compose file based on feature',
        'FEATURE_NAME',
        'PT_SINGLE_SELECT',
        'return all[FEATURE_NAME]?.docker_compose ?: ["unknown"]'
    ) ]

    allParams << [ key: 'SKIP_PROVISION', defn: booleanParam(name: 'SKIP_PROVISION', defaultValue: false, description: 'Skip the Provision stage') ]
    allParams << [ key: 'SKIP_PROVISION_TEST_ENV', defn: booleanParam(name: 'SKIP_PROVISION_TEST_ENV', defaultValue: false, description: 'Skip the provisioning of test environment (Docker, VMPCs, etc)') ]
    allParams << [ key: 'SKIP_TEST',      defn: booleanParam(name: 'SKIP_TEST',      defaultValue: false, description: 'Skip the Test stage') ]
    allParams << [ key: 'ORIOLE_SUBMIT_FLAG', defn: choice(
        name: 'ORIOLE_SUBMIT_FLAG',
        choices: ['succeeded','all','none'].join('\n'),
        description: 'Only passed test case submissions or all/none'
    ) ]
    allParams << [ key: 'SEND_TO', defn: cascadeDef(
        'SEND_TO',
        'Email addresses to notify',
        'FEATURE_NAME',
        'PT_SINGLE_SELECT',
        'return all[FEATURE_NAME]?.email ?: ["yzhengfeng@fortinet.com"]'
    ) ]

    allParams << [ key: 'ADDITIONAL_EMAIL', defn: string(
        name: 'ADDITIONAL_EMAIL',
        defaultValue: '',
        description: 'Additional email addresses to notify (comma separated)'
    ) ]

    // allParams << [ key: 'SEND_TO', defn: string(name: 'SEND_TO', defaultValue: 'yzhengfeng@fortinet.com', description: 'Email addresses to notify') ]

        // Determine which parameters to show: support 'only' (include) or 'exclude': support 'only' (include) or 'exclude'
    List<String> includes = config.get('only', []) as List<String>

    def visible = allParams.findAll { entry ->
        if (includes) {
            return entry.key in includes
        } else {
            return !excludes.contains(entry.key)
        }
    }.collect { it.defn }

    // Apply to Jenkins
    properties([
        parameters(visible)
    ])
} // end call
