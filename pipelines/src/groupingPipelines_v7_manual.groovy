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
            defaultValue: '3587',
            description: 'Enter the build number'
        )
        // ADDED: AUTOLIB_BRANCH parameter
        string(
            name: 'AUTOLIB_BRANCH',
            defaultValue: 'v3r10build0007',
            description: 'Which branch of the autolib_v3 repo to checkout before running tests (e.g., main, v3r10build0007)'
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
    "SVN_BRANCH": "v760"
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
    "NODE_NAME": "node10",
    "FEATURE_NAME": "threatfeed",
    "TEST_CASE_FOLDER": "testcase_v1",
    "TEST_CONFIG_CHOICE": "env.fortistack.threatfeed.conf",
    "TEST_GROUP_CHOICE": "grp.threatfeed.full",
    "TEST_GROUPS": [
      "grp.threatfeed.full"
    ],
    "SUM_DURATION": "1 hr 3 min",
    "DOCKER_COMPOSE_FILE_CHOICE": "KVM",
    "SEND_TO": "rainxiao@fortinet.com,wangd@fortinet.com,yzhengfeng@fortinet.com",
    "PROVISION_VMPC": true,
    "VMPC_NAMES": "VMPC1,VMPC4",
    "PROVISION_DOCKER": false,
    "ORIOLE_SUBMIT_FLAG": "all"
  },
  {
    "NODE_NAME": "node1",
    "FEATURE_NAME": "avenregres",
    "TEST_CASE_FOLDER": "testcase_v1",
    "TEST_CONFIG_CHOICE": "env.FGTVM64.avenregres_demo.conf",
    "TEST_GROUP_CHOICE": "grp.avenregres.full",
    "TEST_GROUPS": [
      "grp.avenregres.full"
    ],
    "SUM_DURATION": "1 hr",
    "DOCKER_COMPOSE_FILE_CHOICE": "docker.avenregres_avenregres.yml",
    "SEND_TO": "hchuanjian@fortinet.com,rainxiao@fortinet.com,wangd@fortinet.com,yzhengfeng@fortinet.com",
    "PROVISION_VMPC": false,
    "VMPC_NAMES": "",
    "PROVISION_DOCKER": true,
    "ORIOLE_SUBMIT_FLAG": "all"
  }
]''',
            description: '''Individual configuration parameters as a JSON array.
