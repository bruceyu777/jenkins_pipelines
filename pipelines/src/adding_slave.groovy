import groovy.json.JsonOutput
import java.net.URLEncoder

pipeline {
    agent { label 'master' }
    
    environment {
        NODE_USER          = 'fosqa'
        NODE_PASSWORD      = 'ftnt123!'
        JENKINS_ADMIN_USER = 'fosqa'
        JENKINS_API_TOKEN  = '110dec5c2d2974a67968074deafccc1414'
    }
    
    parameters {
        string(name: 'NODE_NAME', defaultValue: 'node1', description: 'Name of the Jenkins node')
        string(name: 'NODE_IP',   defaultValue: '10.96.225.186', description: 'Floating IP address of the target node')
        string(name: 'JENKINS_URL', defaultValue: 'http://10.96.227.206:8080', description: 'Jenkins Master URL (floating IP)')
        booleanParam(name: 'CLEANUP', defaultValue: true, description: 'Cleanup previous Jenkins Agent installation?')
    }
    
    stages {
        stage('Set Build Display Name') {
            steps {
                script {
                    def nodeName = params.NODE_NAME.toString().trim()
                    def nodeIp   = params.NODE_IP.toString().trim()
                    if (!nodeName) error "NODE_NAME was empty or whitespace"
                    if (!nodeIp)   error "NODE_IP was empty or whitespace"
                    currentBuild.displayName = "#${currentBuild.number} ${nodeName}-${nodeIp}"
                }
            }
        }
        
        stage('Jenkins master to create a new Node') {
            steps {
                script {
                    def nodeName   = params.NODE_NAME.toString().trim()
                    def nodeIp     = params.NODE_IP.toString().trim()
                    def jenkinsUrl = params.JENKINS_URL.toString().trim()
                    if (!nodeName)   error "NODE_NAME was empty or whitespace"
                    if (!nodeIp)     error "NODE_IP was empty or whitespace"
                    if (!jenkinsUrl) error "JENKINS_URL was empty or whitespace"
                    
                    def httpCode = sh(
                        script: "curl -s -o /dev/null -w '%{http_code}' ${jenkinsUrl}/computer/${nodeName}/api/json",
                        returnStdout: true
                    ).trim()
                    
                    if (httpCode == '200') {
                        echo "Node ${nodeName} exists; deleting..."
                        def crumbData = sh(
                            script: "curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} '${jenkinsUrl}/crumbIssuer/api/json'",
                            returnStdout: true
                        ).trim()
                        if (!crumbData) error "Could not get Jenkins crumb."
                        def crumb = readJSON(text: crumbData).crumb
                        
                        def deleteCmd = """
curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} \\
  -H 'Jenkins-Crumb:${crumb}' -X POST \\
  '${jenkinsUrl}/computer/${nodeName}/doDelete'
""".stripIndent().trim()
                        sh(script: deleteCmd, returnStdout: true)
                        sleep time: 10, unit: 'SECONDS'
                    } else {
                        echo "Node ${nodeName} not found; creating..."
                    }
                    
                    try {
                        def payload = [
                            name            : nodeName,
                            nodeDescription : "Automatically created node",
                            numExecutors    : 10,
                            remoteFS        : "/home/jenkins",
                            labelString     : "",
                            mode            : "NORMAL",
                            type            : "hudson.slaves.DumbSlave",
                            retentionStrategy: [
                                "stapler-class": "hudson.slaves.RetentionStrategy\$Always",
                                "\$class"      : "hudson.slaves.RetentionStrategy\$Always"
                            ],
                            nodeProperties  : [],
                            launcher        : [
                                "stapler-class": "hudson.slaves.JNLPLauncher",
                                "\$class"      : "hudson.slaves.JNLPLauncher"
                            ]
                        ]
                        def jsonBody = JsonOutput.toJson(payload)
                        
                        def crumbData2 = sh(
                            script: "curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} '${jenkinsUrl}/crumbIssuer/api/json'",
                            returnStdout: true
                        ).trim()
                        if (!crumbData2) error "Could not get Jenkins crumb for creation."
                        def crumb2 = readJSON(text: crumbData2).crumb
                        
                        def createCmd = """
curl -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} \\
  -H 'Jenkins-Crumb:${crumb2}' -X POST \\
  --data-urlencode 'name=${nodeName}' \\
  --data-urlencode 'type=hudson.slaves.DumbSlave' \\
  --data-urlencode 'json=${jsonBody}' \\
  '${jenkinsUrl}/computer/doCreateItem'
""".stripIndent().trim()
                        sh(script: createCmd, returnStdout: true)
                    } catch (e) {
                        echo "Node creation error: ${e.message}"
                    }
                }
            }
        }
        
        stage('Provision Node') {
            steps {
                script {
                    def nodeName   = params.NODE_NAME.toString().trim()
                    def jenkinsUrl = params.JENKINS_URL.toString().trim()
                    echo "Provisioning ${nodeName}..."
                    sleep time: 5, unit: 'SECONDS'
                    
                    def jnlp = sh(
                        script: "curl -fsSL -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} ${jenkinsUrl}/computer/${nodeName}/slave-agent.jnlp",
                        returnStdout: true
                    ).trim()
                    def matcher = jnlp =~ /<argument>(.*?)<\/argument>/
                    if (!matcher) error "Failed to parse JNLP secret."
                    env.JENKINS_SECRET = matcher[0][1]
                }
            }
        }
        
        stage('Install Jenkins Agent on Node') {
            steps {
                script {
                    def nodeName      = params.NODE_NAME.toString().trim()
                    def nodeIp        = params.NODE_IP.toString().trim()
                    def jenkinsUrl    = params.JENKINS_URL.toString().trim()
                    def jenkinsSecret = env.JENKINS_SECRET
                    def cleanupFlag   = params.CLEANUP ? "--cleanup" : ""
                    
                    // Step 1: Git pull
                    def innerGit = 'cd /home/fosqa/resources/tools && ' +
                                   'if [ -n "$(git status --porcelain)" ]; then git stash -u -m tmp; fi && ' +
                                   'git pull -X theirs && ' +
                                   'if git stash list | grep -q tmp; then git stash pop || (git reset --hard HEAD && git stash drop); fi'
                    sh "sshpass -p '${env.NODE_PASSWORD}' ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${env.NODE_USER}@${nodeIp} '${innerGit}' || echo 'Git pull failed, continuing.'"
                    
                    // Step 2: set hostname
                    def hostCmd = "cd /home/fosqa/resources/tools && sudo python3 set_hostname.py --hostname all-in-one-${nodeName} --floating-ip ${nodeIp}"
                    sh "sshpass -p '${env.NODE_PASSWORD}' ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${env.NODE_USER}@${nodeIp} '${hostCmd}' || echo 'Hostname update failed.'"
                    
                    // Step 3: install agent (single line)
                    def remoteInstallCmd = "sudo python3 /home/fosqa/resources/tools/install_jenkins_agent.py ${cleanupFlag} --jenkins-url ${jenkinsUrl} --jenkins-secret ${jenkinsSecret} --jenkins-agent-name ${nodeName} --remote-root /home/jenkins/agent --password jenkins"
                    sh "sshpass -p '${env.NODE_PASSWORD}' ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null ${env.NODE_USER}@${nodeIp} '${remoteInstallCmd}'"
                }
            }
        }
        
        stage('Verify Node Connection') {
            steps {
                script {
                    def nodeName   = params.NODE_NAME.toString().trim()
                    def jenkinsUrl = params.JENKINS_URL.toString().trim()
                    def ok = false
                    for (int i = 0; i < 10; i++) {
                        def out = sh(
                            script: "curl -fsSL ${jenkinsUrl}/computer/${nodeName}/api/json",
                            returnStdout: true
                        ).trim()
                        if (out.contains('"offline":false')) { ok = true; break }
                        sleep time: 10, unit: 'SECONDS'
                    }
                    if (!ok) error "Node ${nodeName} did not come online."
                    echo "Node ${nodeName} is online."
                }
            }
        }
    }
}
