// Used by http://10.96.227.206:8080/job/fortistack_master_provision_runtest/

// Helper function for debugging parameters
def debugAllParameters() {
    echo "=========================================="
    echo "         PARAMETER DEBUG"
    echo "=========================================="

    // 1. Show all params.* variables (sorted)
    echo "\n--- params.* Variables ---"
    params.sort().each { key, value ->
        echo "params.${key} = '${value}'"
    }

    // 2. Show global variables (if any exist)
    echo "\n--- Global Variables ---"
    def commonGlobals = ['SVN_BRANCH', 'LOCAL_LIB_DIR', 'FGT_TYPE', 'build_name', 'send_to']
    def foundGlobals = []

    commonGlobals.each { varName ->
        try {
            def value = binding.getVariable(varName)
            echo "GLOBAL ${varName} = '${value}'"
            foundGlobals << varName
        } catch (Exception e) {
            // Variable doesn't exist, skip silently
        }
    }

    if (foundGlobals.isEmpty()) {
        echo "No global variables found"
    }

    // 3. Show conflicts (where global differs from params)
    echo "\n--- Conflicts (Global vs params) ---"
    def conflicts = []
    foundGlobals.each { varName ->
        try {
            def globalValue = binding.getVariable(varName)
            def paramValue = params."${varName}"
            if (globalValue?.toString() != paramValue?.toString()) {
                echo "⚠️  ${varName}: params='${paramValue}' vs global='${globalValue}'"
                conflicts << varName
            }
        } catch (Exception e) {
            // Skip if params doesn't have this variable
        }
    }

    if (conflicts.isEmpty()) {
        echo "No conflicts found"
    }

    echo "=========================================="
}

// Helper function to get the archive group name (first two portions).
def getArchiveGroupName(String group) {
    def parts = group.tokenize('.')
    if (parts.size() >= 2) {
        return "${parts[0]}.${parts[1]}"
    } else {
        return group
    }
}

