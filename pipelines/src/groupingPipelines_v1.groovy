pipeline {
    agent any

    parameters {
        // Multi-line text parameter for editing JSON.
        text(
            name: 'MULTI_CONFIGS',
            defaultValue: '''{
  "common": {
    "PARAMS_JSON": "{\\"build_name\\": \\"fortistack-\\", \\"send_to\\": \\"yzhengfeng@fortinet.com\\", \\"FGT_TYPE\\": \\"ALL\\", \\"LOCAL_LIB_DIR\\": \\"autolibv3\\", \\"SVN_BRANCH\\": \\"trunk\\"}",
    "BUILD_NUMBER": "3473",
    "FORCE_UPDATE_DOCKER_FILE": true,
    "SKIP_PROVISION": false,
    "SKIP_TEST": false
  },
  "configs": [
    {
      "NODE_NAME": "node1",
      "FEATURE_NAME": "avfortisandbox",
      "TEST_CASE_FOLDER": "testcase",
      "TEST_CONFIG_CHOICE": "env.newman.FGT_KVM.avfortisandbox.conf",
      "TEST_GROUP_CHOICE": "grp.avfortisandbox_fortistack.full",
      "DOCKER_COMPOSE_FILE_CHOICE": "docker.avfortisandbox_avfortisandbox.yml"
    },
    {
      "NODE_NAME": "node2",
      "FEATURE_NAME": "webfilter",
      "TEST_CASE_FOLDER": "testcase_v1",
      "TEST_CONFIG_CHOICE": "env.FGTVM64.webfilter_demo.conf",
      "TEST_GROUP_CHOICE": "grp.webfilter_basic.full",
      "DOCKER_COMPOSE_FILE_CHOICE": "docker.webfilter_basic.yml"
    }
  ]
}''',
            description: 'Multi-line JSON input. You may supply a "common" section and a "configs" array, or simply a JSON array of configurations.'
        )
    }

    stages {
        stage('Trigger fortistackMaster Jobs in Parallel') {
            steps {
                script {
                    // Parse the JSON from the parameter.
                    def inputJson = readJSON text: params.MULTI_CONFIGS
                    def common = [:]
                    def configs = []

                    // If the JSON contains "common" and "configs" keys, merge the common part.
                    if (inputJson.containsKey("common") && inputJson.containsKey("configs")) {
                        common = inputJson.common
                        configs = inputJson.configs
                    } else if (inputJson instanceof List) {
                        configs = inputJson
                    } else {
                        error "Invalid JSON format. Please supply either a list of configurations or an object with 'common' and 'configs' keys."
                    }

                    def parallelBuilds = [:]

                    // For each individual configuration, merge common parameters with individual overrides.
                    configs.eachWithIndex { individual, i ->
                        // The plus operator merges maps where keys in 'individual' override those in 'common'.
                        def merged = common + individual
                        def branchName = "Run_${i}"
                        parallelBuilds[branchName] = {
                            echo "Triggering fortistack_master_provision_runtest with merged configuration: ${merged}"
                            build job: 'fortistack_master_provision_runtest', parameters: [
                                string(name: 'PARAMS_JSON', value: merged.PARAMS_JSON),
                                string(name: 'BUILD_NUMBER', value: merged.BUILD_NUMBER),
                                string(name: 'NODE_NAME', value: merged.NODE_NAME),
                                booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', value: merged.FORCE_UPDATE_DOCKER_FILE),
                                string(name: 'FEATURE_NAME', value: merged.FEATURE_NAME),
                                string(name: 'TEST_CASE_FOLDER', value: merged.TEST_CASE_FOLDER),
                                string(name: 'TEST_CONFIG_CHOICE', value: merged.TEST_CONFIG_CHOICE),
                                string(name: 'TEST_GROUP_CHOICE', value: merged.TEST_GROUP_CHOICE),
                                string(name: 'DOCKER_COMPOSE_FILE_CHOICE', value: merged.DOCKER_COMPOSE_FILE_CHOICE),
                                booleanParam(name: 'SKIP_PROVISION', value: merged.SKIP_PROVISION),
                                booleanParam(name: 'SKIP_TEST', value: merged.SKIP_TEST)
                            ], wait: true
                        }
                    }

                    // Execute all fortistackMaster jobs in parallel.
                    parallel parallelBuilds
                }
            }
        }
    }

    post {
        always {
            echo "Upstream pipeline completed."
        }
    }
}
