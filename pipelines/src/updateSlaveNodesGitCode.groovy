import groovy.json.JsonOutput
import groovy.json.JsonSlurper

pipeline {
    agent { label 'master' }

    parameters {
        string(name: 'NODE_PREFIX', defaultValue: 'node', description: 'Prefix for node names (e.g., "node" for node1, node2, etc.)')
        string(name: 'START_NUMBER', defaultValue: '1', description: 'Start number for node range')
        string(name: 'END_NUMBER', defaultValue: '50', description: 'End number for node range')
        string(name: 'GIT_REPO_PATH', defaultValue: '/home/fosqa/resources/tools', description: 'Path to Git repository on slave nodes')
        string(name: 'GIT_BRANCH', defaultValue: 'main', description: 'Git branch to pull from')
        booleanParam(name: 'DRY_RUN', defaultValue: false, description: 'Dry run - only show what would be updated without making changes')
        choice(name: 'NODE_FILTER', choices: ['ALL', 'ONLINE_ONLY'], description: 'Which nodes to update')
        booleanParam(name: 'FORCE_PULL', defaultValue: false, description: 'Force pull by stashing local changes if needed')
        booleanParam(name: 'SHOW_GIT_STATUS', defaultValue: true, description: 'Show git status before and after update')
        booleanParam(name: 'PARALLEL_EXECUTION', defaultValue: true, description: 'Execute git updates in parallel across nodes')
    }

    stages {
        stage('Set Build Display Name') {
            steps {
                script {
                    def nodePrefix = params.NODE_PREFIX.toString().trim()
                    def startNum = params.START_NUMBER.toString().trim()
                    def endNum = params.END_NUMBER.toString().trim()

                    currentBuild.displayName = "#${currentBuild.number} Git Update ${nodePrefix}${startNum}-${endNum}"
                    if (params.DRY_RUN) {
                        currentBuild.displayName += " (DRY RUN)"
                    }
                }
            }
        }

        stage('Validate Parameters') {
            steps {
                script {
                    def nodePrefix = params.NODE_PREFIX.toString().trim()
                    def startNum = params.START_NUMBER.toString().trim()
                    def endNum = params.END_NUMBER.toString().trim()
                    def gitRepoPath = params.GIT_REPO_PATH.toString().trim()
                    def gitBranch = params.GIT_BRANCH.toString().trim()

                    if (!nodePrefix) error "NODE_PREFIX cannot be empty"
                    if (!startNum.isInteger()) error "START_NUMBER must be a valid integer"
                    if (!endNum.isInteger()) error "END_NUMBER must be a valid integer"
                    if (!gitRepoPath) error "GIT_REPO_PATH cannot be empty"
                    if (!gitBranch) error "GIT_BRANCH cannot be empty"

                    def startNumber = startNum.toInteger()
                    def endNumber = endNum.toInteger()

                    if (startNumber < 1) error "START_NUMBER must be >= 1"
                    if (endNumber < startNumber) error "END_NUMBER must be >= START_NUMBER"

                    echo "Validation passed:"
                    echo "  Node range: ${nodePrefix}${startNumber} to ${nodePrefix}${endNumber}"
                    echo "  Git repository path: ${gitRepoPath}"
                    echo "  Git branch: ${gitBranch}"
                    echo "  Total nodes to process: ${endNumber - startNumber + 1}"
                    echo "  Dry run: ${params.DRY_RUN}"
                    echo "  Node filter: ${params.NODE_FILTER}"
                    echo "  Force pull: ${params.FORCE_PULL}"
                    echo "  Show git status: ${params.SHOW_GIT_STATUS}"
                    echo "  Parallel execution: ${params.PARALLEL_EXECUTION}"
                }
            }
        }

        stage('Update Git on Slave Nodes') {
            steps {
                script {
                    def nodePrefix = params.NODE_PREFIX.toString().trim()
                    def startNumber = params.START_NUMBER.toInteger()
                    def endNumber = params.END_NUMBER.toInteger()
                    def gitRepoPath = params.GIT_REPO_PATH.toString().trim()
                    def gitBranch = params.GIT_BRANCH.toString().trim()
                    def nodeFilter = params.NODE_FILTER.toString()
                    def dryRun = params.DRY_RUN
                    def forcePull = params.FORCE_PULL
                    def showGitStatus = params.SHOW_GIT_STATUS
                    def parallelExecution = params.PARALLEL_EXECUTION

                    echo "Starting Git update on slave nodes..."
                    echo "==========================================="

                    // Build list of nodes to process
                    def nodeList = []
                    for (int i = startNumber; i <= endNumber; i++) {
                        nodeList.add("${nodePrefix}${i}")
                    }

                    // Build git command (simplified, similar to fortistackProvisionFgts.groovy)
                    def gitCommand = "sudo -u fosqa bash -c 'cd ${gitRepoPath} && " +
                                   "if [ -n \"\$(git status --porcelain)\" ]; then git stash push -m \"temporary stash\"; fi; " +
                                   "git pull; " +
                                   "if git stash list | grep -q \"temporary stash\"; then git stash pop; fi'"

                    echo "Git command to execute: ${gitCommand}"

                    if (parallelExecution && nodeList.size() > 1) {
                        // Parallel execution
                        def parallelTasks = [:]

                        nodeList.each { nodeName ->
                            parallelTasks[nodeName] = {
                                updateNodeGit(nodeName, gitCommand, dryRun, nodeFilter)
                            }
                        }

                        echo "Executing git updates in parallel..."
                        parallel parallelTasks

                    } else {
                        // Sequential execution
                        echo "Executing git updates sequentially..."
                        nodeList.each { nodeName ->
                            updateNodeGit(nodeName, gitCommand, dryRun, nodeFilter)
                        }
                    }

                    echo "\n✅ Git update stage completed!"
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
                def gitRepoPath = params.GIT_REPO_PATH.toString().trim()
                def gitBranch = params.GIT_BRANCH.toString().trim()
                def dryRun = params.DRY_RUN

                echo "\n=========================================="
                echo "PIPELINE COMPLETED"
                echo "=========================================="
                echo "Node range: ${nodePrefix}${startNumber} to ${nodePrefix}${endNumber}"
                echo "Git repository path: ${gitRepoPath}"
                echo "Git branch: ${gitBranch}"
                echo "Mode: ${dryRun ? 'DRY RUN' : 'LIVE UPDATE'}"
                echo "Status: ${currentBuild.currentResult}"
                echo "\n💡 Git source code update completed on slave nodes."
            }
        }
    }
}

def updateNodeGit(nodeName, gitCommand, dryRun, nodeFilter) {
    try {
        echo "\n🔄 Processing node: ${nodeName}"

        if (dryRun) {
            echo "  🔍 DRY RUN: Would execute git pull on ${nodeName}"
            return
        }

        // Check if node is online (only for ONLINE_ONLY filter)
        if (nodeFilter == 'ONLINE_ONLY') {
            try {
                node(nodeName) {
                    echo "  📊 Node ${nodeName} is online and accessible"
                }
            } catch (Exception e) {
                echo "  ⏭️  Skipping ${nodeName} (node not available: ${e.getMessage()})"
                return
            }
        }

        // Execute git command on the node (using the simple approach like your reference)
        node(nodeName) {
            echo "  🔄 Executing git update on ${nodeName}..."
            echo "  📝 Command to execute: ${gitCommand}"

            try {
                // First, let's test basic connectivity and permissions
                echo "  🔍 Testing basic environment on ${nodeName}..."
                sh 'whoami'
                sh 'pwd'

                // Check if the git repository exists and is accessible
                echo "  🔍 Checking git repository access..."
                sh "ls -la ${params.GIT_REPO_PATH} || echo 'Directory does not exist or no access'"

                // Test if we can access the directory as fosqa user
                echo "  🔍 Testing fosqa user access..."
                sh "sudo -u fosqa bash -c 'cd ${params.GIT_REPO_PATH} && pwd && git status' || echo 'Cannot access as fosqa user'"

                // Execute the simplified git command
                echo "  🔄 Executing git pull command..."
                sh gitCommand
                echo "  ✅ Successfully updated Git on ${nodeName}"

            } catch (Exception e) {
                echo "  ⚠️  Git update had issues on ${nodeName}: ${e.getMessage()}"

                // Additional debugging on exception
                echo "  🔍 Exception occurred, running additional diagnostics..."
                try {
                    sh """
                        echo "=== DIAGNOSTIC INFO FOR ${nodeName} ==="
                        echo "Current user: \$(whoami)"
                        echo "Home directory: \$HOME"
                        echo "Current working directory: \$(pwd)"
                        echo "Checking if fosqa user exists:"
                        id fosqa || echo "fosqa user does not exist"
                        echo "Checking sudo permissions:"
                        sudo -l -U fosqa || echo "Cannot check sudo permissions for fosqa"
                        echo "Checking git installation:"
                        which git || echo "git not found"
                        git --version || echo "git version check failed"
                        echo "Checking git repository status:"
                        sudo -u fosqa bash -c 'cd ${params.GIT_REPO_PATH} && pwd && git remote -v && git branch -a' || echo "Git diagnostics failed"
                        echo "=== END DIAGNOSTIC INFO ==="
                    """
                } catch (Exception diagEx) {
                    echo "  ⚠️  Even diagnostics failed: ${diagEx.getMessage()}"
                }
            }
        }

    } catch (Exception e) {
        echo "  ❌ Error processing ${nodeName}: ${e.getMessage()}"
        // Continue with other nodes even if one fails
    }
}
