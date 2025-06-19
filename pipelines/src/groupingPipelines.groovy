pipeline {
    // Run the master pipeline on the master node to avoid deadlock.
    agent { label 'master' }

    parameters {
        // Standalone BUILD_NUMBER parameter.
        string(
            name: 'RELEASE',
            defaultValue: '7.6.4',  // Default release number.
            description: 'Enter the release number, with 3 digits, like 7.6.4, or 8.0.0'
        )
        string(
            name: 'BUILD_NUMBER',
            defaultValue: '3563',
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
    "NODE_NAME": "node9",
    "FEATURE_NAME": "avfortisandbox",
    "TEST_CASE_FOLDER": "testcase",
    "TEST_CONFIG_CHOICE": "env.newman.FGT_KVM.avfortisandbox.conf",
    "TEST_GROUP_CHOICE": "grp.avfortisandbox_fortistack.full",
    "TEST_GROUPS": "[\\"grp.avfortisandbox_fortistack.full\\", \\"grp.avfortisandbox_alt.full\\"]",
    "DOCKER_COMPOSE_FILE_CHOICE": "docker.avfortisandbox_avfortisandbox.yml",
    "SEND_TO": "yzhengfeng@fortinet.com;wangd@fortinet.com;vlysak@fortinet.com"
  },
  {
    "NODE_NAME": "node10",
    "FEATURE_NAME": "webfilter",
    "TEST_CASE_FOLDER": "testcase_v1",
    "TEST_CONFIG_CHOICE": "env.FGTVM64.webfilter_demo.conf",
    "TEST_GROUP_CHOICE": "grp.webfilter_basic.full",
    "TEST_GROUPS": "grp.webfilter_basic.full, grp.webfilter_basic2.full",
    "DOCKER_COMPOSE_FILE_CHOICE": "docker.webfilter_basic.yml",
    "SEND_TO": "yzhengfeng@fortinet.com;wangd@fortinet.com;hchuanjian@fortinet.com"
  }
]''',
            description: '''Individual configuration parameters as a JSON array.
Both TEST_GROUP_CHOICE (legacy, single test group) and TEST_GROUPS (a JSON array of test suites or a comma separated list) are supported.
Downstream will use TEST_GROUPS if defined and nonempty; otherwise it will fall back to TEST_GROUP_CHOICE.'''
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
                        merged.RELEASE = params.RELEASE

                        // Process TEST_GROUPS field:
                        // if (merged.TEST_GROUPS?.trim()) {
                        //     def tg = merged.TEST_GROUPS.trim()
                        //     def validTestGroups
                        //     if (tg.startsWith('[')) {
                        //         // If it starts with '[' try to parse it as JSON.
                        //         try {
                        //             def parsed = readJSON text: tg
                        //             validTestGroups = groovy.json.JsonOutput.toJson(parsed)
                        //         } catch (Exception e) {
                        //             echo "TEST_GROUPS value not valid JSON, splitting by comma: ${e}"
                        //             def arr = tg.split(',').collect { it.trim() }
                        //             validTestGroups = groovy.json.JsonOutput.toJson(arr)
                        //         }
                        //     } else {
                        //         // Otherwise, assume a comma separated list.
                        //         def arr = tg.split(',').collect { it.trim() }
                        //         validTestGroups = groovy.json.JsonOutput.toJson(arr)
                        //     }
                        //     merged.TEST_GROUPS = validTestGroups
                        // } else {
                        //     merged.TEST_GROUPS = ""
                        // }
                        // Normalize TEST_GROUPS into a single JSON‐string array or empty string
                        def rawTG = merged.TEST_GROUPS
                        def validTestGroups = ""

                        // 1) If it’s already a List (from unescaped JSON), just JSON-encode it:
                        if (rawTG instanceof List) {
                            validTestGroups = groovy.json.JsonOutput.toJson(rawTG)
                        }
                        // 2) Otherwise if it’s a String, handle escaped JSON or comma-delimited:
                        else if (rawTG instanceof String && rawTG.trim()) {
                            def tg = rawTG.trim()
                            if (tg.startsWith("[")) {
                                try {
                                    // parse "[\"a\",\"b\"]" into a List
                                    def parsed = readJSON text: tg
                                    validTestGroups = groovy.json.JsonOutput.toJson(parsed)
                                } catch (Exception e) {
                                    echo "TEST_GROUPS not valid JSON, splitting by comma: ${e}"
                                    def arr = tg.split(",").collect { it.trim() }
                                    validTestGroups = groovy.json.JsonOutput.toJson(arr)
                                }
                            } else {
                                // plain comma-separated list “a, b, c”
                                def arr = tg.split(",").collect { it.trim() }
                                validTestGroups = groovy.json.JsonOutput.toJson(arr)
                            }
                        }

                        // 3) If neither, leave it empty
                        merged.TEST_GROUPS = validTestGroups


                        def branchName = "Run_${i}"
                        parallelBuilds[branchName] = {
                            echo "Triggering fortistack_master_provision_runtest with merged configuration: ${merged}"
                            build job: 'fortistack_master_provision_runtest', parameters: [
                                string(name: 'PARAMS_JSON', value: groovy.json.JsonOutput.toJson(merged.PARAMS_JSON)),
                                string(name: 'RELEASE', value: merged.RELEASE),
                                string(name: 'BUILD_NUMBER', value: merged.BUILD_NUMBER),
                                string(name: 'NODE_NAME', value: merged.NODE_NAME),
                                booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', value: merged.FORCE_UPDATE_DOCKER_FILE),
                                string(name: 'FEATURE_NAME', value: merged.FEATURE_NAME),
                                string(name: 'TEST_CASE_FOLDER', value: merged.TEST_CASE_FOLDER),
                                string(name: 'TEST_CONFIG_CHOICE', value: merged.TEST_CONFIG_CHOICE),
                                string(name: 'TEST_GROUP_CHOICE', value: merged.TEST_GROUP_CHOICE),
                                string(name: 'TEST_GROUPS', value: merged.TEST_GROUPS),
                                string(name: 'DOCKER_COMPOSE_FILE_CHOICE', value: merged.DOCKER_COMPOSE_FILE_CHOICE),
                                string(name: 'SEND_TO', value: merged.SEND_TO),
                                booleanParam(name: 'SKIP_PROVISION', value: merged.SKIP_PROVISION),
                                booleanParam(name: 'SKIP_TEST', value: merged.SKIP_TEST),
                                booleanParam(name: 'PROVISION_VMPC',   value: merged.PROVISION_VMPC),
                                string(      name: 'VMPC_NAMES',       value: merged.VMPC_NAMES),
                                booleanParam(name: 'PROVISION_DOCKER', value: merged.PROVISION_DOCKER)
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
