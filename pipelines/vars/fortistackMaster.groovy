// Used by http://10.96.227.206:8080/job/fortistack_master_provision_runtest/
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

def computedTestGroups = []  // Global variable to share across stages

def call() {
  fortistackMasterParameters(exclude:[])
  expandParamsJson(params.PARAMS_JSON)

  pipeline {
    // ────────────────────────────────────────────────────────────────────
    // Tell the Build Pipeline plugin that *this* job has two downstream
    // jobs, shown in the next stage of the pipeline view:
    //   1) fortistack_provision_fgts
    //   2) fortistack_runtest
    // ────────────────────────────────────────────────────────────────────
    properties([
      [
        $class: 'BuildTrigger',
        configs: [
          [
            $class:                 'BuildTriggerConfig',
            projects:               'fortistack_provision_fgts,fortistack_runtest',
            condition:              'SUCCESS',
            triggerWithNoParameters: true
          ]
        ]
      ]
    ])
    agent { label 'master' }
    options {
      buildDiscarder(logRotator(numToKeepStr: '100'))
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
      
      stage('Set Build Display Name') {
        steps {
          script {
            currentBuild.displayName = "#${currentBuild.number} ${params.NODE_NAME}-${params.FEATURE_NAME}-${computedTestGroups.join(',')}"
          }
        }
      }
      
      stage('Debug Parameters') {
        steps {
          script {
            echo "=== Debug: Printing All Parameters ==="
            params.each { key, value -> echo "${key} = ${value}" }
          }
        }
      }
      
      stage('Trigger Provision Pipeline') {
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
              string(name: 'FGT_TYPE', value: params.FGT_TYPE.trim())
            ]
            echo "Triggering fortistack_provision_fgts pipeline with parameters: ${provisionParams}"
            build job: 'fortistack_provision_fgts', parameters: provisionParams, wait: true
          }
        }
      }
      
      stage('Trigger Test Pipeline') {
        // This stage runs on the designated node and iterates over each test group sequentially.
        agent { label "${params.NODE_NAME}" }
        when {
          expression { return !params.SKIP_TEST }
        }
        steps {
          script {
            def paramsMap = new groovy.json.JsonSlurper()
                             .parseText(params.PARAMS_JSON)
                             .collectEntries { k, v -> [k, v] }
                             
            echo "Using computed test groups: ${computedTestGroups}"
            
            // Track overall result.
            def overallSuccess = true
            def groupResults = [:]
            
            // Loop through each test group.
            for (group in computedTestGroups) {
              def testParams = [
                string(name: 'RELEASE', value: params.RELEASE.trim()),
                string(name: 'BUILD_NUMBER', value: params.BUILD_NUMBER.trim()),
                string(name: 'NODE_NAME', value: params.NODE_NAME.trim()),
                string(name: 'LOCAL_LIB_DIR', value: paramsMap.LOCAL_LIB_DIR?.trim()),
                string(name: 'SVN_BRANCH', value: params.SVN_BRANCH?.trim()),
                string(name: 'FEATURE_NAME', value: params.FEATURE_NAME?.trim()),
                string(name: 'TEST_CASE_FOLDER', value: params.TEST_CASE_FOLDER?.trim()),
                string(name: 'TEST_CONFIG_CHOICE', value: params.TEST_CONFIG_CHOICE?.trim()),
                string(name: 'TEST_GROUP_CHOICE', value: group),
                string(name: 'DOCKER_COMPOSE_FILE_CHOICE', value: params.DOCKER_COMPOSE_FILE_CHOICE?.trim()),
                booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', value: params.FORCE_UPDATE_DOCKER_FILE),
                booleanParam(name: 'PROVISION_VMPC',   value: params.PROVISION_VMPC    ),
                string(      name: 'VMPC_NAMES',       value: params.VMPC_NAMES.trim()       ),
                booleanParam(name: 'PROVISION_DOCKER', value: params.PROVISION_DOCKER  ),
                string(name: 'build_name', value: paramsMap.build_name.trim()),
                string(name: 'ORIOLE_SUBMIT_FLAG', value: params.ORIOLE_SUBMIT_FLAG.trim()),
                string(name: 'SEND_TO', value: params.SEND_TO.trim())
              ]
              echo "Triggering fortistack_runtest pipeline for test group '${group}' with parameters: ${testParams}"
              def result = build job: 'fortistack_runtest', parameters: testParams, wait: true, propagate: false
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
              def outputsDir = "/home/fosqa/${LOCAL_LIB_DIR}/outputs"
              // Clean up previous archiving work.
              sh "hostname"
              sh "ip add"
              sh "rm -f ${WORKSPACE}/summary_*.html"

              // Debug: list all directories under outputsDir
              def listAll = sh(
                returnStdout: true,
                script: "find '${outputsDir}' -mindepth 2 -maxdepth 2 -type d"
              ).trim()
              echo "All directories under ${outputsDir}:\n${listAll}"

              def archivedFolders = []
              for (group in computedTestGroups) {
                def archiveGroup = getArchiveGroupName(group)
                // Build the base find command.
                def baseCmd = "find '${outputsDir}' -mindepth 2 -maxdepth 2 -type d -name '*--group--${archiveGroup}' -printf '%T@ %p\\n'"
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
            } catch (err) {
              echo "Error in post block: ${err}"
            }
          }
        }


        echo "Master Pipeline completed."
        
      }
      success {
            sendFosqaEmail(
                to:       params.SEND_TO,
                subject:  "${env.BUILD_DISPLAY_NAME} Succeeded",
                body:     "<p>Good news: job <b>${env.JOB_NAME}</b> completed at ${new Date()}</p>"
            )
      }
      failure {
          sendFosqaEmail(
              to:      params.SEND_TO,
              subject: "${env.BUILD_DISPLAY_NAME} FAILED",
              body:    "<p>Check console output: ${env.BUILD_URL}</p>"
          )
      }
    }
  }
}
