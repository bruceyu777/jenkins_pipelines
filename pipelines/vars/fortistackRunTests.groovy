// fortistackRunTests.groovy
// Handles test execution only, assuming environment is already provisioned

def getArchiveGroupName(String group) {
    def parts = group.tokenize('.')
    if (parts.size() >= 2) {
        return "${parts[0]}.${parts[1]}"
    } else {
        return group
    }
}

def getTestGroups(params) {
    def testGroups = []
    if (params.TEST_GROUPS) {
        if (params.TEST_GROUPS instanceof String) {
            def tg = params.TEST_GROUPS.trim()
            if (tg.startsWith("\"") && tg.endsWith("\"")) {
                tg = tg.substring(1, tg.length()-1).trim()
            }
            if (tg.startsWith("[")) {
                try {
                    def parsed = readJSON text: tg
                    if (parsed instanceof List) {
                        testGroups = parsed
                    } else {
                        testGroups = tg.split(",").collect { it.trim() }
                    }
                } catch (e) {
                    echo "Error parsing TEST_GROUPS as JSON: ${e}. Falling back to splitting by comma."
                    testGroups = tg.split(",").collect { it.trim() }
                }
            } else {
                testGroups = tg.split(",").collect { it.trim() }
            }
        } else if (params.TEST_GROUPS instanceof List) {
            testGroups = params.TEST_GROUPS
        } else {
            testGroups = [params.TEST_GROUPS.toString()]
        }
    }
    if (!testGroups || testGroups.isEmpty()) {
        testGroups = [params.TEST_GROUP_CHOICE]
    }
    return testGroups
}

def expandParamsJson(String jsonStr) {
    try {
        def jsonMap = new groovy.json.JsonSlurper().parseText(jsonStr) as Map
        jsonMap.each { key, value ->
            binding.setVariable(key, value)
            echo "Set variable: ${key} = ${value}"
        }
        return jsonMap
    } catch (Exception e) {
        error("Failed to parse PARAMS_JSON: ${e}")
    }
}

def computedTestGroups = []  // Global variable to share across stages

