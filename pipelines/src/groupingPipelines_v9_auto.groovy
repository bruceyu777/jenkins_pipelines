pipeline {
    agent { label 'master' }

    parameters {
        string(
            name: 'RELEASE',
            defaultValue: '7.6.5',
            description: 'Enter the release number, with 3 digits, like 7.6.4, or 8.0.0'
        )
        string(
            name: 'BUILD_NUMBER',
            defaultValue: '3619',
            description: 'Enter the build number'
        )
        string(
            name: 'BUILD_KEYWORD',
            defaultValue: 'KVM',
            description: 'Build keyword for image type (e.g., KVM, DOCKER, VMWARE, etc.)'
        )
        string(
            name: 'AUTOLIB_BRANCH',
            defaultValue: 'v3r10build0007',
            description: 'Which branch of the autolib_v3 repo to checkout before running tests'
        )
        // NEW: Add SCHEDULE parameter
        booleanParam(
            name: 'SCHEDULE',
            defaultValue: false,
            description: 'Enable to wait for build readiness before starting tests. Pipeline will poll every 2 minutes.'
        )
        string(
            name: 'SCHEDULE_TIMEOUT_MINUTES',
            defaultValue: '1200',
            description: 'Maximum time (in minutes) to wait for build readiness when SCHEDULE is enabled'
        )
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
            description: 'Common configuration parameters as JSON'
        )
        booleanParam(
            name: 'REGENERATE_DISPATCH',
            defaultValue: true,
            description: 'Regenerate dispatch.json by running load_balancer.py'
        )
        string(
            name: 'LOAD_BALANCER_ARGS',
            defaultValue: '-a',
            description: 'Arguments to pass to load-balancer.py'
        )
        string(
            name: 'TEST_GROUP_TYPE',
            defaultValue: 'full',
            description: 'Type of group to create (default: "full", can be "full","crit","tmp")'
        )
        string(
            name: 'FEATURE_FILTER_LIST',
            defaultValue: '',
            description: 'Comma-separated list of features to include. Leave empty to include all features.'
        )
        string(
            name: 'EMAIL_RECIPIENTS',
            defaultValue: 'yzhengfeng@fortinet.com,rainxiao@fortinet.com,wangd@fortinet.com,nzhang@fortinet.com,qxu@fortinet.com',
            description: 'Comma-separated list of email recipients for the test results report'
        )
        booleanParam(
            name: 'DRY_RUN',
            defaultValue: false,
            description: 'When enabled, only print parameters without triggering downstream jobs'
        )
        booleanParam(
            name: 'FORCE_SEND_TEST_RESULTS',
            defaultValue: false,
            description: 'When enabled, send test results report even during dry run'
        )
        choice(
            name: 'ORIOLE_SUBMIT_FLAG',
            choices: ['succeeded', 'all', 'none'],
            description: 'Only passed test case submissions or all/none'
        )
    }

    stages {
        stage('Set Build Display Name') {
            steps {
                script {
                    def displayName = "#${currentBuild.number}-r${params.RELEASE}-b${params.BUILD_NUMBER}"
                    if (params.DRY_RUN) {
                        displayName += "-DRY_RUN"
                    }
                    if (params.SCHEDULE) {
                        displayName += "-SCHEDULED"
                    }
                    if (params.AUTOLIB_BRANCH && params.AUTOLIB_BRANCH != 'main') {
                        displayName += "-${params.AUTOLIB_BRANCH}"
                    }
                    if (params.FEATURE_FILTER_LIST?.trim()) {
                        def featureCount = params.FEATURE_FILTER_LIST.split(',').size()
                        displayName += "-${featureCount}features"
                    }
                    currentBuild.displayName = displayName
                }
            }
        }

        // NEW STAGE: Wait for Build Readiness
        stage('Wait for Build Readiness') {
            when {
                expression { return params.SCHEDULE }
            }
            steps {
                script {
                    echo "=== WAITING FOR BUILD READINESS ==="
                    echo "Project: FortiOS"
                    echo "Release: ${params.RELEASE}"
                    echo "Build: ${params.BUILD_NUMBER}"
                    echo "Build Keyword: ${params.BUILD_KEYWORD}"
                    echo "Timeout: ${params.SCHEDULE_TIMEOUT_MINUTES} minutes"
                    echo "Poll interval: 2 minutes"

                    def timeoutMinutes = params.SCHEDULE_TIMEOUT_MINUTES.toInteger()
                    def pollIntervalSeconds = 120
                    def maxAttempts = (timeoutMinutes * 60) / pollIntervalSeconds
                    def attempt = 0
                    def buildReady = false
                    def startTime = System.currentTimeMillis()

                    while (attempt < maxAttempts && !buildReady) {
                        attempt++
                        def elapsedMinutes = (System.currentTimeMillis() - startTime) / 60000

                        echo "=== Attempt ${attempt}/${maxAttempts.intValue()} (${String.format('%.1f', elapsedMinutes)} minutes elapsed) ==="

                        try {
                            def checkResult = sh(
                                script: """
                                    cd /home/fosqa/jenkins-master/pipelines/tools
                                    python3 check_build_ready.py \
                                        --release ${params.RELEASE} \
                                        --build ${params.BUILD_NUMBER} \
                                        --build-keyword ${params.BUILD_KEYWORD} \
                                        --project FortiOS \
                                        --json-output
                                """,
                                returnStatus: true
                            )

                            if (checkResult == 0) {
                                echo "✅ Build ${params.BUILD_NUMBER} (${params.BUILD_KEYWORD}) is READY!"
                                buildReady = true

                                echo "=== Triggering rsync on Docker host via SSH ==="
                                try {
                                    // Use Jenkins credentials for SSH authentication
                                    withCredentials([usernamePassword(
                                        credentialsId: 'CdnSshCredential',
                                        usernameVariable: 'SSH_USER',
                                        passwordVariable: 'SSH_PASS'
                                    )]) {
                                        def rsyncOutput = sh(
                                            script: '''
                                                # Use sshpass to provide password for SSH
                                                export SSHPASS="$SSH_PASS"
                                                sshpass -e ssh \
                                                    -o StrictHostKeyChecking=no \
                                                    -o UserKnownHostsFile=/dev/null \
                                                    -o ConnectTimeout=10 \
                                                    ${SSH_USER}@192.168.99.1 \
                                                    "echo '$SSH_PASS' | sudo -S /home/fosqa/tools/sync_fos_images.sh ''' + params.RELEASE + ''' ''' + params.BUILD_NUMBER + ''' ''' + params.BUILD_KEYWORD + '''"
                                            ''',
                                            returnStdout: true
                                        ).trim()

                                        echo "Rsync output:"
                                        echo rsyncOutput
                                        echo "✅ Rsync completed successfully"
                                    }

                                } catch (Exception rsyncErr) {
                                    echo "⚠️ Rsync failed: ${rsyncErr.getMessage()}"
                                    echo "Build is ready, continuing despite rsync issue..."
                                }

                            } else {
                                echo "⏳ Build ${params.BUILD_NUMBER} (${params.BUILD_KEYWORD}) is not ready yet..."

                                if (attempt < maxAttempts) {
                                    echo "⏰ Waiting ${pollIntervalSeconds} seconds before next check..."
                                    sleep time: pollIntervalSeconds, unit: 'SECONDS'
                                }
                            }

                        } catch (Exception e) {
                            echo "⚠️ Error checking build readiness: ${e.getMessage()}"

                            if (attempt < maxAttempts) {
                                echo "⏰ Will retry in ${pollIntervalSeconds} seconds..."
                                sleep time: pollIntervalSeconds, unit: 'SECONDS'
                            }
                        }
                    }

                    if (!buildReady) {
                        def totalWaitTime = String.format('%.1f', (System.currentTimeMillis() - startTime) / 60000)
                        echo "❌ Build ${params.BUILD_NUMBER} (${params.BUILD_KEYWORD}) did not become ready within ${timeoutMinutes} minutes (waited ${totalWaitTime} minutes)"

                        // Store failure information for email notification
                        env.BUILD_READY_TIMEOUT = 'true'
                        env.BUILD_WAIT_TIME = totalWaitTime

                        // Set build result and stop pipeline
                        currentBuild.result = 'ABORTED'
                        error "Build readiness timeout: Build ${params.BUILD_NUMBER} (${params.BUILD_KEYWORD}) not ready after ${totalWaitTime} minutes"
                    }

                    echo "=== Build readiness check completed successfully ==="
                    echo "✅ Proceeding with pipeline execution..."
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

                    def pythonArgs = "-g ${params.TEST_GROUP_TYPE}"

                    if (params.FEATURE_FILTER_LIST?.trim()) {
                        echo "Applying feature filter: ${params.FEATURE_FILTER_LIST}"
                        pythonArgs += " -f \"${params.FEATURE_FILTER_LIST}\""
                    } else {
                        echo "No feature filtering applied - including all features"
                    }

                    pythonArgs += " ${params.LOAD_BALANCER_ARGS}"

                    def command = """
                        cd /home/fosqa/jenkins-master/feature-configs/fortistack

                        echo "=== USING CONTAINER'S PYTHON WITH PRE-INSTALLED PACKAGES ==="
                        python3 --version
                        python3 -c "import requests, pymongo; print('All packages available')"

                        echo "=== RUNNING LOAD BALANCER ==="
                        python3 load_balancer.py ${pythonArgs}
                    """.stripIndent().trim()

                    echo "Executing command: ${command}"
                    sh command
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

                    if (params.FEATURE_FILTER_LIST?.trim()) {
                        def requestedFeatures = params.FEATURE_FILTER_LIST.split(',').collect { it.trim() }
                        def actualFeatures = individualConfigs.collect { it.FEATURE_NAME }.unique().sort()
                        echo "Feature filter applied:"
                        echo "  Requested: ${requestedFeatures}"
                        echo "  Generated: ${actualFeatures}"
                    }

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

                        // ADDED: Add AUTOLIB_BRANCH to the merged configuration
                        merged.AUTOLIB_BRANCH = params.AUTOLIB_BRANCH

                        // ADDED: Add ORIOLE_SUBMIT_FLAG to the merged configuration
                        merged.ORIOLE_SUBMIT_FLAG = params.ORIOLE_SUBMIT_FLAG

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

                    // Print feature filtering summary
                    if (params.FEATURE_FILTER_LIST?.trim()) {
                        def actualFeatures = individualConfigs.collect { it.FEATURE_NAME }.unique().sort()
                        echo "==== FEATURE FILTERING SUMMARY ===="
                        echo "Requested features: ${params.FEATURE_FILTER_LIST}"
                        echo "Generated features: ${actualFeatures.join(', ')}"
                        echo "Total features in dispatch: ${actualFeatures.size()}"
                    } else {
                        def allFeatures = individualConfigs.collect { it.FEATURE_NAME }.unique().sort()
                        echo "==== ALL FEATURES INCLUDED ===="
                        echo "Features: ${allFeatures.join(', ')}"
                        echo "Total features: ${allFeatures.size()}"
                    }

                    // Log AUTOLIB_BRANCH configuration
                    echo "==== AUTOLIB_BRANCH CONFIGURATION ===="
                    echo "AUTOLIB_BRANCH: ${params.AUTOLIB_BRANCH}"
                    echo "This will be passed to all downstream jobs"

                    // Log ORIOLE_SUBMIT_FLAG configuration
                    echo "==== ORIOLE_SUBMIT_FLAG CONFIGURATION ===="
                    echo "ORIOLE_SUBMIT_FLAG: ${params.ORIOLE_SUBMIT_FLAG}"
                    echo "This will be passed to all downstream jobs"

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
                    def jobResults = [:]

                    jobConfigs.eachWithIndex { config, i ->
                        def branchName = "Run_${i}_${config.FEATURE_NAME}_${config.NODE_NAME}"
                        parallelBuilds[branchName] = {
                            try {
                                echo "Triggering fortistack_master_provision_runtest with configuration: ${config}"

                                // Start with required parameters that are always present
                                def buildParams = [
                                    string(name: 'PARAMS_JSON', value: groovy.json.JsonOutput.toJson(config.PARAMS_JSON)),
                                    string(name: 'RELEASE', value: config.RELEASE),
                                    string(name: 'BUILD_NUMBER', value: config.BUILD_NUMBER),
                                    string(name: 'NODE_NAME', value: config.NODE_NAME),
                                    string(name: 'FEATURE_NAME', value: config.FEATURE_NAME)
                                ]

                                // ADDED: Add AUTOLIB_BRANCH parameter to downstream jobs
                                if (config.containsKey('AUTOLIB_BRANCH')) {
                                    buildParams << string(name: 'AUTOLIB_BRANCH', value: config.AUTOLIB_BRANCH)
                                }

                                // ADDED: Add ORIOLE_SUBMIT_FLAG parameter to downstream jobs
                                if (config.containsKey('ORIOLE_SUBMIT_FLAG')) {
                                    buildParams << string(name: 'ORIOLE_SUBMIT_FLAG', value: config.ORIOLE_SUBMIT_FLAG)
                                }

                                // Defensively add optional string parameters only if they exist in config
                                if (config.containsKey('TEST_CASE_FOLDER')) {
                                    buildParams << string(name: 'TEST_CASE_FOLDER', value: config.TEST_CASE_FOLDER)
                                }
                                if (config.containsKey('TEST_CONFIG_CHOICE')) {
                                    buildParams << string(name: 'TEST_CONFIG_CHOICE', value: config.TEST_CONFIG_CHOICE)
                                }
                                if (config.containsKey('TEST_GROUP_CHOICE')) {
                                    buildParams << string(name: 'TEST_GROUP_CHOICE', value: config.TEST_GROUP_CHOICE)
                                }
                                if (config.containsKey('TEST_GROUPS')) {
                                    buildParams << string(name: 'TEST_GROUPS', value: config.TEST_GROUPS)
                                }
                                if (config.containsKey('DOCKER_COMPOSE_FILE_CHOICE')) {
                                    buildParams << string(name: 'DOCKER_COMPOSE_FILE_CHOICE', value: config.DOCKER_COMPOSE_FILE_CHOICE)
                                }
                                if (config.containsKey('SEND_TO')) {
                                    buildParams << string(name: 'SEND_TO', value: config.SEND_TO)
                                }
                                if (config.containsKey('VMPC_NAMES')) {
                                    buildParams << string(name: 'VMPC_NAMES', value: config.VMPC_NAMES)
                                }

                                // Defensively add boolean parameters only if they exist in config
                                if (config.containsKey('FORCE_UPDATE_DOCKER_FILE')) {
                                    buildParams << booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', value: config.FORCE_UPDATE_DOCKER_FILE)
                                }
                                if (config.containsKey('SKIP_PROVISION')) {
                                    buildParams << booleanParam(name: 'SKIP_PROVISION', value: config.SKIP_PROVISION)
                                }
                                if (config.containsKey('SKIP_PROVISION_TEST_ENV')) {
                                    buildParams << booleanParam(name: 'SKIP_PROVISION_TEST_ENV', value: config.SKIP_PROVISION_TEST_ENV)
                                }
                                if (config.containsKey('SKIP_TEST')) {
                                    buildParams << booleanParam(name: 'SKIP_TEST', value: config.SKIP_TEST)
                                }
                                if (config.containsKey('PROVISION_VMPC')) {
                                    buildParams << booleanParam(name: 'PROVISION_VMPC', value: config.PROVISION_VMPC)
                                }
                                if (config.containsKey('PROVISION_DOCKER')) {
                                    buildParams << booleanParam(name: 'PROVISION_DOCKER', value: config.PROVISION_DOCKER)
                                }

                                def result = build job: 'fortistack_master_provision_runtest', parameters: buildParams, wait: true
                                jobResults[branchName] = [status: 'SUCCESS', result: result]
                                echo "Job ${branchName} completed successfully"
                            } catch (Exception e) {
                                jobResults[branchName] = [status: 'FAILED', error: e.getMessage()]
                                echo "Job ${branchName} failed: ${e.getMessage()}"
                                // Don't throw the exception - let other jobs continue
                            }
                        }
                    }

                    // Execute all jobs in parallel.
                    parallel parallelBuilds

                    // Summary of results
                    def successCount = 0
                    def failureCount = 0
                    echo "=== DOWNSTREAM JOBS SUMMARY ==="
                    jobResults.each { jobName, result ->
                        if (result.status == 'SUCCESS') {
                            successCount++
                            echo "✓ ${jobName}: SUCCESS"
                        } else {
                            failureCount++
                            echo "✗ ${jobName}: FAILED - ${result.error}"
                        }
                    }

                    echo "Total: ${successCount} succeeded, ${failureCount} failed"

                    // Store results for later stages
                    env.JOB_RESULTS_SUMMARY = "${successCount} succeeded, ${failureCount} failed"

                    // Set build result to UNSTABLE if there were failures, but don't fail the pipeline
                    if (failureCount > 0) {
                        currentBuild.result = 'UNSTABLE'
                        echo "Pipeline marked as UNSTABLE due to ${failureCount} failed downstream jobs"
                    }
                }
            }
        }

        stage('Send Test Results Report') {
            // Always run this stage unless it's a dry run (and FORCE_SEND_TEST_RESULTS is false)
            when {
                expression { return !params.DRY_RUN || params.FORCE_SEND_TEST_RESULTS }
            }
            steps {
                script {
                    echo "=== SENDING TEST RESULTS REPORT ==="
                    echo "Release: ${params.RELEASE}"
                    echo "Build: ${params.BUILD_NUMBER}"
                    echo "Recipients: ${params.EMAIL_RECIPIENTS}"
                    echo "DRY_RUN: ${params.DRY_RUN}"
                    echo "FORCE_SEND_TEST_RESULTS: ${params.FORCE_SEND_TEST_RESULTS}"

                    // Include job results summary if available
                    if (env.JOB_RESULTS_SUMMARY) {
                        echo "Downstream Jobs Summary: ${env.JOB_RESULTS_SUMMARY}"
                    }

                    def command = """
                        cd /home/fosqa/resources/tools

                        echo "=== SENDING AUTOLIB TEST RESULTS EMAIL ==="
                        echo "=== CHECKING PYTHON ENVIRONMENT ==="
                        python3 --version
                        python3 -c "import pandas, openpyxl, pymongo, requests, libvirt; print('All required packages available')"
                        echo "=== RUNNING FETCH_AUTOLIB_RESULTS ==="
                        python3 fetch_autolib_results.py -r ${params.RELEASE} -b ${params.BUILD_NUMBER} -t ${params.EMAIL_RECIPIENTS}
                    """.stripIndent().trim()

                    echo "Executing command: ${command}"

                    try {
                        sh command
                        echo "Test results report sent successfully"
                    } catch (Exception e) {
                        echo "Warning: Failed to send test results report: ${e.getMessage()}"
                        // Don't fail the pipeline if email sending fails - just mark as unstable
                        if (currentBuild.result != 'FAILURE') {
                            currentBuild.result = 'UNSTABLE'
                        }
                    }
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
                    echo "AUTOLIB_BRANCH: ${params.AUTOLIB_BRANCH}"
                    echo "ORIOLE_SUBMIT_FLAG: ${params.ORIOLE_SUBMIT_FLAG}"
                    echo "FORCE_SEND_TEST_RESULTS: ${params.FORCE_SEND_TEST_RESULTS}"

                    if (params.FORCE_SEND_TEST_RESULTS) {
                        echo "NOTE: Test results report will be sent despite dry run mode"
                    }

                    if (params.FEATURE_FILTER_LIST?.trim()) {
                        def actualFeatures = jobConfigs.collect { it.FEATURE_NAME }.unique().sort()
                        echo "Feature filtering applied:"
                        echo "  Requested: ${params.FEATURE_FILTER_LIST}"
                        echo "  Generated: ${actualFeatures.join(', ')}"
                    }

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

                def dryRunPrefix = params.DRY_RUN ? "[DRY RUN] " : ""
                def schedulePrefix = params.SCHEDULE ? "[SCHEDULED] " : ""

                // Check if pipeline was aborted due to build readiness timeout
                if (env.BUILD_READY_TIMEOUT == 'true') {
                    echo "=== SENDING BUILD READINESS TIMEOUT NOTIFICATION ==="

                    def timeoutSummary = """
                        <h3 style="color: red;">❌ Build Readiness Timeout</h3>
                        <p><b>Status:</b> <span style="color: red;">ABORTED - Build Not Ready</span></p>
                        <p><b>Release:</b> ${params.RELEASE}</p>
                        <p><b>Build:</b> ${params.BUILD_NUMBER}</p>
                        <p><b>Build Keyword:</b> ${params.BUILD_KEYWORD}</p>
                        <p><b>Timeout Duration:</b> ${params.SCHEDULE_TIMEOUT_MINUTES} minutes</p>
                        <p><b>Actual Wait Time:</b> ${env.BUILD_WAIT_TIME} minutes</p>
                        <p><b>Pipeline URL:</b> <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                        <hr>
                        <p><b>Details:</b></p>
                        <ul>
                            <li>The build was not available on the Image Server within the specified timeout period.</li>
                            <li>The pipeline has been aborted and no tests were executed.</li>
                            <li>Please verify the build status on the Image Server: <a href="http://172.18.52.254:8090">http://172.18.52.254:8090</a></li>
                            <li>You may need to manually trigger the pipeline once the build is ready.</li>
                        </ul>
                        <hr>
                        <p><b>Next Steps:</b></p>
                        <ol>
                            <li>Check if build ${params.BUILD_NUMBER} (${params.BUILD_KEYWORD}) exists on the Image Server</li>
                            <li>Verify the build process completed successfully</li>
                            <li>Re-run this pipeline once the build is available</li>
                        </ol>
                    """

                    def commonConfig = readJSON text: params.COMMON_CONFIG
                    def notifyTo = commonConfig.PARAMS_JSON.send_to ?: "yzhengfeng@fortinet.com"

                    emailext (
                        subject: "❌ [TIMEOUT] ${schedulePrefix}Build Not Ready: ${params.RELEASE}-${params.BUILD_NUMBER} (${params.BUILD_KEYWORD})",
                        body: timeoutSummary,
                        to: notifyTo,
                        mimeType: 'text/html'
                    )

                    echo "✅ Timeout notification email sent"
                    return  // Exit early, don't send normal summary
                }

                // Normal pipeline completion email (only sent if build was ready)
                def individualConfigs = readJSON text: env.INDIVIDUAL_CONFIGS_JSON
                def buildSummary = "<h3>${schedulePrefix}${dryRunPrefix}Build Summary</h3>"

                // Add schedule information if enabled
                if (params.SCHEDULE) {
                    buildSummary += "<p><b>SCHEDULED BUILD:</b> Pipeline waited for build readiness</p>"
                    buildSummary += "<p><b>Schedule Timeout:</b> ${params.SCHEDULE_TIMEOUT_MINUTES} minutes</p>"
                    if (env.BUILD_WAIT_TIME) {
                        buildSummary += "<p><b>Actual Wait Time:</b> ${env.BUILD_WAIT_TIME} minutes</p>"
                    }
                }

                buildSummary += "<p><b>Release:</b> ${params.RELEASE}</p>"
                buildSummary += "<p><b>Build:</b> ${params.BUILD_NUMBER}</p>"
                buildSummary += "<p><b>AUTOLIB_BRANCH:</b> ${params.AUTOLIB_BRANCH}</p>"
                buildSummary += "<p><b>ORIOLE_SUBMIT_FLAG:</b> ${params.ORIOLE_SUBMIT_FLAG}</p>"
                buildSummary += "<p><b>Test Results Email Recipients:</b> ${params.EMAIL_RECIPIENTS}</p>"

                if (params.FEATURE_FILTER_LIST?.trim()) {
                    def actualFeatures = individualConfigs.collect { it.FEATURE_NAME }.unique().sort()
                    buildSummary += "<p><b>Feature Filter Applied:</b> ${params.FEATURE_FILTER_LIST}</p>"
                    buildSummary += "<p><b>Generated Features:</b> ${actualFeatures.join(', ')}</p>"
                }

                buildSummary += "<table border='1' style='border-collapse: collapse; width: 100%;'>"
                buildSummary += "<tr><th>Node</th><th>Features</th><th>Test Groups</th></tr>"

                def nodeMap = [:]
                individualConfigs.each { config ->
                    def nodeName = config.NODE_NAME
                    if (!nodeMap.containsKey(nodeName)) {
                        nodeMap[nodeName] = []
                    }
                    nodeMap[nodeName] << config
                }

                nodeMap.each { node, configs ->
                    def featuresStr = configs.collect { it.FEATURE_NAME }.join(", ")
                    def groupCount = configs.collect {
                        it.TEST_GROUPS instanceof List ? it.TEST_GROUPS.size() : 0
                    }.sum()

                    buildSummary += "<tr><td>${node}</td><td>${featuresStr}</td><td>${groupCount}</td></tr>"
                }

                buildSummary += "</table>"

                if (env.JOB_RESULTS_SUMMARY) {
                    buildSummary += "<p><b>Downstream Jobs Results:</b> ${env.JOB_RESULTS_SUMMARY}</p>"
                }

                if (params.DRY_RUN) {
                    buildSummary += "<p><b>DRY RUN:</b> No downstream jobs were triggered</p>"
                }

                def commonConfig = readJSON text: params.COMMON_CONFIG
                def notifyTo = commonConfig.PARAMS_JSON.send_to ?: "yzhengfeng@fortinet.com"

                emailext (
                    subject: "${schedulePrefix}${dryRunPrefix}Fortistack Group Pipeline: ${currentBuild.fullDisplayName}",
                    body: """
                    <p>${schedulePrefix}${dryRunPrefix}Fortistack group pipeline has completed.</p>
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
