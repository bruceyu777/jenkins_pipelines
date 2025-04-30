import org.jenkinsci.plugins.scriptsecurity.sandbox.groovy.SecureGroovyScript

def call() {
  properties([
    parameters([
      // Static JSON parameter for common settings.
      string(
        name: 'PARAMS_JSON',
        defaultValue: '''{
  "build_name": "guitest-",
  "send_to": "yzhengfeng@fortinet.com",
  "FGT_TYPE": "ALL",
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
      // Feature selection, decide which test folder to use
      choice(
        name: 'FEATURE_NAME',
        choices: ["test_gui_application", "test_gui_infrastructure", "test_gui_vm_others", "test_label_check", "test_gui_wifi"].join("\n"),
        description: 'Select the feature'
      ),

      choice(
        name: 'STACK_NAME',
        choices: ["fgtA", "fgtB", "fgtC", "fgtD"].join("\n"),
        description: 'Select the stack config file, which is a yml file'
      ),

      string(
        name: 'KEY_WORD',
        defaultValue: 'test_',
        description: 'Enter keyword for pytest to select test cases'
      ),
      
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
