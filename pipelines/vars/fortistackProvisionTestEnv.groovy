// fortistackProvisionTestEnv.groovy
// Handles environment preparation, provisioning, and setup

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
def mergedSendTo = ''        // Global variable for merged email recipients

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
            buildDiscarder(logRotator(daysToKeepStr: '14'))
        }

        environment {
            TZ = 'America/Vancouver'
            PROVISION_COMPLETED = 'false' // Track if provisioning succeeded
        }

        stages {
            stage('GIT Pull') {
                steps {
                    script {
                        echo "=== Local Git Update ==="
                        gitUpdate(
                            repoPath: '/home/fosqa/resources/tools',
                            failOnError: false
                        )
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

            stage('Initialize Test Groups') {
                steps {
                    script {
                        computedTestGroups = getTestGroups(params)
                        echo "Computed test groups: ${computedTestGroups}"
                    }
                }
            }

            stage('Merge Email Parameters') {
                steps {
                    script {
                        // Merge email recipients: start with SEND_TO parameter
                        def sendToEmails = []
                        if (params.SEND_TO?.trim()) {
                            sendToEmails.addAll(params.SEND_TO.split(',').collect { it.trim() }.findAll { it })
                        }

                        // Add additional emails from features.json
                        try {
                            def featuresJsonPath = '/home/fosqa/jenkins-master/feature-configs/fortistack/features.json'
                            if (fileExists(featuresJsonPath)) {
                                def featuresJson = readJSON file: featuresJsonPath
                                def featureConfig = featuresJson[params.FEATURE_NAME]

                                if (featureConfig && featureConfig.email) {
                                    echo "Found additional emails in features.json: ${featureConfig.email}"
                                    def additionalEmails = featureConfig.email.split(',').collect { it.trim() }.findAll { it }
                                    sendToEmails.addAll(additionalEmails)
                                }
                            }
                        } catch (Exception e) {
                            echo "Warning: Could not read additional emails from features.json: ${e.getMessage()}"
                        }

                        // Add ADDITIONAL_EMAIL parameter if provided
                        if (params.ADDITIONAL_EMAIL?.trim()) {
                            def extraEmails = params.ADDITIONAL_EMAIL.split(',').collect { it.trim() }.findAll { it }
                            sendToEmails.addAll(extraEmails)
                        }

                        // Remove duplicates and create final merged email list
                        mergedSendTo = sendToEmails.unique().join(',')

                        // Fallback to default if no emails found
                        if (!mergedSendTo?.trim()) {
                            mergedSendTo = "yzhengfeng@fortinet.com"
                        }

                        echo "Merged SEND_TO: ${mergedSendTo}"
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

            stage('Check Docker and KVM domains') {
                steps {
                    echo "Checking Docker environment..."
                    sh 'docker ps'
                    sh 'docker-compose --version'
                    echo "Checking KVM domains..."
                    sh 'virsh -c qemu:///system list --all'
                }
            }

            stage('Cleanup VMPC Domains if no VMPCs needed') {
                when {
                    expression { return params.PROVISION_VMPC == false }
                }
                steps {
                    script {
                        echo "PROVISION_VMPC is false - cleaning up existing VMPC domains..."

                        sh """
                            cd /home/fosqa/resources/tools

                            # Check if any VMPC domains exist (dry run first)
                            echo "=== Checking for existing VMPC domains ==="
                            sudo /home/fosqa/resources/tools/venv/bin/python undefine_kvm_domain.py \
                                --domain-name VMPC \
                                --dry-run || echo "No VMPC domains found or check failed"

                            # Clean up VMPC domains if any exist
                            echo "=== Cleaning up VMPC domains ==="
                            sudo /home/fosqa/resources/tools/venv/bin/python undefine_kvm_domain.py \
                                --domain-name VMPC || {
                                echo "⚠️ VMPC cleanup encountered an issue, but continuing..."
                            }

                            echo "✅ VMPC domain cleanup completed (or no domains found)"
                        """
                    }
                }
            }

            stage('Provision VMPCs') {
                when {
                    expression { return params.PROVISION_VMPC == true }
                }
                steps {
                    script {
                        if (!params.VMPC_NAMES?.trim()) {
                            error "PROVISION_VMPC is true but VMPC_NAMES is empty. Please specify one or more VMPC names."
                        }

                        // Pass the VMPC_NAMES string directly to the python script
                        echo "Provisioning VMPCs: ${params.VMPC_NAMES}"
                        sh """
                            cd /home/fosqa/resources/tools
                            sudo make provision_kvm_pc vmpc="${params.VMPC_NAMES}" feature="${params.FEATURE_NAME}"
                        """
                    }
                }
            }

            stage('Environment Preparation') {
                steps {
                    script {
                        echo "--- SVN_BRANCH Debugging in fortistackProvisionTestEnv ---"
                        echo "[DEBUG] Value received in 'params.SVN_BRANCH': '${params.SVN_BRANCH}'"
                        try {
                            echo "[DEBUG] Value of global variable 'SVN_BRANCH' that will be used for checkout: '${SVN_BRANCH}'"
                            if (SVN_BRANCH != params.SVN_BRANCH) {
                                echo "[WARNING] Global 'SVN_BRANCH' ('${SVN_BRANCH}') differs from 'params.SVN_BRANCH' ('${params.SVN_BRANCH}'). The global variable from PARAMS_JSON takes precedence here."
                            }
                        } catch (MissingPropertyException e) {
                            echo "[INFO] Global variable 'SVN_BRANCH' was not set by PARAMS_JSON. The script will rely on 'params.SVN_BRANCH'."
                        }
                        echo "--------------------------------------------------------"

                        withCredentials([
                            usernamePassword(
                                credentialsId: 'LDAP',
                                usernameVariable: 'SVN_USER',
                                passwordVariable: 'SVN_PASS'
                            )
                        ]) {
                            echo "=== Step 1: Local Git update ==="
                            def innerGitCmd = """
                                sudo -u fosqa bash -c '
                                  cd /home/fosqa/resources/tools && \
                                  if [ -n "\$(git status --porcelain)" ]; then \
                                    git stash push -m "temporary stash"; \
                                  fi; \
                                  git pull; \
                                  if git stash list | grep -q "temporary stash"; then \
                                    git stash pop; \
                                  fi
                                '
                            """
                            try {
                                sh innerGitCmd
                            } catch (Exception e) {
                                echo "Local git pull failed: ${e.getMessage()}. Continuing without updating."
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

            stage('Docker Setup') {
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
                            // Step 3: Update Docker file, if needed
                            def forceArg = params.FORCE_UPDATE_DOCKER_FILE ? "--force" : ""
                            sh """
                                cd /home/fosqa/resources/tools
                                sudo /home/fosqa/resources/tools/venv/bin/python \
                                  get_dockerfile_from_cdn.py --feature ${FEATURE_NAME} ${forceArg}
                            """

                            // Step 4: Create Docker file soft link
                            sh """
                                cd /home/fosqa/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}
                                sudo rm -f docker_filesys
                                sudo ln -s /home/fosqa/docker_filesys/${params.FEATURE_NAME} docker_filesys
                            """

                            def composeFile = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/docker/${params.DOCKER_COMPOSE_FILE_CHOICE}"
                            def dockerDir = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/docker"

                            // Use printFile helper to debug docker compose file
                            printFile(
                                filePath: composeFile,
                                fileLabel: "Docker Compose File",
                                baseDir: dockerDir
                            )

                            sh """
                                docker login harbor-robot.corp.fortinet.com -u \$SVN_USER -p \$SVN_PASS
                                docker ps -aq | xargs -r docker rm -f
                                cd /home/fosqa/resources/tools
                                make setup_docker_network_and_cleanup_telnet_ports

                                docker compose \
                                  -f "${composeFile}" \
                                  up --build -d
                            """
                        }
                    }
                }
            }

            stage('Update Node Info') {
                steps {
                    script {
                        try {
                            // Check if we have test groups before using them
                            def featureArg = ""
                            if (computedTestGroups && computedTestGroups.size() > 0) {
                                featureArg = "--feature ${computedTestGroups[0]}"
                            } else if (params.FEATURE_NAME) {
                                featureArg = "--feature ${params.FEATURE_NAME}"
                            }

                            echo "Running get_node_info.py with feature argument: '${featureArg}'"

                            sh """
                                cd /home/fosqa/resources/tools
                                sudo ./venv/bin/python3 get_node_info.py ${featureArg}
                            """
                            echo "✅ get_node_info.py succeeded"

                        } catch (err) {
                            echo "⚠️ get_node_info.py failed: ${err.getMessage()}, but pipeline will continue"
                        }

                        // ALWAYS set provisioning completed flag, regardless of get_node_info.py result
                        env.PROVISION_COMPLETED = 'true'
                        echo "✅ Provisioning marked as completed"
                    }
                }
            }
        }

        post {

            success {
                script {
                    def nodeInfo = ""
                    try {
                        nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.html').trim()
                    } catch (e) {
                        try {
                            def nodeInfoTxt = readFile('/home/fosqa/KVM/node_info_summary.txt').trim()
                            nodeInfo = "<pre style=\"font-family:monospace; white-space:pre-wrap;\">${nodeInfoTxt}</pre>"
                        } catch (e2) {
                            nodeInfo = "<p>Node information not available</p>"
                        }
                    }

                    def dispName = currentBuild.displayName ?: "${env.BUILD_NUMBER}"

                    // Debug logging
                    echo "DEBUG: Pipeline succeeded - sending success email"
                    echo "DEBUG: computedTestGroups = ${computedTestGroups}"
                    echo "DEBUG: mergedSendTo = ${mergedSendTo}"

                    // We're in success block, so provisioning must have completed successfully
                    echo "Sending SUCCESS email to: ${mergedSendTo}"
                    sendFosqaEmail(
                        to     : mergedSendTo,
                        subject: "Environment Provisioned: ${dispName}",
                        body   : """
                        <h2>Build: ${dispName}</h2>
                        <p>✅ Test environment has been successfully provisioned.</p>
                        <p><b>Feature:</b> ${params.FEATURE_NAME}</p>
                        <p><b>Test groups:</b> ${computedTestGroups.join(', ')}</p>
                        <p>🔗 <a href="${env.BUILD_URL}">Console output</a></p>
                        <h3>Environment Details</h3>
                        ${nodeInfo}
                        """
                    )
                }
            }

            failure {
                script {
                    // Read node info
                    def nodeInfo = ""
                    try {
                        nodeInfo = readFile('/home/fosqa/KVM/node_info_summary.html').trim()
                    } catch (e) {
                        try {
                            def nodeInfoTxt = readFile('/home/fosqa/KVM/node_info_summary.txt').trim()
                            nodeInfo = "<pre style=\"font-family:monospace; white-space:pre-wrap;\">${nodeInfoTxt}</pre>"
                        } catch (e2) {
                            nodeInfo = "<p>Node information not available</p>"
                        }
                    }

                    def dispName = currentBuild.displayName ?: "${env.BUILD_NUMBER}"

                    echo "Sending FAILURE email to: ${mergedSendTo}"
                    sendFosqaEmail(
                        to     : mergedSendTo,
                        subject: "PROVISION FAILED: ${dispName}",
                        body   : """
                        <h2>Build: ${dispName}</h2>
                        <p>❌ Environment provisioning failed.</p>
                        <p><b>Feature:</b> ${params.FEATURE_NAME}</p>
                        <p><b>Test groups:</b> ${computedTestGroups.join(', ')}</p>
                        <p style="color:red;">Build failed. Check the <a href="${env.BUILD_URL}">console log</a>.</p>
                        <h3>Environment Details (Partial)</h3>
                        ${nodeInfo}
                        """
                    )
                }
            }
        }
    }
}