Both TEST_GROUP_CHOICE (legacy, single test group) and TEST_GROUPS (a JSON array of test suites or a comma separated list) are supported.
Downstream will use TEST_GROUPS if defined and nonempty; otherwise it will fall back to TEST_GROUP_CHOICE.'''
        )
    }

    stages {
        stage('Set Build Display Name') {
            steps {
            script {
                def displayName = "#${currentBuild.number}-r${params.RELEASE}-b${params.BUILD_NUMBER}"
                // Add AUTOLIB_BRANCH indicator if not using default
                if (params.AUTOLIB_BRANCH && params.AUTOLIB_BRANCH != 'main') {
                    displayName += "-${params.AUTOLIB_BRANCH}"
                }
                currentBuild.displayName = displayName
            }
            }
        }
        stage('Trigger fortistack_master_provision_runtest Jobs in Parallel') {
            steps {
                script {
                    // Parse the common and individual configuration JSON.
                    def common = readJSON text: params.COMMON_CONFIG
                    def individualConfigs = readJSON text: params.INDIVIDUAL_CONFIGS

                    def parallelBuilds = [:]

                    // Iterate over each individual configuration and merge with common settings.
                    individualConfigs.eachWithIndex { individual, i ->
                        // Merge configurations: individual configs override common configs
                        def merged = common + individual

                        // Debug: Show which parameters are being overridden
                        def overrides = []
                        individual.each { key, value ->
                            if (common.containsKey(key) && common[key] != value) {
                                overrides << "${key}: ${common[key]} -> ${value}"
                            }
                        }
                        if (overrides) {
                            echo "Config #${i} overrides: ${overrides.join(', ')}"
                        }

                        // Override with standalone parameters only if not specified in individual config
                        // Individual configs take precedence over pipeline parameters
                        if (!individual.containsKey('BUILD_NUMBER')) {
                            merged.BUILD_NUMBER = params.BUILD_NUMBER
                        }
                        if (!individual.containsKey('RELEASE')) {
                            merged.RELEASE = params.RELEASE
                        }
                        if (!individual.containsKey('AUTOLIB_BRANCH')) {
                            merged.AUTOLIB_BRANCH = params.AUTOLIB_BRANCH
                        }

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

                            // Start with required parameters that are always present
                            def buildParams = [
                                string(name: 'PARAMS_JSON', value: groovy.json.JsonOutput.toJson(merged.PARAMS_JSON)),
                                string(name: 'RELEASE', value: merged.RELEASE),
                                string(name: 'BUILD_NUMBER', value: merged.BUILD_NUMBER),
                                string(name: 'NODE_NAME', value: merged.NODE_NAME),
                                string(name: 'FEATURE_NAME', value: merged.FEATURE_NAME)
                            ]

                            // ADDED: Add AUTOLIB_BRANCH parameter to downstream jobs
                            if (merged.containsKey('AUTOLIB_BRANCH')) {
                                buildParams << string(name: 'AUTOLIB_BRANCH', value: merged.AUTOLIB_BRANCH)
                            }

                            // Defensively add optional string parameters only if they exist in config
                            if (merged.containsKey('TEST_CASE_FOLDER')) {
                                buildParams << string(name: 'TEST_CASE_FOLDER', value: merged.TEST_CASE_FOLDER)
                            }
                            if (merged.containsKey('TEST_CONFIG_CHOICE')) {
                                buildParams << string(name: 'TEST_CONFIG_CHOICE', value: merged.TEST_CONFIG_CHOICE)
                            }
                            if (merged.containsKey('TEST_GROUP_CHOICE')) {
                                buildParams << string(name: 'TEST_GROUP_CHOICE', value: merged.TEST_GROUP_CHOICE)
                            }
                            if (merged.containsKey('TEST_GROUPS')) {
                                buildParams << string(name: 'TEST_GROUPS', value: merged.TEST_GROUPS)
                            }
                            if (merged.containsKey('DOCKER_COMPOSE_FILE_CHOICE')) {
                                buildParams << string(name: 'DOCKER_COMPOSE_FILE_CHOICE', value: merged.DOCKER_COMPOSE_FILE_CHOICE)
                            }
                            if (merged.containsKey('SEND_TO')) {
                                buildParams << string(name: 'SEND_TO', value: merged.SEND_TO)
                            }
                            if (merged.containsKey('VMPC_NAMES')) {
                                buildParams << string(name: 'VMPC_NAMES', value: merged.VMPC_NAMES)
                            }
                            if (merged.containsKey('ORIOLE_SUBMIT_FLAG')) {
                                buildParams << string(name: 'ORIOLE_SUBMIT_FLAG', value: merged.ORIOLE_SUBMIT_FLAG)
                            }

                            // Defensively add boolean parameters only if they exist in config
                            if (merged.containsKey('FORCE_UPDATE_DOCKER_FILE')) {
                                buildParams << booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', value: merged.FORCE_UPDATE_DOCKER_FILE)
                            }
                            if (merged.containsKey('SKIP_PROVISION')) {
                                buildParams << booleanParam(name: 'SKIP_PROVISION', value: merged.SKIP_PROVISION)
                            }
                            if (merged.containsKey('SKIP_PROVISION_TEST_ENV')) {
                                buildParams << booleanParam(name: 'SKIP_PROVISION_TEST_ENV', value: merged.SKIP_PROVISION_TEST_ENV)
                            }
                            if (merged.containsKey('SKIP_TEST')) {
                                buildParams << booleanParam(name: 'SKIP_TEST', value: merged.SKIP_TEST)
                            }
                            if (merged.containsKey('PROVISION_VMPC')) {
                                buildParams << booleanParam(name: 'PROVISION_VMPC', value: merged.PROVISION_VMPC)
                            }
                            if (merged.containsKey('PROVISION_DOCKER')) {
                                buildParams << booleanParam(name: 'PROVISION_DOCKER', value: merged.PROVISION_DOCKER)
                            }

                            build job: 'fortistack_master_provision_runtest', parameters: buildParams, wait: true
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
