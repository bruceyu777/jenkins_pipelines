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
    fortistackMasterParameters(exclude: ['FGT_TYPE','SKIP_PROVISION'])

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

            stage('Run Tests') {
                steps {
                    script {
                        withCredentials([
                            usernamePassword(
                                credentialsId: 'LDAP',
                                usernameVariable: 'SVN_USER',
                                passwordVariable: 'SVN_PASS'
                            )
                        ]) {
                            // Run tests for each computed test group
                            for (group in computedTestGroups) {
                                echo "Running tests for test group: ${group}"
                                sh """
                                    cd /home/fosqa/${LOCAL_LIB_DIR}
                                    sudo chmod -R 777 .
                                    . /home/fosqa/${LOCAL_LIB_DIR}/venv/bin/activate
                                    python3 autotest.py \
                                      -e testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_CONFIG_CHOICE} \
                                      -g testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${group} \
                                      -d -s ${params.ORIOLE_SUBMIT_FLAG}
                                """
                                // inject inside a try/catch so failures are non-fatal
                                try {
                                    sh """
                                        cd /home/fosqa/resources/tools
                                        sudo /home/fosqa/resources/tools/venv/bin/python3 \
                                        inject_autolib_result.py \
                                            -r ${params.RELEASE} \
                                            -g ${group}
                                    """
                                    echo "✅ inject_autolib_result.py succeeded"
                                } catch (err) {
                                    echo "⚠️ inject_autolib_result.py failed, but pipeline will continue"
                                }

                                // update node info by running get_node_info.py
                                try {
                                    sh """
                                        cd /home/fosqa/resources/tools
                                        sudo ./venv/bin/python3 get_node_info.py --feature ${group}
                                    """
                                    echo "✅ get_node_info.py succeeded"
                                } catch (err) {
                                    echo "⚠️ get_node_info.py failed, but pipeline will continue"
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
