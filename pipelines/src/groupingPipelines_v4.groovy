pipeline {
    // Run the master pipeline on the master node to avoid deadlock.
    agent { label 'master' }

    parameters {
        // Standalone BUILD_NUMBER parameter.
        string(
            name: 'BUILD_NUMBER',
            defaultValue: '3473',
            description: 'Enter the build number'
        )
        // Multi-line text parameter for common configuration.
        text(
            name: 'COMMON_CONFIG',
            defaultValue: '''{
  "PARAMS_JSON": {
    "build_name": "fortistack-",
    "send_to": "yzhengfeng@fortinet.com",
    "FGT_TYPE": "ALL",
    "LOCAL_LIB_DIR": "autolibv3",
    "SVN_BRANCH": "trunk"
  },
  "FORCE_UPDATE_DOCKER_FILE": true,
  "SKIP_PROVISION": false,
  "SKIP_TEST": false
}''',
            description: 'Common configuration parameters as JSON (PARAMS_JSON is now a JSON object)'
        )
        // Multi-line text parameter for individual configuration overrides.
        text(
            name: 'INDIVIDUAL_CONFIGS',
            defaultValue: '''[
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
]''',
            description: 'Individual configuration parameters as a JSON array'
        )
    }

    stages {
        stage('Trigger fortistack_master_provision_runtest Jobs in Parallel') {
            steps {
                script {
                    // Parse the common and individual configuration JSON.
                    def common = readJSON text: params.COMMON_CONFIG
                    def individualConfigs = readJSON text: params.INDIVIDUAL_CONFIGS

                    def parallelBuilds = [:]

                    // Iterate over each individual configuration and merge with common settings.
                    individualConfigs.eachWithIndex { individual, i ->
                        def merged = common + individual
                        // Override BUILD_NUMBER with the standalone parameter.
                        merged.BUILD_NUMBER = params.BUILD_NUMBER
                        def branchName = "Run_${i}"
                        parallelBuilds[branchName] = {
                            echo "Triggering fortistack_master_provision_runtest with merged configuration: ${merged}"
                            build job: 'fortistack_master_provision_runtest', parameters: [
                                // Convert the JSON object to a string using the fully qualified name.
                                string(name: 'PARAMS_JSON', value: groovy.json.JsonOutput.toJson(merged.PARAMS_JSON)),
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

                    // Execute all jobs in parallel.
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
