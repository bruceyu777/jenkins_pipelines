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
            buildDiscarder(logRotator(numToKeepStr: '100'))
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
                                def testErr = null
                                try {
                                    sh """
                                        cd /home/fosqa/${LOCAL_LIB_DIR}
                                        . /home/fosqa/${LOCAL_LIB_DIR}/venv/bin/activate
                                        set -euxo pipefail
                                        python3 autotest.py \
                                          -e "testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_CONFIG_CHOICE}" \
                                          -g "testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${group}" \
                                          -d -s ${params.ORIOLE_SUBMIT_FLAG}
                                    """
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
                        def folder = sh(
                            returnStdout: true,
                            script: """
                                find ${outputsDir} \
                                    -mindepth 2 -maxdepth 2 -type d \
                                    -name "*--group--${archiveGroup}" \
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
                            sh "cp -r ${folder} ${WORKSPACE}/test_results/${archiveGroup}/"
                            sh "cp ${folder}/summary/summary.html ${WORKSPACE}/summary_${archiveGroup}.html"
                        }
                    }

                    if (archivedFolders.isEmpty()) {
                        echo "No test results were found for any test group."
                    } else {
                        archiveArtifacts artifacts: "test_results/**, summary_*.html", fingerprint: false
                        publishHTML(target: [
                            reportDir   : ".",
                            reportFiles : "summary_${getArchiveGroupName(computedTestGroups[0])}.html",
                            reportName  : "Test Results Summary for ${getArchiveGroupName(computedTestGroups[0])}"
                        ])
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
