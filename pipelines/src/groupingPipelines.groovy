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
    "SVN_BRANCH": "v760"
  },
  "FORCE_UPDATE_DOCKER_FILE": true,
  "SKIP_PROVISION": false,
  "SKIP_TEST": false
}''',
            description: 'Common configuration parameters as JSON (PARAMS_JSON is now a JSON object)'
        )
        // Remove INDIVIDUAL_CONFIGS as it will be automatically generated
        booleanParam(
            name: 'REGENERATE_DISPATCH',
            defaultValue: true,
            description: 'Regenerate dispatch.json by running load_balancer.py'
        )
        string(
            name: 'LOAD_BALANCER_ARGS',
            defaultValue: '-a',
            description: 'Arguments to pass to load_balancer.py (default: "-a" to use Jenkins nodes)'
        )
        // Add dry run parameter
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
            description: 'When enabled, only print parameters without triggering downstream jobs'
        )
    }

    stages {
        stage('Set Build Display Name') {
            steps {
                script {
                    // Add DRY RUN indicator to the display name
                    def displayName = "#${currentBuild.number}-r${params.RELEASE}-b${params.BUILD_NUMBER}"
                    if (params.DRY_RUN) {
                        displayName += "-DRY_RUN"
                    }
                    currentBuild.displayName = displayName
                }
            }
        }

        stage('Generate Dispatch Configuration') {
            when {
                expression { return params.REGENERATE_DISPATCH }
            }
            steps {
                script {
                    echo "Running load_balancer.py to generate dispatch.json"
                    sh """
                        cd /home/fosqa/jenkins-master/feature-configs/fortistack
                        python3 load_balancer.py ${params.LOAD_BALANCER_ARGS}
                    """
                }
            }
        }

        stage('Read Dispatch Configuration') {
            steps {
                script {
                    echo "Reading dispatch.json for individual configurations"
                    def dispatchJson = readFile('/home/fosqa/jenkins-master/feature-configs/fortistack/dispatch.json')
                    def individualConfigs = readJSON text: dispatchJson
                    echo "Loaded ${individualConfigs.size()} configurations from dispatch.json"

                    // Store for later use
                    env.INDIVIDUAL_CONFIGS_JSON = dispatchJson
                }
            }
        }

        stage('Prepare Job Configurations') {
            steps {
                script {
                    // Parse the common config JSON
                    def common = readJSON text: params.COMMON_CONFIG
                    // Parse the individual configurations from the environment variable
                    def individualConfigs = readJSON text: env.INDIVIDUAL_CONFIGS_JSON

                    def jobConfigs = []
                    def configSummary = [:]

                    // Iterate over each individual configuration and merge with common settings.
                    individualConfigs.eachWithIndex { individual, i ->
                        def merged = common + individual
                        // Override BUILD_NUMBER with the standalone parameter.
                        merged.BUILD_NUMBER = params.BUILD_NUMBER
                        merged.RELEASE = params.RELEASE

                        // Normalize TEST_GROUPS to handle different formats
                        def testGroups = individual.TEST_GROUPS

                        // If TEST_GROUPS is already a JSON array in the input
                        if (testGroups instanceof List) {
                            merged.TEST_GROUPS = groovy.json.JsonOutput.toJson(testGroups)
                        }
                        // If it's a string or anything else, ensure it's properly formatted
                        else if (testGroups) {
                            merged.TEST_GROUPS = groovy.json.JsonOutput.toJson(testGroups)
                        } else {
                            merged.TEST_GROUPS = "[]"
                        }

                        jobConfigs << merged

                        // Track node usage for summary
                        def nodeName = individual.NODE_NAME
                        if (!configSummary.containsKey(nodeName)) {
                            configSummary[nodeName] = []
                        }
                        configSummary[nodeName] << [
                            index: i,
                            feature: individual.FEATURE_NAME,
                            groups: individual.TEST_GROUPS instanceof List ? individual.TEST_GROUPS.size() : 0
                        ]
                    }

                    // Pretty print detailed configuration for each job
                    echo "==== JOB CONFIGURATIONS ===="
                    jobConfigs.eachWithIndex { config, index ->
                        echo "CONFIG #${index} (${config.FEATURE_NAME} on ${config.NODE_NAME}):"
                        echo groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(config))
                        echo "------------------------"
                    }

                    // Pretty print summary of nodes and features
                    echo "==== NODE DISTRIBUTION SUMMARY ===="
                    configSummary.each { node, features ->
                        echo "NODE: ${node} - ${features.size()} features"
                        features.each { f ->
                            echo "  #${f.index}: ${f.feature} (${f.groups} test groups)"
                        }
                    }

                    // Store for downstream stage
                    env.JOB_CONFIGS = groovy.json.JsonOutput.toJson(jobConfigs)
                }
            }
        }

        stage('Trigger Downstream Jobs') {
            when {
                expression { return !params.DRY_RUN }
            }
            steps {
                script {
                    def jobConfigs = readJSON text: env.JOB_CONFIGS
                    def parallelBuilds = [:]

                    jobConfigs.eachWithIndex { config, i ->
                        def branchName = "Run_${i}_${config.FEATURE_NAME}_${config.NODE_NAME}"
                        parallelBuilds[branchName] = {
                            echo "Triggering fortistack_master_provision_runtest with configuration: ${config}"
                            build job: 'fortistack_master_provision_runtest', parameters: [
                                string(name: 'PARAMS_JSON', value: groovy.json.JsonOutput.toJson(config.PARAMS_JSON)),
                                string(name: 'RELEASE', value: config.RELEASE),
                                string(name: 'BUILD_NUMBER', value: config.BUILD_NUMBER),
                                string(name: 'NODE_NAME', value: config.NODE_NAME),
                                booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', value: config.FORCE_UPDATE_DOCKER_FILE),
                                string(name: 'FEATURE_NAME', value: config.FEATURE_NAME),
                                string(name: 'TEST_CASE_FOLDER', value: config.TEST_CASE_FOLDER),
                                string(name: 'TEST_CONFIG_CHOICE', value: config.TEST_CONFIG_CHOICE),
                                string(name: 'TEST_GROUP_CHOICE', value: config.TEST_GROUP_CHOICE),
                                string(name: 'TEST_GROUPS', value: config.TEST_GROUPS),
                                string(name: 'DOCKER_COMPOSE_FILE_CHOICE', value: config.DOCKER_COMPOSE_FILE_CHOICE),
                                string(name: 'SEND_TO', value: config.SEND_TO),
                                booleanParam(name: 'SKIP_PROVISION', value: config.SKIP_PROVISION),
                                booleanParam(name: 'SKIP_TEST', value: config.SKIP_TEST),
                                booleanParam(name: 'PROVISION_VMPC', value: config.PROVISION_VMPC ?: false),
                                string(name: 'VMPC_NAMES', value: config.VMPC_NAMES ?: ''),
                                booleanParam(name: 'PROVISION_DOCKER', value: config.PROVISION_DOCKER ?: true),
                                string(name: 'ORIOLE_SUBMIT_FLAG', value: config.ORIOLE_SUBMIT_FLAG ?: 'all'),
                            ], wait: true
                        }
                    }

                    // Execute all jobs in parallel.
                    parallel parallelBuilds
                }
            }
        }

        stage('Dry Run Summary') {
            when {
                expression { return params.DRY_RUN }
            }
            steps {
                script {
                    def jobConfigs = readJSON text: env.JOB_CONFIGS

                    echo "=== DRY RUN SUMMARY ==="
                    echo "Would have triggered ${jobConfigs.size()} downstream jobs"
                    echo "Configuration validation complete - NO JOBS WERE TRIGGERED"

                    // Create artifact with all configurations for reference
                    writeFile file: 'dry_run_configs.json', text: groovy.json.JsonOutput.prettyPrint(env.JOB_CONFIGS)
                    archiveArtifacts artifacts: 'dry_run_configs.json', fingerprint: true
                }
            }
        }
    }

    post {
        always {
            script {
                echo "Upstream pipeline completed."

                // Determine if this was a dry run
                def dryRunPrefix = params.DRY_RUN ? "[DRY RUN] " : ""

                // Get information about configurations
                def individualConfigs = readJSON text: env.INDIVIDUAL_CONFIGS_JSON
                def buildSummary = "<h3>${dryRunPrefix}Build Summary</h3><ul>"

                // Group by node
                def nodeMap = [:]
                individualConfigs.each { config ->
                    def nodeName = config.NODE_NAME
                    if (!nodeMap.containsKey(nodeName)) {
                        nodeMap[nodeName] = []
                    }
                    nodeMap[nodeName] << config
                }

                // Build summary table
                buildSummary += "<table border='1' style='border-collapse: collapse; width: 100%;'>"
                buildSummary += "<tr><th>Node</th><th>Features</th><th>Test Groups</th></tr>"

                nodeMap.each { node, configs ->
                    def featuresStr = configs.collect { it.FEATURE_NAME }.join(", ")
                    def groupCount = configs.collect {
                        it.TEST_GROUPS instanceof List ? it.TEST_GROUPS.size() : 0
                    }.sum()

                    buildSummary += "<tr><td>${node}</td><td>${featuresStr}</td><td>${groupCount}</td></tr>"
                }

                buildSummary += "</table>"

                if (params.DRY_RUN) {
                    buildSummary += "<p><b>DRY RUN:</b> No downstream jobs were triggered</p>"
                }

                // Determine addresses for notification
                def commonConfig = readJSON text: params.COMMON_CONFIG
                def notifyTo = commonConfig.PARAMS_JSON.send_to ?: "yzhengfeng@fortinet.com"

                // Send notification email
                emailext (
                    subject: "${dryRunPrefix}Fortistack Group Pipeline: ${currentBuild.fullDisplayName}",
                    body: """
                    <p>${dryRunPrefix}Fortistack group pipeline has completed.</p>
                    <p><b>Status:</b> ${currentBuild.result ?: 'SUCCESS'}</p>
                    <p><b>Pipeline URL:</b> <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                    ${buildSummary}
                    """,
                    to: notifyTo,
                    mimeType: 'text/html'
                )
            }
        }
    }
}
