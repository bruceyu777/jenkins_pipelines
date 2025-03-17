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

// Helper function to compute test groups based on parameters.
def getTestGroups(params) {
    def testGroups = []
    if (params.TEST_GROUPS) {
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
    if (!testGroups || testGroups.isEmpty()) {
        testGroups = [params.TEST_GROUP_CHOICE]
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
  fortistackMasterParameters()
  expandParamsJson(params.PARAMS_JSON)

  pipeline {
    agent { label 'master' }
    options {
      buildDiscarder(logRotator(numToKeepStr: '100'))
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
        agent { label "${params.NODE_NAME}" }
        when {
          expression { return !params.SKIP_PROVISION }
        }
        steps {
          script {
            def paramsMap = new groovy.json.JsonSlurper()
                              .parseText(params.PARAMS_JSON)
                              .collectEntries { k, v -> [k, v] }
            def provisionParams = [
              string(name: 'NODE_NAME', value: params.NODE_NAME),
              string(name: 'BUILD_NUMBER', value: params.BUILD_NUMBER),
              string(name: 'FGT_TYPE', value: paramsMap.FGT_TYPE)
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
                string(name: 'BUILD_NUMBER', value: params.BUILD_NUMBER),
                string(name: 'NODE_NAME', value: params.NODE_NAME),
                string(name: 'LOCAL_LIB_DIR', value: paramsMap.LOCAL_LIB_DIR),
                string(name: 'SVN_BRANCH', value: paramsMap.SVN_BRANCH),
                string(name: 'FEATURE_NAME', value: params.FEATURE_NAME),
                string(name: 'TEST_CASE_FOLDER', value: params.TEST_CASE_FOLDER),
                string(name: 'TEST_CONFIG_CHOICE', value: params.TEST_CONFIG_CHOICE),
                string(name: 'TEST_GROUP_CHOICE', value: group),
                string(name: 'DOCKER_COMPOSE_FILE_CHOICE', value: params.DOCKER_COMPOSE_FILE_CHOICE),
                booleanParam(name: 'FORCE_UPDATE_DOCKER_FILE', value: params.FORCE_UPDATE_DOCKER_FILE),
                string(name: 'build_name', value: paramsMap.build_name),
                string(name: 'send_to', value: paramsMap.send_to)
              ]
              echo "Triggering fortistack_runtest pipeline for test group '${group}' with parameters: ${testParams}"
              def result = build job: 'fortistack_runtest', parameters: testParams, wait: true, propagate: false
              groupResults[group] = result.getResult()
              if(result.getResult() != "SUCCESS"){
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
        script {
          // Archive Test Results using computedTestGroups.
          def outputsDir = "/home/fosqa/${LOCAL_LIB_DIR}/outputs"
          // Clean up previous archiving work.
          sh "rm -f ${WORKSPACE}/summary_*.html"
          
          def archivedFolders = []
          for (group in computedTestGroups) {
            def archiveGroup = getArchiveGroupName(group)
            def folder = sh(
                returnStdout: true,
                script: """
                    find ${outputsDir} -mindepth 2 -maxdepth 2 -type d -name "*--group--${archiveGroup}" -printf '%T@ %p\\n' | sort -nr | head -1 | cut -d' ' -f2-
                """
            ).trim()
            
            if (!folder) {
                echo "Warning: No test results folder found for test group '${archiveGroup}' in ${outputsDir}."
            } else {
                echo "Found folder for group '${archiveGroup}': ${folder}"
                archivedFolders << folder
                //Only archive summary.html
                sh "cp ${folder}/summary/summary.html ${WORKSPACE}/summary_${archiveGroup}.html"
            }
          }
          
          if (archivedFolders.isEmpty()) {
              echo "No test results were found for any test group."
          } else {
              archiveArtifacts artifacts: "test_results/**, summary_*.html", fingerprint: false
          }
        }
        echo "Pipeline completed."
      }
    }
  }
}
