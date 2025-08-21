import groovy.json.JsonOutput
import groovy.json.JsonSlurper

pipeline {
    agent { label 'master' }

    environment {
        JENKINS_ADMIN_USER = 'fosqa'
        JENKINS_API_TOKEN  = '110dec5c2d2974a67968074deafccc1414'
    }

    parameters {
        string(name: 'NODE_PREFIX', defaultValue: 'node', description: 'Prefix for node names (e.g., "node" for node1, node2, etc.)')
        string(name: 'START_NUMBER', defaultValue: '1', description: 'Start number for node range')
        string(name: 'END_NUMBER', defaultValue: '50', description: 'End number for node range')
        string(name: 'NUM_EXECUTORS', defaultValue: '8', description: 'Number of executors to set for each node')
        string(name: 'JENKINS_URL', defaultValue: 'http://10.96.227.206:8080', description: 'Jenkins Master URL (floating IP)')
        booleanParam(name: 'DRY_RUN', defaultValue: false, description: 'Dry run - only show what would be updated without making changes')
        choice(name: 'NODE_FILTER', choices: ['ALL', 'ONLINE_ONLY', 'OFFLINE_ONLY'], description: 'Which nodes to update')
    }

    stages {
        stage('Set Build Display Name') {
            steps {
                script {
                    def nodePrefix = params.NODE_PREFIX.toString().trim()
                    def startNum = params.START_NUMBER.toString().trim()
                    def endNum = params.END_NUMBER.toString().trim()
                    def numExecutors = params.NUM_EXECUTORS.toString().trim()

                    currentBuild.displayName = "#${currentBuild.number} ${nodePrefix}${startNum}-${endNum} (${numExecutors} executors)"
                }
            }
        }

        stage('Validate Parameters') {
            steps {
                script {
                    def nodePrefix = params.NODE_PREFIX.toString().trim()
                    def startNum = params.START_NUMBER.toString().trim()
                    def endNum = params.END_NUMBER.toString().trim()
                    def numExecutors = params.NUM_EXECUTORS.toString().trim()
                    def jenkinsUrl = params.JENKINS_URL.toString().trim()

                    if (!nodePrefix) error "NODE_PREFIX cannot be empty"
                    if (!startNum.isInteger()) error "START_NUMBER must be a valid integer"
                    if (!endNum.isInteger()) error "END_NUMBER must be a valid integer"
                    if (!numExecutors.isInteger()) error "NUM_EXECUTORS must be a valid integer"
                    if (!jenkinsUrl) error "JENKINS_URL cannot be empty"

                    def startNumber = startNum.toInteger()
                    def endNumber = endNum.toInteger()
                    def executors = numExecutors.toInteger()

                    if (startNumber < 1) error "START_NUMBER must be >= 1"
                    if (endNumber < startNumber) error "END_NUMBER must be >= START_NUMBER"
                    if (executors < 1) error "NUM_EXECUTORS must be >= 1"
                    if (executors > 50) error "NUM_EXECUTORS should not exceed 50 for safety"

                    echo "Validation passed:"
                    echo "  Node range: ${nodePrefix}${startNumber} to ${nodePrefix}${endNumber}"
                    echo "  Executors per node: ${executors}"
                    echo "  Total nodes to process: ${endNumber - startNumber + 1}"
                    echo "  Dry run: ${params.DRY_RUN}"
                    echo "  Node filter: ${params.NODE_FILTER}"
                }
            }
        }

        stage('Get Jenkins Crumb') {
            steps {
                script {
                    def jenkinsUrl = params.JENKINS_URL.toString().trim()

                    def crumbData = sh(
                        script: "curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} '${jenkinsUrl}/crumbIssuer/api/json'",
                        returnStdout: true
                    ).trim()

                    if (!crumbData) error "Could not get Jenkins crumb"

                    def crumbJson = readJSON(text: crumbData)
                    env.JENKINS_CRUMB = crumbJson.crumb

                    echo "Jenkins crumb obtained successfully"
                }
            }
        }

        stage('Update Node Executors') {
            steps {
                script {
                    def nodePrefix = params.NODE_PREFIX.toString().trim()
                    def startNumber = params.START_NUMBER.toInteger()
                    def endNumber = params.END_NUMBER.toInteger()
                    def numExecutors = params.NUM_EXECUTORS.toInteger()
                    def jenkinsUrl = params.JENKINS_URL.toString().trim()
                    def nodeFilter = params.NODE_FILTER.toString()
                    def dryRun = params.DRY_RUN

                    def updateCount = 0
                    def skipCount = 0
                    def errorCount = 0
                    def processedNodesList = []

                    echo "Starting to update node executors..."
                    echo "==========================================="

                    for (int i = startNumber; i <= endNumber; i++) {
                        def nodeName = "${nodePrefix}${i}"

                        try {
                            echo "\nProcessing node: ${nodeName}"

                            // Check if node exists
                            def nodeExistsCode = sh(
                                script: "curl -s -o /dev/null -w '%{http_code}' -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} '${jenkinsUrl}/computer/${nodeName}/api/json'",
                                returnStdout: true
                            ).trim()

                            if (nodeExistsCode != '200') {
                                echo "  ❌ Node ${nodeName} does not exist (HTTP ${nodeExistsCode})"
                                skipCount++
                                continue
                            }

                            // Get current node information
                            def nodeInfoJson = sh(
                                script: "curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} '${jenkinsUrl}/computer/${nodeName}/api/json'",
                                returnStdout: true
                            ).trim()

                            def nodeInfo = readJSON(text: nodeInfoJson)
                            def isOnline = !nodeInfo.offline
                            def currentExecutors = nodeInfo.numExecutors

                            echo "  📊 Current status: ${isOnline ? 'ONLINE' : 'OFFLINE'}, Executors: ${currentExecutors}"

                            // Apply node filter
                            def shouldProcess = true
                            switch (nodeFilter) {
                                case 'ONLINE_ONLY':
                                    shouldProcess = isOnline
                                    break
                                case 'OFFLINE_ONLY':
                                    shouldProcess = !isOnline
                                    break
                                case 'ALL':
                                default:
                                    shouldProcess = true
                                    break
                            }

                            if (!shouldProcess) {
                                echo "  ⏭️  Skipping ${nodeName} (filtered out by ${nodeFilter})"
                                skipCount++
                                continue
                            }

                            // Check if update is needed
                            if (currentExecutors == numExecutors) {
                                echo "  ✅ Node ${nodeName} already has ${numExecutors} executors"
                                skipCount++
                                continue
                            }

                            if (dryRun) {
                                echo "  🔍 DRY RUN: Would update ${nodeName} from ${currentExecutors} to ${numExecutors} executors"
                                processedNodesList.add("${nodeName}:${currentExecutors}:${numExecutors}:DRYRUN")
                                updateCount++
                            } else {
                                // Update the node configuration using Jenkins Script Console
                                echo "  🔄 Updating ${nodeName} from ${currentExecutors} to ${numExecutors} executors..."

                                def groovyScript = """
import jenkins.model.Jenkins

def nodeName = '${nodeName}'
def numExecutors = ${numExecutors}
def jenkins = Jenkins.instance

try {
    def computer = jenkins.getComputer(nodeName)
    if (computer != null) {
        def node = computer.getNode()
        if (node != null) {
            println "Found node: " + nodeName
            println "Current executors: " + node.getNumExecutors()

            // Set the new executor count
            node.setNumExecutors(numExecutors)
            println "Set executors to: " + numExecutors

            // Save the node configuration
            node.save()
            println "Node configuration saved"

            // Reload Jenkins configuration to ensure persistence
            jenkins.reload()
            println "Jenkins configuration reloaded"

            // Verify the change
            def updatedNode = jenkins.getComputer(nodeName).getNode()
            println "Verified executors: " + updatedNode.getNumExecutors()

            println "Successfully updated " + nodeName + " to " + numExecutors + " executors"
        } else {
            println "ERROR: Node " + nodeName + " not found"
        }
    } else {
        println "ERROR: Computer " + nodeName + " not found"
    }
} catch (Exception e) {
    println "ERROR: Exception occurred: " + e.getMessage()
    e.printStackTrace()
}
""".trim()

                                // Save script to temporary file
                                def tempScriptFile = "/tmp/update_${nodeName}_executors.groovy"
                                writeFile file: tempScriptFile, text: groovyScript

                                // Execute the script using curl with file upload
                                def scriptResult = sh(
                                    script: "curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} -H 'Jenkins-Crumb:${env.JENKINS_CRUMB}' -X POST -F 'script=<${tempScriptFile}' '${jenkinsUrl}/scriptText'",
                                    returnStdout: true
                                ).trim()

                                // Clean up temp file
                                sh "rm -f ${tempScriptFile}"

                                if (scriptResult.contains("Successfully updated ${nodeName} to ${numExecutors} executors") ||
                                    scriptResult.contains("Node configuration saved")) {
                                    echo "  ✅ Successfully updated ${nodeName}"
                                    processedNodesList.add("${nodeName}:${currentExecutors}:${numExecutors}:UPDATED")
                                    updateCount++
                                } else {
                                    echo "  ❌ Failed to update ${nodeName}"
                                    echo "  🔍 Script output: ${scriptResult}"
                                    errorCount++
                                }
                            }

                        } catch (Exception e) {
                            echo "  ❌ Error processing ${nodeName}: ${e.message}"
                            errorCount++
                        }
                    }

                    // Store processed nodes data using currentBuild.description (reliable method)
                    def processedNodesString = processedNodesList.join('|')
                    currentBuild.description = "PROCESSED_NODES:${processedNodesString}"
                    currentBuild.displayName = currentBuild.displayName + " [${processedNodesList.size()} updated]"

                    // Update Summary
                    echo "\n=========================================="
                    echo "UPDATE STAGE SUMMARY"
                    echo "=========================================="
                    echo "Total nodes in range: ${endNumber - startNumber + 1}"
                    echo "Successfully updated: ${updateCount}"
                    echo "Skipped: ${skipCount}"
                    echo "Errors: ${errorCount}"
                    echo "Dry run mode: ${dryRun}"

                    if (errorCount > 0) {
                        echo "\n⚠️  Some nodes had issues during update. Check logs above."
                    }

                    echo "\n✅ Node executor update stage completed!"
                }
            }
        }

        stage('Verify Node Executors') {
            when {
                expression {
                    def desc = currentBuild.description ?: ""
                    return desc.contains("PROCESSED_NODES:")
                }
            }
            steps {
                script {
                    def jenkinsUrl = params.JENKINS_URL.toString().trim()
                    def dryRun = params.DRY_RUN

                    // Extract processed nodes data from build description
                    def desc = currentBuild.description ?: ""
                    def processedNodesString = ""
                    if (desc.contains("PROCESSED_NODES:")) {
                        processedNodesString = desc.substring(desc.indexOf("PROCESSED_NODES:") + 16)
                        echo "📋 Found processed nodes data: ${processedNodesString}"
                    }

                    if (!processedNodesString) {
                        echo "⚠️  No processed nodes data found. Skipping verification."
                        return
                    }

                    def verificationSuccess = 0
                    def verificationFailed = 0
                    def verificationResults = []

                    echo "Starting verification of updated nodes..."
                    echo "==========================================="

                    // Give Jenkins time to process all updates
                    echo "⏳ Waiting 15 seconds for Jenkins to process updates..."
                    sleep time: 15, unit: 'SECONDS'

                    def processedNodes = processedNodesString.split('\\|')

                    processedNodes.each { nodeData ->
                        if (nodeData.trim()) {
                            def parts = nodeData.split(':')
                            def nodeName = parts[0]
                            def oldExecutors = parts[1].toInteger()
                            def expectedExecutors = parts[2].toInteger()
                            def updateStatus = parts[3]

                            echo "\nVerifying node: ${nodeName}"
                            echo "  Expected change: ${oldExecutors} → ${expectedExecutors}"

                            if (updateStatus == 'DRYRUN') {
                                echo "  ✅ Dry run - verification skipped"
                                verificationResults.add([
                                    name: nodeName,
                                    oldExecutors: oldExecutors,
                                    expectedExecutors: expectedExecutors,
                                    actualExecutors: oldExecutors,
                                    status: 'DRY_RUN'
                                ])
                                verificationSuccess++
                                return
                            }

                            try {
                                // Verify with multiple attempts
                                def verified = false
                                def actualExecutors = oldExecutors

                                for (int attempt = 1; attempt <= 3; attempt++) {
                                    echo "  🔍 Verification attempt ${attempt}/3..."

                                    def verifyJson = sh(
                                        script: "curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} '${jenkinsUrl}/computer/${nodeName}/api/json'",
                                        returnStdout: true
                                    ).trim()

                                    def verifyInfo = readJSON(text: verifyJson)
                                    actualExecutors = verifyInfo.numExecutors

                                    echo "  📊 Attempt ${attempt}: Current=${actualExecutors}, Expected=${expectedExecutors}"

                                    if (actualExecutors == expectedExecutors) {
                                        verified = true
                                        break
                                    }

                                    if (attempt < 3) {
                                        echo "  ⏳ Waiting 5 seconds before retry..."
                                        sleep time: 5, unit: 'SECONDS'
                                    }
                                }

                                if (verified) {
                                    echo "  ✅ Verification successful for ${nodeName}"
                                    verificationResults.add([
                                        name: nodeName,
                                        oldExecutors: oldExecutors,
                                        expectedExecutors: expectedExecutors,
                                        actualExecutors: actualExecutors,
                                        status: 'SUCCESS'
                                    ])
                                    verificationSuccess++
                                } else {
                                    echo "  ❌ Verification failed for ${nodeName} (expected: ${expectedExecutors}, actual: ${actualExecutors})"
                                    verificationResults.add([
                                        name: nodeName,
                                        oldExecutors: oldExecutors,
                                        expectedExecutors: expectedExecutors,
                                        actualExecutors: actualExecutors,
                                        status: 'FAILED'
                                    ])
                                    verificationFailed++
                                }

                            } catch (Exception e) {
                                echo "  ❌ Error verifying ${nodeName}: ${e.message}"
                                verificationResults.add([
                                    name: nodeName,
                                    oldExecutors: oldExecutors,
                                    expectedExecutors: expectedExecutors,
                                    actualExecutors: 'ERROR',
                                    status: 'ERROR'
                                ])
                                verificationFailed++
                            }
                        }
                    }

                    // Verification Summary
                    echo "\n=========================================="
                    echo "VERIFICATION STAGE SUMMARY"
                    echo "=========================================="
                    echo "Total nodes verified: ${verificationSuccess + verificationFailed}"
                    echo "Verification successful: ${verificationSuccess}"
                    echo "Verification failed: ${verificationFailed}"

                    if (verificationResults.size() > 0) {
                        echo "\nDETAILED VERIFICATION RESULTS:"
                        echo "Node Name | Old Exec | Expected | Actual   | Status"
                        echo "----------|----------|----------|----------|----------"
                        verificationResults.each { result ->
                            def actualStr = result.actualExecutors.toString()
                            echo "${result.name.padRight(9)} | ${result.oldExecutors.toString().padRight(8)} | ${result.expectedExecutors.toString().padRight(8)} | ${actualStr.padRight(8)} | ${result.status}"
                        }
                    }

                    if (verificationFailed > 0) {
                        echo "\n⚠️  Some verifications failed. Please check the Jenkins UI manually for the affected nodes."
                    }

                    echo "\n✅ Verification stage completed!"
                }
            }
        }
    }

    post {
        always {
            script {
                def nodePrefix = params.NODE_PREFIX.toString().trim()
                def startNumber = params.START_NUMBER.toString().trim()
                def endNumber = params.END_NUMBER.toString().trim()
                def numExecutors = params.NUM_EXECUTORS.toString().trim()
                def dryRun = params.DRY_RUN

                echo "\n=========================================="
                echo "PIPELINE COMPLETED"
                echo "=========================================="
                echo "Range: ${nodePrefix}${startNumber} to ${nodePrefix}${endNumber}"
                echo "Target executors: ${numExecutors}"
                echo "Mode: ${dryRun ? 'DRY RUN' : 'LIVE UPDATE'}"
                echo "Status: ${currentBuild.currentResult}"
                echo "\n💡 Please verify the changes in Jenkins UI to confirm all nodes have the correct executor count."
            }
        }
    }
}
