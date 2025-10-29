import groovy.json.JsonOutput
import java.net.URLEncoder

pipeline {
    agent { label 'master' }

    environment {
        NODE_USER          = 'fosqa'
        NODE_PASSWORD      = 'ftnt123!'
        JENKINS_ADMIN_USER = 'yzhengfeng'
        JENKINS_API_TOKEN  = '11019010421f50b6fbe8c6dda2f2e9cbd8'
    }

    parameters {
        string(name: 'NODE_NAME', defaultValue: 'node1', description: 'Name of the Jenkins node')
        string(name: 'NODE_IP',   defaultValue: '10.96.225.186', description: 'Floating IP address of the target node')
        string(name: 'JENKINS_URL', defaultValue: 'https://releaseqa-stackjenkins.corp.fortinet.com', description: 'Jenkins Master URL (HTTPS)')
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
                        script: "curl -k -s -o /dev/null -w '%{http_code}' -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} ${jenkinsUrl}/computer/${nodeName}/api/json",
                        returnStdout: true
                    ).trim()

                    if (httpCode == '200') {
                        echo "Node ${nodeName} exists; deleting..."
                        def crumbData = sh(
                            script: "curl -k -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} '${jenkinsUrl}/crumbIssuer/api/json'",
                            returnStdout: true
                        ).trim()
                        if (!crumbData) error "Could not get Jenkins crumb."
                        def crumb = readJSON(text: crumbData).crumb

                        def deleteCmd = """
curl -k -s -f -v -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} \\
  -H 'Jenkins-Crumb:${crumb}' -X POST \\
  '${jenkinsUrl}/computer/${nodeName}/doDelete'
""".stripIndent().trim()
                        sh(script: deleteCmd)
                        sleep time: 10, unit: 'SECONDS'
                    } else {
                        echo "Node ${nodeName} not found; creating..."
                    }

                    // DEBUGGING BLOCK: This will fail loudly and print the server's response
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
                        script: "curl -k -s -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} '${jenkinsUrl}/crumbIssuer/api/json'",
                        returnStdout: true
                    ).trim()
                    if (!crumbData2) error "Could not get Jenkins crumb for creation."
                    def crumb2 = readJSON(text: crumbData2).crumb

                    // Make curl verbose (-v) and remove silent (-s) to see everything
                    def createCmd = """
curl -k -v -f -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} \\
  -H 'Jenkins-Crumb:${crumb2}' -X POST \\
  --data-urlencode 'name=${nodeName}' \\
  --data-urlencode 'type=hudson.slaves.DumbSlave' \\
  --data-urlencode 'json=${jsonBody}' \\
  '${jenkinsUrl}/computer/doCreateItem'
""".stripIndent().trim()

                    // This will now print the full verbose output from curl when it fails
                    sh(script: createCmd)
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
                        script: "curl -k -fsSL -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} ${jenkinsUrl}/computer/${nodeName}/slave-agent.jnlp",
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
                            script: "curl -k -fsSL -u ${env.JENKINS_ADMIN_USER}:${env.JENKINS_API_TOKEN} ${jenkinsUrl}/computer/${nodeName}/api/json",
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