def call() {
    // Define sendTo inside the call() block (after params is available)
    def sendTo = (params.SEND_TO?.trim() ? params.SEND_TO : "yzhengfeng@fortinet.com")

    // Existing function that defines your "standard" set of upstream parameters:
    fortistackMasterParameters(exclude: ['FGT_TYPE','SKIP_PROVISION','SKIP_PROVISION_TEST_ENV'])

    // Expand any JSON‐style parameters so they become global variables:
    expandParamsJson(params.PARAMS_JSON)

    pipeline {
        // Use the NODE_NAME coming from upstream (or from user input if run independently)
        agent { label "${params.NODE_NAME}" }

        options {
            buildDiscarder(logRotator(daysToKeepStr: '14'))
        }

        environment {
            TZ = 'America/Vancouver'
        }

        stages {
            stage('Initialize Test Groups') {
                steps {
                    script {
                        computedTestGroups = getTestGroups(params)
                        echo "Computed test groups: ${computedTestGroups}"
                    }
                }
            }

            stage('Set Build Display Name') {
                steps {
                    script {
                        currentBuild.displayName = "#${currentBuild.number} " +
                            "${params.NODE_NAME}-${params.RELEASE}-${params.BUILD_NUMBER}-" +
                            "${FEATURE_NAME}-${computedTestGroups.join(',')}"
                    }
                }
            }

            stage('Debug Parameters') {
                steps {
                    script {
                        echo "=== Debug: Printing All Parameters ==="
                        params.each { key, value ->
                            echo "${key} = ${value}"
                        }
                    }
                }
            }

            stage('Autolib Setup') {
                steps {
                    script {
                        withCredentials([
                            usernamePassword(
                                credentialsId: 'LDAP',
                                usernameVariable: 'SVN_USER',
                                passwordVariable: 'SVN_PASS'
                            )
                        ]) {
                            echo "🔄 Syncing autolib repo (${LOCAL_LIB_DIR}) to branch ${AUTOLIB_BRANCH}, stashing any local edits…"
                            sh """
                                REPO_DIR=/home/fosqa/${LOCAL_LIB_DIR}

                                # mark it safe
                                sudo -u fosqa git config --global --add safe.directory \$REPO_DIR

                                cd \$REPO_DIR

                                # run all three as fosqa
                                sudo -u fosqa sh -c '
                                    git stash push -u -m "autolib auto-stash before pull" || true
                                    git checkout ${AUTOLIB_BRANCH}
                                    git pull --rebase --autostash
                                '
                            """
                        }
                    }
                }
            }

            stage('Verify Environment') {
                steps {
                    script {
                        echo "Verifying test environment is ready..."
                        sh 'docker ps'

                        // Check that the test directory exists
                        def testDirExists = sh(
                            script: "test -d /home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}",
                            returnStatus: true
                        ) == 0

                        if (!testDirExists) {
                            error "Test directory not found. Ensure environment is properly provisioned first."
                        }

                        // Check that docker-compose file exists if using Docker
                        if (params.PROVISION_DOCKER) {
                            def dockerFileExists = sh(
                                script: "test -f /home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/docker/${params.DOCKER_COMPOSE_FILE_CHOICE}",
                                returnStatus: true
                            ) == 0

                            if (!dockerFileExists) {
                                error "Docker compose file not found. Ensure environment is properly provisioned first."
                            }
                        }
                    }
                }
            }

            stage('Wait until previous build finish') {
                steps {
                    script {
                        echo "Waiting for previous builds to finish..."
                        sh """
                            cd /home/fosqa/resources/tools
                            . /home/fosqa/resources/tools/venv/bin/activate
                            sudo -E PYTHONUNBUFFERED=1 stdbuf -oL -eL ./venv/bin/python3 -u wait_until_aio_pipeline_not_running.py --terminate ${params.TERMINATE_PREVIOUS}
                        """
                    }
                }
            }

            stage('Tests Skipped') {
                when {
                    expression { return params.SKIP_TEST }
                }
                steps {
                    script {
                        echo "⚠️ Tests are being skipped due to SKIP_TEST parameter being enabled"
                        echo "Test groups that would have been executed: ${computedTestGroups}"
                        currentBuild.description = "Tests skipped - SKIP_TEST enabled"
                    }
                }
            }

            stage('Docker Restart') {
                when {
                    expression { return params.PROVISION_DOCKER }
                }
                steps {
                    script {
                        withCredentials([
                            usernamePassword(
                                credentialsId: 'LDAP',
                                usernameVariable: 'SVN_USER',
                                passwordVariable: 'SVN_PASS'
                            )
                        ]) {
                            echo "=== Docker provisioning is ENABLED ==="

                            def composeFile = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/docker/${params.DOCKER_COMPOSE_FILE_CHOICE}"
                            def dockerDir = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/docker"

                            // Use printFile helper to debug docker compose file
                            printFile(
                                filePath: composeFile,
                                fileLabel: "Docker Compose File",
                                baseDir: dockerDir
                            )

                            // Step 3: Login to Docker registry
                            echo "=== Logging in to Docker registry ==="
                            sh "docker login harbor-robot.corp.fortinet.com -u \$SVN_USER -p \$SVN_PASS"

                            // Step 6: Restart Docker Compose stack
                            echo "=== 🚀 Restart Docker Compose ==="
                            sh """
                                cd "${dockerDir}"
                                docker compose -f "${composeFile}" down -v || echo "⚠️ docker compose down encountered issues, continuing..."
                                docker compose -f "${composeFile}" up --build -d
                            """

                            // Step 7: Verify containers are running
                            echo "=== ✅ Verifying Docker containers started successfully ==="
                            sh """
                                cd "${dockerDir}"
                                echo "Running containers:"
                                docker compose -f "${composeFile}" ps

                                # Count running containers
                                RUNNING_COUNT=\$(docker compose -f "${composeFile}" ps --services --filter "status=running" | wc -l)
                                echo "Number of running containers: \$RUNNING_COUNT"

                                if [ "\$RUNNING_COUNT" -eq 0 ]; then
                                    echo "❌ ERROR: No containers are running after docker compose up!"
                                    exit 1
                                fi

                                echo "✅ Docker Compose stack is up and running"
                            """

                            // Step 8: Wait for containers to become healthy (optional, with timeout)
                            echo "=== ⏳ Waiting for containers to become healthy (max 2 minutes) ==="
                            sh """
                                cd "${dockerDir}"

                                timeout 120 bash -c '
                                    while true; do
                                        # Check for any unhealthy containers
                                        UNHEALTHY=\$(docker compose -f "${composeFile}" ps --format json 2>/dev/null | \
                                                    jq -r "select(.Health == \\"unhealthy\\") | .Service" 2>/dev/null | wc -l)

                                        if [ "\$UNHEALTHY" -eq 0 ]; then
                                            echo "✅ All containers are healthy or have no health checks defined"
                                            break
                                        fi

                                        echo "⏳ Waiting for \$UNHEALTHY container(s) to become healthy..."
                                        sleep 5
                                    done
                                ' || echo "⚠️ Timeout waiting for health checks (containers may still be starting)"

                                echo "Final container status:"
                                docker compose -f "${composeFile}" ps
                            """
                        }
                    }
                }
            }

            stage('Run Tests') {
                when {
                    expression { return !params.SKIP_TEST }
                }
                steps {
                    script {
                        withCredentials([
                            usernamePassword(
                                credentialsId: 'LDAP',
                                usernameVariable: 'SVN_USER',
                                passwordVariable: 'SVN_PASS'
                            )
                        ]) {

                            echo "=== Step 1: Local Git update ==="
                            def gitResult = gitUpdate(
                                repoPath: '/home/fosqa/resources/tools',
                                failOnError: false
                            )

                            if (!gitResult.success) {
                                echo "⚠️ Git update had issues: ${gitResult.message}"
                            }

                            echo "=== Step 2: SVN Checkout/Update ==="
                            def svnResult = svnUpdate([
                                LOCAL_LIB_DIR: LOCAL_LIB_DIR,
                                SVN_BRANCH: SVN_BRANCH,
                                FEATURE_NAME: params.FEATURE_NAME,
                                TEST_CASE_FOLDER: params.TEST_CASE_FOLDER,
                                SVN_USER: env.SVN_USER,
                                SVN_PASS: env.SVN_PASS
                            ])

                            echo "SVN ${svnResult.action} completed"
                            echo "Folder: ${svnResult.folderPath}"
                            echo "Reason: ${svnResult.reason}"

                            // Define crash monitor variables (used for start and stop)
                            def crashMonitorPidFile = "/home/fosqa/resources/tools/monitor_fgt_crash.pid"
                            def crashMonitorLogFile = "/home/fosqa/resources/tools/logs/monitor_fgt_crash.log"
                            def crashLogsDir = "/home/fosqa/resources/tools/crashlogs"

                            // Start crash log monitoring in background
                            echo "=== Step 3: Start FGT Crash Log Monitor ==="

                            try {
                                sh """
                                    cd /home/fosqa/resources/tools

                                    # Clean up any previous monitor files
                                    sudo rm -f '${crashMonitorPidFile}' '${crashMonitorLogFile}'
                                    sudo rm -rf ${crashLogsDir}
                                    sudo mkdir -p ${crashLogsDir}
                                    sudo mkdir -p /home/fosqa/resources/tools/logs

                                    # Create log file with proper permissions
                                    sudo touch '${crashMonitorLogFile}'
                                    sudo chmod 777 '${crashMonitorLogFile}'

                                    # Start crash monitor in background (using bash -c to properly handle sudo and nohup)
                                    echo "🔍 Starting FGT crash log monitor..."
                                    sudo bash -c "cd /home/fosqa/resources/tools && nohup ./venv/bin/python3 monitor_fgt_crash.py --devices ALL > '${crashMonitorLogFile}' 2>&1 & echo \\\$! > '${crashMonitorPidFile}'"

                                    # Read the saved PID
                                    MONITOR_PID=\$(sudo cat '${crashMonitorPidFile}' 2>/dev/null || echo "")

                                    if [ -n "\$MONITOR_PID" ]; then
                                        echo "✅ Crash monitor started with PID: \$MONITOR_PID"

                                        # Verify it's running
                                        sleep 2
                                        if sudo ps -p \$MONITOR_PID > /dev/null 2>&1; then
                                            echo "✅ Crash monitor is running"
                                        else
                                            echo "⚠️  Warning: Crash monitor process not found (may have exited)"
                                            echo "Last 10 lines of monitor log:"
                                            sudo tail -n 10 '${crashMonitorLogFile}' 2>/dev/null || echo "Log file not found"
                                        fi
                                    else
                                        echo "⚠️  Warning: Failed to get monitor PID"
                                    fi
                                """
                                echo "✅ Crash log monitoring started successfully"
                            } catch (err) {
                                echo "⚠️  Failed to start crash monitor (non-fatal): ${err}"
                            }

                            // Run tests for each computed test group
                            for (group in computedTestGroups) {
                                echo "get node info by running get_node_info.py"
                                try {
                                    sh """
                                        cd /home/fosqa/resources/tools
                                        sudo ./venv/bin/python3 get_node_info.py --feature ${group}
                                    """
                                    echo "✅ get_node_info.py succeeded"
                                } catch (err) {
                                    echo "⚠️ get_node_info.py failed, but pipeline will continue"
                                }

                                echo "Running tests for test group: ${group}"

                                // Change to the working directory
                                sh "cd /home/fosqa/${LOCAL_LIB_DIR} && sudo chmod -R 777 ."

                                // Debug: Print docker compose file if using Docker
                                if (params.PROVISION_DOCKER) {
                                    def composeFile = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/docker/${params.DOCKER_COMPOSE_FILE_CHOICE}"
                                    def dockerDir = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/docker"

                                    printFile(
                                        filePath: composeFile,
                                        fileLabel: "Docker Compose File",
                                        baseDir: dockerDir
                                    )
                                } else {
                                    echo "=== Docker provisioning disabled, skipping docker compose file check ==="
                                }

                                // Define file paths (absolute paths to avoid dir() issues)
                                def envFile = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_CONFIG_CHOICE}"
                                def groupFile = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${group}"
                                def featureDir = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}"

                                // Use printFile helper for environment file (no dir() block needed)
                                printFile(
                                    filePath: envFile,
                                    fileLabel: "Environment File",
                                    baseDir: featureDir
                                )

                                // Use printFile helper for group file
                                printFile(
                                    filePath: groupFile,
                                    fileLabel: "Group File",
                                    baseDir: featureDir
                                )

                                // Run the actual tests but ensure post-processing always runs
                                // Use nohup + background execution to survive agent disconnections
                                def testErr = null
                                try {
                                    def workDir = "/home/fosqa/${LOCAL_LIB_DIR}"
                                    def pidFile = "${workDir}/autotest_${group.replaceAll('[^a-zA-Z0-9]', '_')}.pid"
                                    def logFile = "${workDir}/autotest_${group.replaceAll('[^a-zA-Z0-9]', '_')}.log"
                                    def exitCodeFile = "${workDir}/autotest_${group.replaceAll('[^a-zA-Z0-9]', '_')}.exit"

                                    // Handle ORIOLE_TASK_PATH: replace {} placeholder with actual RELEASE value
                                    def orioleTaskPath = params.ORIOLE_TASK_PATH
                                    if (orioleTaskPath.contains('{}')) {
                                        orioleTaskPath = orioleTaskPath.replace('{}', params.RELEASE)
                                        echo "Resolved Oriole Task Path: ${orioleTaskPath}"
                                    } else {
                                        echo "Using custom Oriole Task Path: ${orioleTaskPath}"
                                    }

                                    echo "🚀 Starting autotest.py in background (resilient to agent disconnections)"

                                    // Start the process in background with nohup
                                    sh(script: """
                                        cd ${workDir}

                                        # Clean up any previous run files
                                        rm -f '${pidFile}' '${logFile}' '${exitCodeFile}'

                                        # Start autotest.py in background with nohup
                                        nohup bash -c '
                                          source ${workDir}/venv/bin/activate
                                          set -x
                                          python3 autotest.py \
                                            -e "testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_CONFIG_CHOICE}" \
                                            -g "testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${group}" \
                                            -d -s ${params.ORIOLE_SUBMIT_FLAG} \
                                            --task_path "${orioleTaskPath}" \
                                            --non_strict
                                          echo \$? > "${exitCodeFile}"
                                        ' > '${logFile}' 2>&1 &

                                        # Save the background process PID
                                        PID=\$!
                                        echo \$PID > '${pidFile}'
                                        echo ""
                                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                        echo "✅ Started autotest.py with PID: \$PID"
                                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

                                        # Wait a moment for log file to be created
                                        sleep 2
                                    """, label: 'Start autotest.py in background')

                                    // Now tail the log file to show output in real-time
                                    sh(script: """
                                        set +x  # Disable command echoing for monitoring loop
                                        echo ""
                                        echo "📺 Streaming autotest.py output (saved to ${logFile})..."
                                        echo ""

                                        # Tail the log file and continue until process completes
                                        tail -f '${logFile}' 2>/dev/null &
                                        TAIL_PID=\$!

                                        # Monitor silently until exit file appears or process dies
                                        while [ ! -f '${exitCodeFile}' ]; do
                                            if [ -f '${pidFile}' ]; then
                                                PID=\$(cat '${pidFile}' 2>/dev/null)
                                                if ! ps -p \$PID > /dev/null 2>&1; then
                                                    # Process finished, wait a bit for final output
                                                    sleep 3
                                                    break
                                                fi
                                            fi
                                            sleep 5
                                        done

                                        # Stop tailing
                                        kill \$TAIL_PID 2>/dev/null || true

                                        echo ""
                                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                        echo "✅ autotest.py output stream ended"
                                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                    """, label: 'Stream autotest.py output')

                                    // Create a silent polling script that only outputs progress periodically
                                    def pollScript = """
                                        #!/bin/bash
                                        set -euo pipefail

                                        PID_FILE='${pidFile}'
                                        EXIT_FILE='${exitCodeFile}'
                                        LOG_FILE='${logFile}'
                                        MAX_WAIT_SECONDS=7200  # 2 hours
                                        POLL_INTERVAL=10
                                        PROGRESS_INTERVAL=300  # 5 minutes

                                        elapsed=0
                                        last_progress=0

                                        while [ \$elapsed -lt \$MAX_WAIT_SECONDS ]; do
                                            sleep \$POLL_INTERVAL
                                            elapsed=\$((elapsed + POLL_INTERVAL))

                                            # Check if process completed
                                            if [ -f "\$EXIT_FILE" ]; then
                                                echo ""
                                                echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                                echo "✅ Process completed after \$elapsed seconds (\$((elapsed/60)) minutes)"
                                                echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                                exit 0
                                            fi

                                            # Check if process still running
                                            if [ -f "\$PID_FILE" ]; then
                                                PID=\$(cat "\$PID_FILE")
                                                if ! ps -p \$PID > /dev/null 2>&1; then
                                                    # Process died, wait a moment for exit file
                                                    sleep 5
                                                    if [ -f "\$EXIT_FILE" ]; then
                                                        echo ""
                                                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                                        echo "✅ Process completed after \$elapsed seconds (\$((elapsed/60)) minutes)"
                                                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                                        exit 0
                                                    else
                                                        echo ""
                                                        echo "❌ Process \$PID terminated without creating exit code file"
                                                        exit 1
                                                    fi
                                                fi

                                                # Show simple progress periodically (every 5 minutes)
                                                if [ \$((elapsed - last_progress)) -ge \$PROGRESS_INTERVAL ]; then
                                                    echo "⏱️  Still running... \$((elapsed/60)) minutes elapsed (PID: \$PID)"
                                                    last_progress=\$elapsed
                                                fi
                                            fi
                                        done

                                        # Timeout
                                        echo ""
                                        echo "❌ Timeout after \$MAX_WAIT_SECONDS seconds"
                                        if [ -f "\$PID_FILE" ]; then
                                            PID=\$(cat "\$PID_FILE")
                                            if ps -p \$PID > /dev/null 2>&1; then
                                                echo "Killing process \$PID"
                                                kill -9 \$PID 2>/dev/null || true
                                            fi
                                        fi
                                        exit 2
                                    """

                                    // Write polling script and execute it
                                    writeFile file: "${workDir}/poll_autotest.sh", text: pollScript

                                    // Run the polling script (silent monitoring in background)
                                    def pollResult = sh(
                                        script: "bash ${workDir}/poll_autotest.sh",
                                        returnStatus: true,
                                        label: 'Silent monitoring (progress every 5 min)'
                                    )

                                    // Check the poll result
                                    if (pollResult == 2) {
                                        error "autotest.py timed out after 120 minutes"
                                    } else if (pollResult != 0) {
                                        error "autotest.py monitoring failed with status: ${pollResult}"
                                    }

                                    // Check exit code
                                    def exitCode = sh(
                                        returnStdout: true,
                                        script: "cat '${exitCodeFile}' 2>/dev/null || echo '999'"
                                    ).trim().toInteger()

                                    if (exitCode != 0) {
                                        echo "❌ autotest.py failed with exit code: ${exitCode}"
                                        echo "━━━━━━━━━━━━━━━━━━━━ Last 50 lines of log ━━━━━━━━━━━━━━━━━━━━"
                                        sh "tail -n 50 '${logFile}' || true"
                                        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
                                        error "autotest.py failed with exit code: ${exitCode}"
                                    }

                                    echo "✅ autotest.py completed successfully"

                                } catch (err) {
                                    echo "❌ autotest.py failed for group '${group}': ${err}"
                                    testErr = err
                                }

                                // Post-processing: always run, even if tests failed
                                try {
                                    timeout(time: 10, unit: 'MINUTES') {
                                        sh(label: 'inject_autolib_result', script: """
                                        set -eu
                                        cd /home/fosqa/resources/tools
                                        sudo -n /home/fosqa/resources/tools/venv/bin/python3 \\
                                            inject_autolib_result.py -r ${params.RELEASE} -g ${group}
                                        """)
                                    }
                                    echo "✅ inject_autolib_result.py succeeded"
                                } catch (err) {
                                    echo "⚠️ inject_autolib_result.py failed (timeout or error), continuing: ${err}"
                                }

                                try {
                                    timeout(time: 5, unit: 'MINUTES') {
                                        sh(label: 'get_node_info', script: """
                                        set -eu
                                        cd /home/fosqa/resources/tools
                                        sudo -n ./venv/bin/python3 get_node_info.py --feature ${group} --with-exact-report
                                        """)
                                    }
                                    echo "✅ get_node_info.py succeeded"
                                } catch (err) {
                                    echo "⚠️ get_node_info.py failed (timeout or error), continuing: ${err}"
                                }

                                // If tests failed, now fail the stage after post-processing
                                if (testErr) {
                                    error "Failing stage after post-processing. Original error: ${testErr}"
                                }
                            }

                            // Stop crash log monitoring and archive results
                            echo "=== Final Step: Stop FGT Crash Log Monitor ==="
                            // Reuse variables declared earlier: crashMonitorPidFile, crashMonitorLogFile, crashLogsDir

                            try {
                                sh """
                                    cd /home/fosqa/resources/tools

                                    echo "🛑 Stopping crash log monitor..."

                                    # Check if PID file exists
                                    if [ -f '${crashMonitorPidFile}' ]; then
                                        MONITOR_PID=\$(cat '${crashMonitorPidFile}' 2>/dev/null)

                                        if [ -n "\$MONITOR_PID" ] && ps -p \$MONITOR_PID > /dev/null 2>&1; then
                                            echo "Found running crash monitor with PID: \$MONITOR_PID"

                                            # Send SIGTERM for graceful shutdown
                                            sudo kill -TERM \$MONITOR_PID 2>/dev/null || true

                                            # Wait up to 10 seconds for graceful shutdown
                                            for i in {1..10}; do
                                                if ! ps -p \$MONITOR_PID > /dev/null 2>&1; then
                                                    echo "✅ Crash monitor stopped gracefully"
                                                    break
                                                fi
                                                sleep 1
                                            done

                                            # Force kill if still running
                                            if ps -p \$MONITOR_PID > /dev/null 2>&1; then
                                                echo "⚠️  Forcing crash monitor to stop..."
                                                sudo kill -9 \$MONITOR_PID 2>/dev/null || true
                                                sleep 1
                                            fi
                                        else
                                            echo "⚠️  Crash monitor process not running (PID: \$MONITOR_PID)"
                                        fi

                                        rm -f '${crashMonitorPidFile}'
                                    else
                                        echo "⚠️  No crash monitor PID file found"
                                    fi

                                    # Show monitor log tail
                                    echo "━━━━━━━━━━━━━━━━━━━━ Crash Monitor Log (last 20 lines) ━━━━━━━━━━━━━━━━━━━━"
                                    tail -n 20 '${crashMonitorLogFile}' 2>/dev/null || echo "No monitor log found"
                                    echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

                                    # Check crash logs directory
                                    if [ -d '${crashLogsDir}' ]; then
                                        CRASH_COUNT=\$(find '${crashLogsDir}' -type f 2>/dev/null | wc -l)
                                        if [ "\$CRASH_COUNT" -gt 0 ]; then
                                            echo "⚠️  Found \$CRASH_COUNT crash log file(s):"
                                            ls -lh '${crashLogsDir}'
                                        else
                                            echo "✅ No crash logs detected"
                                        fi
                                    else
                                        echo "⚠️  Crash logs directory not found"
                                    fi
                                """

                                // Copy crash logs to workspace for archiving
                                sh """
                                    if [ -d '${crashLogsDir}' ]; then
                                        mkdir -p ${WORKSPACE}/crashlogs
                                        cp -r ${crashLogsDir}/* ${WORKSPACE}/crashlogs/ 2>/dev/null || true

                                        # Also copy the monitor log
                                        cp '${crashMonitorLogFile}' ${WORKSPACE}/crashlogs/ 2>/dev/null || true
                                    fi
                                """

                                echo "✅ Crash log monitoring stopped and archived"
                            } catch (err) {
                                echo "⚠️  Error stopping crash monitor (non-fatal): ${err}"
                            }
                        }
                    }
                }
            }
        }

        post {
            always {
                script {
                    // Check if tests were skipped
                    if (params.SKIP_TEST) {
                        echo "Tests were skipped - no results to archive"
                        currentBuild.result = 'SUCCESS'
                        currentBuild.description = "Tests skipped - SKIP_TEST enabled"
                        return
                    }

                    def outputsDir = "/home/fosqa/${LOCAL_LIB_DIR}/outputs"
                    sh "rm -rf ${WORKSPACE}/test_results"
                    sh "rm -f ${WORKSPACE}/summary_*.html"

                    def archivedFolders = []
                    for (group in computedTestGroups) {
                        def archiveGroup = getArchiveGroupName(group)
                        // Search for folders matching the pattern: *--group--{archiveGroup}*
                        // This will match both exact matches and folders with additional suffixes
                        def folder = sh(
                            returnStdout: true,
                            script: """
                                find ${outputsDir} \
                                    -mindepth 2 -maxdepth 2 -type d \
                                    -name "*--group--${archiveGroup}*" \
                                    -printf '%T@ %p\\n' \
                                  | sort -nr \
                                  | head -1 \
                                  | cut -d' ' -f2-
                            """
                        ).trim()

                        if (!folder) {
                            echo "Warning: No test results folder found for test group '${archiveGroup}' in ${outputsDir}."
                        } else {
                            echo "Found folder for group '${archiveGroup}': ${folder}"
                            archivedFolders << folder
                            sh "mkdir -p ${WORKSPACE}/test_results/${archiveGroup}"
                            // sh "cp -r ${folder} ${WORKSPACE}/test_results/${archiveGroup}/"

                            // Check if summary.html exists before copying
                            def summaryExists = sh(
                                returnStatus: true,
                                script: "test -f ${folder}/summary/summary.html"
                            ) == 0

                            if (summaryExists) {
                                sh "cp ${folder}/summary/summary.html ${WORKSPACE}/summary_${archiveGroup}.html"
                            } else {
                                echo "Warning: summary.html not found in ${folder}/summary/"
                            }
                        }
                    }

                    if (archivedFolders.isEmpty()) {
                        echo "No test results were found for any test group."
                    } else {
                        archiveArtifacts artifacts: "summary_*.html", fingerprint: false
                        publishHTML(target: [
                            reportDir   : ".",
                            reportFiles : "summary_${getArchiveGroupName(computedTestGroups[0])}.html",
                            reportName  : "Test Results Summary for ${getArchiveGroupName(computedTestGroups[0])}"
                        ])
                    }

                    // Archive crash logs if they exist
                    def crashLogsExist = sh(
                        returnStatus: true,
                        script: "test -d ${WORKSPACE}/crashlogs && [ \$(ls -A ${WORKSPACE}/crashlogs 2>/dev/null | wc -l) -gt 0 ]"
                    ) == 0

                    if (crashLogsExist) {
                        echo "📦 Archiving crash logs..."
                        archiveArtifacts artifacts: "crashlogs/**/*", fingerprint: false, allowEmptyArchive: true

                        // Count crash files
                        def crashCount = sh(
                            returnStdout: true,
                            script: "find ${WORKSPACE}/crashlogs -type f -name '*.log' 2>/dev/null | wc -l"
                        ).trim()

                        if (crashCount.toInteger() > 0) {
                            echo "⚠️  WARNING: ${crashCount} crash log file(s) detected!"
                            currentBuild.description = (currentBuild.description ?: "") + " [${crashCount} crashes]"
                        } else {
                            echo "✅ No crash logs found"
                        }
                    } else {
                        echo "ℹ️  No crash logs to archive"
                    }

                    // Clean up the outputs directory
                    sh """
                        cd /home/fosqa/resources/tools
                        make folder_control
                    """
                }

                echo "Test pipeline completed. Check console output for details."
            }

            success {
                script {
                    // Handle skipped tests case
                    if (params.SKIP_TEST) {
                        def nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.html').trim()
                        sendFosqaEmail(
                            to     : sendTo,
                            subject: "SUCCESS (TESTS SKIPPED): ${env.BUILD_DISPLAY_NAME}",
                            body   : """
                            <p>✅ Job <b>${env.BUILD_DISPLAY_NAME}</b> completed successfully at ${new Date()}.</p>
                            <p>⚠️ <b>Tests were skipped</b> due to SKIP_TEST parameter being enabled.</p>
                            <p>Test groups that would have been executed: ${computedTestGroups.join(', ')}</p>
                            <p>🔗 Console output: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                            ${nodeInfo}
                            """
                        )
                        return
                    }

                    def base = "${env.BUILD_URL}artifact/"
                    def summaryLinks = computedTestGroups.collect { group ->
                        def name = getArchiveGroupName(group)
                        "<a href=\"${base}summary_${name}.html\">Summary: ${name}</a>"
                    }.join("<br/>\n")

                    def nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.html').trim()

                    sendFosqaEmail(
                        to     : sendTo,
                        subject: "SUCCESS: ${env.BUILD_DISPLAY_NAME}",
                        body   : """
                        <p>🎉 Good news! Job <b>${env.BUILD_DISPLAY_NAME}</b> completed at ${new Date()}.</p>
                        <p>📄 Test result summaries:</p>
                        ${summaryLinks}
                        <p>🔗 Console output: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                        ${nodeInfo}
                        """
                    )
                }
            }

            failure {
                script {
                    // Handle skipped tests case (though failure is less likely when tests are skipped)
                    if (params.SKIP_TEST) {
                        def nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.html').trim()
                        sendFosqaEmail(
                            to     : sendTo,
                            subject: "FAILURE (TESTS SKIPPED): ${env.BUILD_DISPLAY_NAME}",
                            body   : """
                            <p>❌ Job <b>${env.BUILD_DISPLAY_NAME}</b> failed even though tests were skipped.</p>
                            <p>This indicates an issue with the pipeline setup or environment verification.</p>
                            <p>🔗 Console output: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                            ${nodeInfo}
                            """
                        )
                        return
                    }

                    def base = "${env.BUILD_URL}artifact/"
                    def summaryLinks = computedTestGroups.collect { group ->
                        def name = getArchiveGroupName(group)
                        "<a href=\"${base}summary_${name}.html\">Summary: ${name}</a>"
                    }.join("<br/>\n")
                    def nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.html').trim()
                    sendFosqaEmail(
                        to     : sendTo,
                        subject: "FAILURE: ${env.BUILD_DISPLAY_NAME}",
                        body   : """
                        <p>❌ Job <b>${env.BUILD_DISPLAY_NAME}</b> failed.</p>
                        <p>📄 You can still peek at whatever got archived:</p>
                        ${summaryLinks}
                        <p>🔗 Console output: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                        ${nodeInfo}
                        """
                    )
                }
            }
        }
    }
}
