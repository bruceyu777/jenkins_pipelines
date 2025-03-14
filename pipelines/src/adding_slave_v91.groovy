import groovy.json.JsonOutput
import java.net.URLEncoder

pipeline {
    agent { label 'master' }
    parameters {
        booleanParam(name: 'CREATE_NODE', defaultValue: true, description: 'Automatically create a new node via Jenkins API?')
        string(name: 'NODE_NAME', defaultValue: 'node1', description: 'Name of the Jenkins node')
        string(name: 'NODE_IP', defaultValue: '192.168.1.10', description: 'IP address of the target node')
        string(name: 'NODE_USER', defaultValue: 'fosqa', description: 'SSH username for the target node')
        password(name: 'NODE_PASSWORD', defaultValue: 'ftnt123!', description: 'SSH password for the target node')
        string(name: 'JENKINS_URL', defaultValue: 'http://10.96.227.206:8080', description: 'Jenkins Master URL (floating IP)')
        string(name: 'JENKINS_SECRET', defaultValue: '', description: 'Jenkins secret for the node (if not provisioning)')
        booleanParam(name: 'CLEANUP', defaultValue: false, description: 'Cleanup previous Jenkins Agent installation?')
        string(name: 'JENKINS_ADMIN_USER', defaultValue: 'fosqa', description: 'Jenkins admin username for API calls')
        password(name: 'JENKINS_API_TOKEN', defaultValue: '110dec5c2d2974a67968074deafccc1414', description: 'Jenkins API token for admin user')
    }
    stages {
        stage('Create Node') {
            when { expression { return params.CREATE_NODE } }
            steps {
                script {
                    def nodeName = params.NODE_NAME
                    
                    // Check if the node already exists by getting its HTTP status code.
                    def httpCode = sh(
                        script: "curl -s -o /dev/null -w '%{http_code}' ${params.JENKINS_URL}/computer/${nodeName}/api/json",
                        returnStdout: true
                    ).trim()
                    
                    if (httpCode == "200") {
                        echo "Node ${nodeName} already exists. Skipping creation."
                    } else {
                        echo "Node ${nodeName} does not exist. Proceeding with creation."
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
                            
                            // Get Jenkins crumb for CSRF protection.
                            def crumbData = sh(
                                script: "curl -s -u ${params.JENKINS_ADMIN_USER}:${params.JENKINS_API_TOKEN} '${params.JENKINS_URL}/crumbIssuer/api/json'",
                                returnStdout: true
                            ).trim()
                            if (!crumbData) {
                                error "Crumb data not received. Check your Jenkins admin credentials and API token."
                            }
                            echo "Crumb data received: ${crumbData}"
                            def crumbJson = readJSON text: crumbData
                            def crumb = crumbJson.crumb
                            
                            // Build the curl command as a multi-line string.
                            def cmd = """
curl -s -u ${params.JENKINS_ADMIN_USER}:${params.JENKINS_API_TOKEN} \\
 -H 'Jenkins-Crumb:${crumb}' -X POST \\
 --data-urlencode 'name=${nodeName}' \\
 --data-urlencode 'type=hudson.slaves.DumbSlave' \\
 --data-urlencode 'json=${jsonBody}' \\
 '${params.JENKINS_URL}/computer/doCreateItem'
"""
                            echo "Executing command: ${cmd}"
                            
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
        }
        stage('Provision Node') {
            when { expression { return params.CREATE_NODE || params.JENKINS_SECRET == '' } }
            steps {
                script {
                    def nodeName = params.NODE_NAME
                    echo "Provisioning node ${nodeName}..."
                    sleep time: 5, unit: 'SECONDS'
                    
                    // Fetch the JNLP file with authentication.
                    def jnlp = sh(
                        script: "curl -fsSL -u ${params.JENKINS_ADMIN_USER}:${params.JENKINS_API_TOKEN} ${params.JENKINS_URL}/computer/${nodeName}/slave-agent.jnlp",
                        returnStdout: true
                    ).trim()
                    echo "JNLP content: ${jnlp}"
                    
                    // Extract secret from the first <argument> element.
                    def matcher = jnlp =~ /<argument>(.*?)<\/argument>/
                    if (!matcher || matcher.size() == 0) {
                        error "Failed to extract secret from JNLP file. Verify if node ${nodeName} exists and is configured."
                    }
                    def secret = matcher[0][1]
                    env.NODE_NAME = nodeName
                    env.JENKINS_SECRET = secret
                    echo "Provisioned node ${env.NODE_NAME} with secret: ${env.JENKINS_SECRET}"
                }
            }
        }
        stage('Install Jenkins Agent on Node') {
            steps {
                script {
                    def nodeName = env.NODE_NAME ?: params.NODE_NAME
                    def jenkinsSecret = env.JENKINS_SECRET ?: params.JENKINS_SECRET
                    def cleanupFlag = params.CLEANUP ? "--cleanup" : ""
                    
                    // Build the remote install command.
                    def remoteInstallCmd = """sudo python3 /home/fosqa/resources/tools/install_jenkins_agent.py \\
    ${cleanupFlag} \\
    --jenkins-url ${params.JENKINS_URL} \\
    --jenkins-secret ${jenkinsSecret} \\
    --jenkins-agent-name ${nodeName} \\
    --remote-root /home/jenkins/agent \\
    --password jenkins
"""
                    echo "Executing remote install command on node ${params.NODE_IP}..."
                    
                    // Use sshpass to run the command remotely.
                    def sshCmd = "sshpass -p '${params.NODE_PASSWORD}' ssh -o StrictHostKeyChecking=no ${params.NODE_USER}@${params.NODE_IP} '${remoteInstallCmd}'"
                    echo "SSH Command: ${sshCmd}"
                    sh sshCmd
                }
            }
        }
        stage('Verify Node Connection') {
            steps {
                script {
                    def nodeName = env.NODE_NAME ?: params.NODE_NAME
                    def nodeOnline = false
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