// Updated helper function to compute test groups based on parameters.
def getTestGroups(params) {
    def testGroups = []
    if (params.TEST_GROUPS?.trim()) {
        if (params.TEST_GROUPS instanceof String) {
            def tg = params.TEST_GROUPS.trim()
            // Remove surrounding quotes if present.
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
    // If TEST_GROUPS is empty, then use TEST_GROUP_CHOICE.
    if (!testGroups || testGroups.isEmpty() || testGroups.every { it == "" }) {
        if (params.TEST_GROUP_CHOICE instanceof List) {
            testGroups = params.TEST_GROUP_CHOICE
        } else {
            def choiceValue = params.TEST_GROUP_CHOICE.toString().trim()
            // If the multi-select returns a comma-separated string, split it.
            if (choiceValue.contains(",")) {
                testGroups = choiceValue.split(",").collect { it.trim() }
            } else {
                testGroups = [choiceValue]
            }
        }
    }
    return testGroups
}

// Helper function to parse PARAMS_JSON and expand its keys as global variables.
def expandParamsJson(String jsonStr) {
    try {
        def jsonMap = new groovy.json.JsonSlurper().parseText(jsonStr) as Map
        jsonMap.each { key, value ->
            // Set each key-value pair as a global variable.
            binding.setVariable(key, value)
            echo "Set variable: ${key} = ${value}"
        }
        return jsonMap
    } catch (Exception e) {
        error("Failed to parse PARAMS_JSON: ${e}")
    }
}

// Global variables should be defined at the top level
def computedTestGroups = []  // Global variable to share across stages
def mergedSendTo = ''        // Declare a global variable that can be used in all stages and in the post block.

// Main entry point for the pipeline
def call() {
  // Execute helper functions to set up parameters and expand JSON
  fortistackMasterParameters(exclude:[])
  expandParamsJson(params.PARAMS_JSON)

  pipeline {
    agent { label 'master' }
    options {
      buildDiscarder(logRotator(daysToKeepStr: '14'))
    }
    environment {
        TZ = 'America/Vancouver'
    }

    stages {
      stage('🔍 Debug Config & Mount') {
        steps {
          script {
            // 1) Check that the directory is there
            def cfgDir  = '/var/jenkins_home/feature-configs/fortistack'
            def cfgFile = "${cfgDir}/features.json"

            echo ">>> DEBUG: Does config dir exist? " + sh(returnStatus: true, script: "test -d '${cfgDir}'")  // 0 means yes
            echo ">>> DEBUG: Does config file exist? " + sh(returnStatus: true, script: "test -f '${cfgFile}'")

            // 2) List directory contents
            echo ">>> DEBUG: Listing '${cfgDir}':\n" +
              sh(returnStdout: true, script: "ls -1 '${cfgDir}'").trim()

            // 3) Dump raw file
            echo ">>> DEBUG: Raw JSON content:\n" +
              sh(returnStdout: true, script: "cat '${cfgFile}'").trim()

            // 4) Parse it and inspect
            def raw = readFile(cfgFile)
            def json = new groovy.json.JsonSlurper().parseText(raw)

            echo ">>> DEBUG: Parsed JSON keys (features): ${json.keySet()}"
            if (params.FEATURE_NAME) {
              echo ">>> DEBUG: Entry for FEATURE_NAME='${params.FEATURE_NAME}':\n" +
                   json[params.FEATURE_NAME]?.toString()
            } else {
              echo ">>> DEBUG: FEATURE_NAME not yet set (form rendering time)."
            }
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

      // Update the build display name
      stage('Set Build Display Name') {
        steps {
          script {
            def displayName = "#${currentBuild.number} ${params.NODE_NAME}-${params.FEATURE_NAME}-${computedTestGroups.join(',')}"

            // Add indicators for skipped components
            def skippedComponents = []
            if (params.SKIP_PROVISION) skippedComponents << "NoProvision"
            else {
                if (params.SKIP_PROVISION_TEST_ENV) skippedComponents << "NoTestEnv"
            }
            if (params.SKIP_TEST) skippedComponents << "NoTest"

            if (skippedComponents) {
                displayName += " [${skippedComponents.join('+')}]"
            }

            currentBuild.displayName = displayName
          }
        }
      }

      stage('Debug Parameters') {
        steps {
          script {
            debugAllParameters()
          }
        }
      }

      stage('🔍 SVN_BRANCH Value Check') {
          steps {
              script {
                  echo "--- Verifying SVN_BRANCH before triggering downstream jobs ---"
                  echo "[INFO] The default value for SVN_BRANCH is 'v760'."
                  echo "[DEBUG] Current value of 'params.SVN_BRANCH': '${params.SVN_BRANCH}'"
                  echo "[DEBUG] Checking if PARAMS_JSON contains an override for SVN_BRANCH..."

                  def paramsMap = new groovy.json.JsonSlurper().parseText(params.PARAMS_JSON)
                  if (paramsMap.containsKey('SVN_BRANCH')) {
                      echo "[WARNING] PARAMS_JSON contains 'SVN_BRANCH: \"${paramsMap.SVN_BRANCH}\"'. This value will be used by downstream jobs that use global variables."
                  } else {
                      echo "[INFO] PARAMS_JSON does not contain an override for SVN_BRANCH."
                  }
                  echo "[DEBUG] Full PARAMS_JSON content:"
                  echo "${params.PARAMS_JSON}"
                  echo "------------------------------------------------------------"
              }
          }
      }

      stage('Merge Email Parameters') {
        steps {
          script {
            // Merge SEND_TO (feature-derived) with ADDITIONAL_EMAIL.
            def featureEmail = params.SEND_TO?.trim() ?: ''
            def extraEmails = params.ADDITIONAL_EMAIL ? params.ADDITIONAL_EMAIL.tokenize(',').collect { it.trim() }.findAll { it } : []
            def mergedSendToList = []
            if (featureEmail) {
              mergedSendToList << featureEmail
            }
            mergedSendToList.addAll(extraEmails)
            mergedSendTo = mergedSendToList.join(',')
            echo "Merged SEND_TO: ${mergedSendTo}"
          }
        }
      }

      stage('Trigger Provision FGT Pipeline') {
        // This stage runs on the designated node.
        agent { label "${params.NODE_NAME.trim()}" }
        when {
          expression { return !params.SKIP_PROVISION }
        }
        steps {
          script {
            def paramsMap = new groovy.json.JsonSlurper()
                              .parseText(params.PARAMS_JSON)
                              .collectEntries { k, v -> [k, v?.toString()?.trim()] }
            def provisionParams = [
              string(name: 'NODE_NAME', value: params.NODE_NAME.trim()),
              string(name: 'RELEASE', value: params.RELEASE.trim()),
              string(name: 'BUILD_NUMBER', value: params.BUILD_NUMBER.trim()),
              string(name: 'FGT_TYPE', value: params.FGT_TYPE.trim()),
              string(name: 'TERMINATE_PREVIOUS', value: params.TERMINATE_PREVIOUS?.trim() ?: 'false'),
            ]
            echo "Triggering fortistack_provision_fgts pipeline with parameters: ${provisionParams}"
            build job: 'fortistack_provision_fgts', parameters: provisionParams, wait: true
          }
        }
      }

      stage('Trigger Provision TEST ENV Pipeline') {
        // This stage runs on the designated node
        agent { label "${params.NODE_NAME.trim()}" }
        when {
          // Only run if not skipping provision AND not skipping test env provision
          expression { return !params.SKIP_PROVISION_TEST_ENV }
        }
        steps {
          script {
            def paramsMap = new groovy.json.JsonSlurper()
                              .parseText(params.PARAMS_JSON)
                              .collectEntries { k, v -> [k, v?.toString()?.trim()] }

            echo "Using computed test groups: ${computedTestGroups}"

            // Build parameters for provision pipeline
            def provisionParams = [
              string(name: 'RELEASE', value: params.RELEASE.trim()),
              string(name: 'BUILD_NUMBER', value: params.BUILD_NUMBER.trim()),
              string(name: 'NODE_NAME', value: params.NODE_NAME.trim()),
              string(name: 'LOCAL_LIB_DIR', value: paramsMap.LOCAL_LIB_DIR?.trim()),
              string(name: 'SVN_BRANCH', value: params.SVN_BRANCH?.trim()),
              string(name: 'AUTOLIB_BRANCH', value: params.AUTOLIB_BRANCH?.trim()),
              string(name: 'FEATURE_NAME', value: params.FEATURE_NAME?.trim()),
              string(name: 'TEST_CASE_FOLDER', value: params.TEST_CASE_FOLDER?.trim()),
              string(name: 'TEST_CONFIG_CHOICE', value: params.TEST_CONFIG_CHOICE?.trim()),
              string(name: 'TEST_GROUP_CHOICE', value: params.TEST_GROUP_CHOICE?.trim()),
              string(name: 'TEST_GROUPS', value: groovy.json.JsonOutput.toJson(computedTestGroups)),
              string(name: 'DOCKER_COMPOSE_FILE_CHOICE', value: params.DOCKER_COMPOSE_FILE_CHOICE?.trim()),
              booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', value: params.FORCE_UPDATE_DOCKER_FILE),
              booleanParam(name: 'PROVISION_VMPC', value: params.PROVISION_VMPC),
              string(name: 'VMPC_NAMES', value: params.VMPC_NAMES?.trim() ?: ''),
              booleanParam(name: 'PROVISION_DOCKER', value: params.PROVISION_DOCKER),
              string(name: 'PARAMS_JSON', value: params.PARAMS_JSON),
              string(name: 'ORIOLE_SUBMIT_FLAG', value: params.ORIOLE_SUBMIT_FLAG?.trim() ?: 'all'),
              string(name: 'SEND_TO', value: mergedSendTo),
              string(name: 'TERMINATE_PREVIOUS', value: params.TERMINATE_PREVIOUS?.trim() ?: 'false')
            ]
            echo "Triggering fortistackProvisionTestEnv pipeline with parameters: ${provisionParams}"
            def provisionResult = build job: 'fortistackProvisionTestEnv', parameters: provisionParams, wait: true, propagate: false

            if (provisionResult.getResult() != "SUCCESS") {
              error "Environment provisioning failed with result: ${provisionResult.getResult()}"
            }
          }
        }
      }

      stage('Trigger Test Pipeline') {
        // This stage runs on the designated node and iterates over each test group sequentially.
        agent { label "${params.NODE_NAME.trim()}" }
        when {
          expression { return !params.SKIP_TEST }
        }
        steps {
          script {
            def paramsMap = new groovy.json.JsonSlurper()
                              .parseText(params.PARAMS_JSON)
                              .collectEntries { k, v -> [k, v?.toString()?.trim()] }

            echo "Using computed test groups: ${computedTestGroups}"

            // Track overall result.
            def overallSuccess = true
            def groupResults = [:]

            // Loop through each test group.
            for (group in computedTestGroups) {
                echo "--- Preparing fortistackRunTests for group: ${group} ---"
                echo "[DEBUG] Passing 'SVN_BRANCH' with value: '${params.SVN_BRANCH}'"
                def testParams = [
                    string(name: 'TERMINATE_PREVIOUS', value: params.TERMINATE_PREVIOUS?.trim() ?: 'false'),
                    string(name: 'RELEASE', value: params.RELEASE.trim()),
                    string(name: 'BUILD_NUMBER', value: params.BUILD_NUMBER.trim()),
                    string(name: 'NODE_NAME', value: params.NODE_NAME.trim()),
                    string(name: 'LOCAL_LIB_DIR', value: paramsMap.LOCAL_LIB_DIR?.trim()),
                    string(name: 'SVN_BRANCH', value: params.SVN_BRANCH?.trim()),
                    string(name: 'AUTOLIB_BRANCH', value: params.AUTOLIB_BRANCH?.trim()),
                    string(name: 'FEATURE_NAME', value: params.FEATURE_NAME?.trim()),
                    string(name: 'TEST_CASE_FOLDER', value: params.TEST_CASE_FOLDER?.trim()),
                    string(name: 'TEST_CONFIG_CHOICE', value: params.TEST_CONFIG_CHOICE?.trim()),
                    string(name: 'TEST_GROUP_CHOICE', value: group),
                    string(name: 'DOCKER_COMPOSE_FILE_CHOICE', value: params.DOCKER_COMPOSE_FILE_CHOICE?.trim()),
                    booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', value: params.FORCE_UPDATE_DOCKER_FILE),
                    booleanParam(name: 'PROVISION_VMPC',   value: params.PROVISION_VMPC),
                    string(      name: 'VMPC_NAMES',       value: params.VMPC_NAMES?.trim() ?: ''),
                    booleanParam(name: 'PROVISION_DOCKER', value: params.PROVISION_DOCKER),
                    string(name: 'PARAMS_JSON', value: params.PARAMS_JSON),
                    string(name: 'ORIOLE_SUBMIT_FLAG', value: params.ORIOLE_SUBMIT_FLAG?.trim() ?: 'all'),
                    string(name: 'ORIOLE_TASK_PATH', value: params.ORIOLE_TASK_PATH?.trim() ?: 'ORIOLE_TASK_PATH not set!'),
                    string(name: 'SEND_TO', value: mergedSendTo)
                ]
                echo "Triggering fortistackRunTests pipeline for test group '${group}' with parameters: ${testParams}"
                def result = build job: 'fortistackRunTests', parameters: testParams, wait: true, propagate: false
                groupResults[group] = result.getResult()
                if (result.getResult() != "SUCCESS") {
                    echo "Test pipeline for group '${group}' failed with result: ${result.getResult()}"
                    overallSuccess = false
                } else {
                    echo "Test pipeline for group '${group}' succeeded."
                }
            }
            echo "Test pipeline group results: ${groupResults}"
            if (!overallSuccess) {
                error("One or more test pipelines failed: ${groupResults}")
            }
          }
        }
      }
    }

    post {
      always {
        node("${params.NODE_NAME}") {
          script {
            try {
              // Only process and archive test results if tests were actually run
              if (!params.SKIP_TEST) {
                def outputsDir = "/home/fosqa/${LOCAL_LIB_DIR}/outputs"
                // Clean up previous archiving work.
                sh "hostname"
                sh "rm -f ${WORKSPACE}/summary_*.html"

                // Debug: list all directories under outputsDir
                // def listAll = sh(
                //   returnStdout: true,
                //   script: "find '${outputsDir}' -mindepth 2 -maxdepth 2 -type d"
                // ).trim()
                // echo "All directories under ${outputsDir}:\n${listAll}"

                def archivedFolders = []
                for (group in computedTestGroups) {
                  def archiveGroup = getArchiveGroupName(group)
                  // Build the base find command.
                  def baseCmd = "find '${outputsDir}' -mindepth 2 -maxdepth 2 -type d -name '*--group--${archiveGroup}*' -printf '%T@ %p\\n'"
                  echo "Base find command for group '${archiveGroup}': ${baseCmd}"

                  // Execute the base find command and capture raw output.
                  def rawOutput = sh(
                    returnStdout: true,
                    script: "bash -c \"${baseCmd}\""
                  ).trim()
                  echo "Raw output for group '${archiveGroup}':\n${rawOutput}"

                  // Sort the output and pick the most recent entry.
                  def sortedOutput = sh(
                    returnStdout: true,
                    script: "bash -c \"echo '${rawOutput}' | sort -nr\""
                  ).trim()
                  echo "Sorted output for group '${archiveGroup}':\n${sortedOutput}"

                  def folder = sh(
                    returnStdout: true,
                    script: "bash -c \"echo '${sortedOutput}' | head -1 | cut -d' ' -f2-\""
                  ).trim()
                  echo "Final folder for group '${archiveGroup}': '${folder}'"

                  if (!folder) {
                    echo "Warning: No test results folder found for test group '${archiveGroup}' in ${outputsDir}."
                  } else {
                    echo "Found folder for group '${archiveGroup}': ${folder}"
                    archivedFolders << folder
                    try {
                      def cpCommand = "cp ${folder}/summary/summary.html ${WORKSPACE}/summary_${archiveGroup}.html"
                      echo "Executing copy command: ${cpCommand}"
                      sh cpCommand
                    } catch (err) {
                      echo "Error copying summary for group '${archiveGroup}': ${err}"
                    }
                  }
                  // Sleep for 1 second before next iteration.
                  sleep time: 1, unit: 'SECONDS'
                }

                if (archivedFolders.isEmpty()) {
                  echo "No test results were found for any test group."
                } else {
                  echo "Archiving artifacts from folders: ${archivedFolders}"
                  archiveArtifacts artifacts: "test_results/**, summary_*.html", fingerprint: false
                }
              } else {
                echo "Skipping test result archiving since SKIP_TEST is true - no test reports were generated."
              }
            } catch (err) {
              echo "Error in post block: ${err}"
            }
          }
        }

        echo "Master Pipeline completed."
      }

      success {
        script {
            // Create summary of what was executed
            def executedComponents = []
            if (!params.SKIP_PROVISION) {
                executedComponents << "Provision FGTs"
                if (!params.SKIP_PROVISION_TEST_ENV) {
                    executedComponents << "Provision Test Environment"
                }
            }
            if (!params.SKIP_TEST) executedComponents << "Run Tests"

            def componentSummary = executedComponents.isEmpty() ?
                "<p>No components were executed.</p>" :
                "<p>Executed components: <b>${executedComponents.join(', ')}</b></p>"

            // Create summary links for test results
            def summaryLinks = ""
            if (!params.SKIP_TEST) {
                def base = "${env.BUILD_URL}artifact/"
                summaryLinks = "<p>📄 Test result summaries:</p><ul>"
                computedTestGroups.each { group ->
                    def name = getArchiveGroupName(group)
                    summaryLinks += "<li><a href=\"${base}summary_${name}.html\">Summary: ${name}</a></li>"
                }
                summaryLinks += "</ul>"
            }

            sendFosqaEmail(
                to:       "yzhengfeng@fortinet.com",
                subject:  "${env.BUILD_DISPLAY_NAME} Succeeded",
                body:     """
                <p>🎉 Good news! Job <b>${env.JOB_NAME}</b> completed at ${new Date()}</p>
                ${componentSummary}
                <p>Feature: <b>${params.FEATURE_NAME}</b></p>
                <p>Test groups: <b>${computedTestGroups.join(', ')}</b></p>
                ${summaryLinks}
                <p>🔗 Console output: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                """
            )
        }
      }

      failure {
        script {
            // Create summary of what was executed
            def executedComponents = []
            if (!params.SKIP_PROVISION) {
                executedComponents << "Provision FGTs"
                if (!params.SKIP_PROVISION_TEST_ENV) {
                    executedComponents << "Provision Test Environment"
                }
            }
            if (!params.SKIP_TEST) executedComponents << "Run Tests"

            def componentSummary = executedComponents.isEmpty() ?
                "<p>No components were executed.</p>" :
                "<p>Executed components: <b>${executedComponents.join(', ')}</b></p>"

            // Create summary links for test results (if any)
            def summaryLinks = ""
            if (!params.SKIP_TEST) {
                def base = "${env.BUILD_URL}artifact/"
                summaryLinks = "<p>📄 Test result summaries (may be incomplete):</p><ul>"
                computedTestGroups.each { group ->
                    def name = getArchiveGroupName(group)
                    summaryLinks += "<li><a href=\"${base}summary_${name}.html\">Summary: ${name}</a></li>"
                }
                summaryLinks += "</ul>"
            }

            sendFosqaEmail(
                to:      "yzhengfeng@fortinet.com",
                subject: "${env.BUILD_DISPLAY_NAME} FAILED",
                body:    """
                <p>❌ Job <b>${env.BUILD_DISPLAY_NAME}</b> failed.</p>
                ${componentSummary}
                <p>Feature: <b>${params.FEATURE_NAME}</b></p>
                <p>Test groups: <b>${computedTestGroups.join(', ')}</b></p>
                ${summaryLinks}
                <p>🔗 Console output: <a href="${env.BUILD_URL}">${env.BUILD_URL}</a></p>
                """
            )
        }
      }
    }
  }
}
