import groovy.json.JsonOutput
import java.net.URLEncoder

pipeline {
    agent { label 'master' }
    
    // Static values (hidden from users)
    environment {
        NODE_USER = 'fosqa'
        NODE_PASSWORD = 'ftnt123!'
        JENKINS_ADMIN_USER = 'fosqa'
        JENKINS_API_TOKEN = '110dec5c2d2974a67968074deafccc1414'
    }
    
    parameters {
        string(name: 'NODE_NAME', defaultValue: 'node1', description: 'Name of the Jenkins node')
        string(name: 'NODE_IP', defaultValue: '10.96.225.186', description: 'Floating IP address of the target node')
        string(name: 'JENKINS_URL', defaultValue: 'http://10.96.227.206:8080', description: 'Jenkins Master URL (floating IP)')
        booleanParam(name: 'CLEANUP', defaultValue: true, description: 'Cleanup previous Jenkins Agent installation?')
    }
    
    stages {
        stage('Set Build Display Name') {
            steps {
                script {
                    currentBuild.displayName = "#${currentBuild.number} ${params.NODE_NAME}-${params.NODE_IP}"
                }
            }
        }
        stage('Jenkins master to create a new Node') {
            steps {
                script {
                    def nodeName = params.NODE_NAME
                    
                    // Check if the node exists via its HTTP status code.
                    def httpCode = sh(
                        script: "curl -s -o /dev/null -w '%{http_code}' ${params.JENKINS_URL}/computer/${nodeName}/api/json",
                        returnStdout: true
                    ).trim()
                    
                    if (httpCode == "200") {
                        echo "Node ${nodeName} already exists. Removing it first..."
                        
                        // Get Jenkins crumb for CSRF protection.
                        def crumbData = sh(
                            script: "curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} '${params.JENKINS_URL}/crumbIssuer/api/json'",
                            returnStdout: true
                        ).trim()
                        if (!crumbData) {
                            error "Crumb data not received. Check your Jenkins admin credentials and API token."
                        }
                        echo "Crumb data received: ${crumbData}"
                        
                        def crumbJson = readJSON text: crumbData
                        def crumb = crumbJson.crumb
                        
                        // Build the curl command for node deletion.
                        def deleteCmd = """
curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} \\
-H 'Jenkins-Crumb:${crumb}' -X POST \\
'${params.JENKINS_URL}/computer/${nodeName}/doDelete'
"""
                        echo "Executing node deletion command: ${deleteCmd}"
                        def deleteResponse = sh(
                            script: deleteCmd,
                            returnStdout: true
                        ).trim()
                        echo "Node deletion response: ${deleteResponse}"
                        
                        // Wait for the deletion to propagate.
                        sleep time: 10, unit: 'SECONDS'
                        echo "Proceeding with node creation for ${nodeName}..."
                    } else {
                        echo "Node ${nodeName} does not exist. Proceeding with creation."
                    }
                    
                    try {
                        def remoteFS = "/home/jenkins"
                        
                        // Build payload as a Groovy map.
                        def payload = [
                            name             : nodeName,
                            nodeDescription  : "Automatically created node",
                            numExecutors     : 1,
                            remoteFS         : remoteFS,
                            labelString      : "",
                            mode             : "NORMAL",
                            type             : "hudson.slaves.DumbSlave",
                            retentionStrategy: [
                                "stapler-class": "hudson.slaves.RetentionStrategy\$Always",
                                "\$class"      : "hudson.slaves.RetentionStrategy\$Always"
                            ],
                            nodeProperties   : [],
                            launcher         : [
                                "stapler-class": "hudson.slaves.JNLPLauncher",
                                "\$class"      : "hudson.slaves.JNLPLauncher"
                            ]
                        ]
                        
                        def jsonBody = JsonOutput.toJson(payload)
                        echo "JSON payload: ${jsonBody}"
                        
                        // Get Jenkins crumb for CSRF protection for creation.
                        def crumbDataCreate = sh(
                            script: "curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} '${params.JENKINS_URL}/crumbIssuer/api/json'",
                            returnStdout: true
                        ).trim()
                        if (!crumbDataCreate) {
                            error "Crumb data not received for creation. Check your Jenkins admin credentials and API token."
                        }
                        echo "Crumb data received for creation: ${crumbDataCreate}"
                        
                        def crumbJsonCreate = readJSON text: crumbDataCreate
                        def crumbCreate = crumbJsonCreate.crumb
                        
                        // Build the curl command for node creation.
                        def cmd = """
curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} \\
-H 'Jenkins-Crumb:${crumbCreate}' -X POST \\
--data-urlencode 'name=${nodeName}' \\
--data-urlencode 'type=hudson.slaves.DumbSlave' \\
--data-urlencode 'json=${jsonBody}' \\
'${params.JENKINS_URL}/computer/doCreateItem'
"""
                        echo "Executing node creation command: ${cmd}"
                        
                        def createResponse = sh(
                            script: cmd,
                            returnStdout: true
                        ).trim()
                        echo "Node creation response: ${createResponse}"
                    } catch (Exception e) {
                        echo "Node creation failed: ${e.getMessage()}. Proceeding to next stage."
                    }
                }
            }
        }
        stage('Provision Node') {
            steps {
                script {
                    def nodeName = params.NODE_NAME
                    echo "Provisioning node ${nodeName}..."
                    sleep time: 5, unit: 'SECONDS'
                    
                    // Fetch the JNLP file with authentication.
                    def jnlp = sh(
                        script: "curl -fsSL -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} ${params.JENKINS_URL}/computer/${nodeName}/slave-agent.jnlp",
                        returnStdout: true
                    ).trim()
                    echo "JNLP content: ${jnlp}"
                    
                    // Extract secret from the first <argument> element.
                    def matcher = jnlp =~ /<argument>(.*?)<\/argument>/
                    if (!matcher || matcher.size() == 0) {
                        error "Failed to extract secret from JNLP file. Verify if node ${nodeName} exists and is configured."
                    }
                    def secret = matcher[0][1]
                    env.JENKINS_SECRET = secret
                    echo "Provisioned node ${nodeName} with secret: ${env.JENKINS_SECRET}"
                }
            }
        }
        stage('Install Jenkins Agent on Node') {
            steps {
                script {
                    def nodeName = params.NODE_NAME
                    def jenkinsSecret = env.JENKINS_SECRET
                    def cleanupFlag = params.CLEANUP ? "--cleanup" : ""
                    
                    echo "=== Step 1: Remote Git update ==="
                    def innerGitCmd = 'cd /home/fosqa/resources/tools && ' +
                                    // Stash both tracked and untracked changes
                                    'if [ -n "$(git status --porcelain)" ]; then git stash push -u -m "temporary stash"; fi; ' +
                                    // Pull using recursive merge with -X theirs to favor remote changes on conflicts
                                    'git pull -X theirs; ' +
                                    // Try to reapply the stash; if conflicts occur, reset hard and drop the stash
                                    'if git stash list | grep -q "temporary stash"; then ' +
                                    '  if ! git stash pop; then ' +
                                    '    echo "Stash pop encountered conflicts, discarding local changes in favor of remote changes"; ' +
                                    '    git reset --hard HEAD; ' +
                                    '    git stash drop; ' +
                                    '  fi; ' +
                                    'fi'
                    def gitPullCmd = "sshpass -p '${env.NODE_PASSWORD}' ssh -o StrictHostKeyChecking=no ${env.NODE_USER}@${params.NODE_IP} '${innerGitCmd}'"
                    echo "Executing remote git pull command: ${gitPullCmd}"
                    try {
                        sh gitPullCmd
                    } catch (Exception e) {
                        echo "Remote git pull failed: ${e.getMessage()}. Continuing without updating."
                    }

                    
                    echo "=== Step 2: Update hostname, floating IP and aliases ==="
                    def setHostnameCmd = "sshpass -p '${env.NODE_PASSWORD}' ssh -o StrictHostKeyChecking=no ${env.NODE_USER}@${params.NODE_IP} 'cd /home/fosqa/resources/tools && sudo python3 set_hostname.py --hostname all-in-one-${nodeName} --floating-ip ${params.NODE_IP}'"
                    echo "Executing update hostname command: ${setHostnameCmd}"
                    try {
                        sh setHostnameCmd
                    } catch (Exception e) {
                        echo "Hostname update failed: ${e.getMessage()}. Continuing..."
                    }
                    
                    echo "=== Step 3: Install Jenkins Agent ==="
                    def remoteInstallCmd = """sudo python3 /home/fosqa/resources/tools/install_jenkins_agent.py \\
${cleanupFlag} \\
--jenkins-url ${params.JENKINS_URL} \\
--jenkins-secret ${jenkinsSecret} \\
--jenkins-agent-name ${nodeName} \\
--remote-root /home/jenkins/agent \\
--password jenkins
"""
                    echo "Executing remote install command on node ${params.NODE_IP}..."
                    def sshCmd = "sshpass -p '${env.NODE_PASSWORD}' ssh -o StrictHostKeyChecking=no ${env.NODE_USER}@${params.NODE_IP} '${remoteInstallCmd}'"
                    echo "SSH Command: ${sshCmd}"
                    sh sshCmd
                    
                    // === Alternative: Consolidate all remote commands in one SSH session ===
                    // def consolidatedCmd = """
                    //     cd /home/fosqa/resources/tools &&
                    //     if [ -n "\$(git status --porcelain)" ]; then git stash push -m "temporary stash"; fi;
                    //     git pull;
                    //     if git stash list | grep -q "temporary stash"; then git stash pop; fi;
                    //     sudo python3 set_hostname.py --hostname all-in-one-${nodeName} --floating-ip ${params.NODE_IP};
                    //     sudo python3 /home/fosqa/resources/tools/install_jenkins_agent.py ${cleanupFlag} --jenkins-url ${params.JENKINS_URL} --jenkins-secret ${jenkinsSecret} --jenkins-agent-name ${nodeName} --remote-root /home/jenkins/agent --password jenkins
                    //     """
                    // def consolidatedSSHCmd = "sshpass -p '${env.NODE_PASSWORD}' ssh -o StrictHostKeyChecking=no ${env.NODE_USER}@${params.NODE_IP} '${consolidatedCmd}'"
                    // echo "Executing consolidated remote command: ${consolidatedSSHCmd}"
                    // sh consolidatedSSHCmd
                    
                }
            }
        }
        stage('Verify Node Connection') {
            steps {
                script {
                    def nodeName = params.NODE_NAME
                    def nodeOnline = false
                    // Check up to 10 times (with a 10-second interval) for the node to come online.
                    for (int i = 0; i < 10; i++) {
                        def json = sh(
                            script: "curl -fsSL ${params.JENKINS_URL}/computer/${nodeName}/api/json",
                            returnStdout: true
                        ).trim()
                        if (json.contains('"offline":false')) {
                            nodeOnline = true
                            break
                        }
                        sleep time: 10, unit: 'SECONDS'
                    }
                    if (!nodeOnline) {
                        error "Node ${nodeName} did not come online in time."
                    }
                    echo "Node ${nodeName} is online."
                }
            }
        }
    }
}
