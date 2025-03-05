import jenkins.model.Jenkins
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

// Recursively convert maps/lists to plain Java objects.
@NonCPS
def toPlainMap(obj) {
    if (obj instanceof Map) {
        def newMap = [:]
        obj.each { k, v ->
            newMap[k] = toPlainMap(v)
        }
        return newMap
    } else if (obj instanceof List) {
        return obj.collect { toPlainMap(it) }
    } else {
        return obj
    }
}

@NonCPS
def parseJson(String json) {
    def parsed = new JsonSlurper().parseText(json)
    return toPlainMap(parsed)
}

pipeline {
    // Use agent none so we don't hold an executor globally.
    agent none
    stages {
        stage('Gather Node Information') {
            steps {
                script {
                    def results = [:]
                    def nodeNames = []
                    
                    // Include master and all agent nodes.
                    // nodeNames.add("master")
                    for (node in Jenkins.instance.getNodes()) {
                        nodeNames.add(node.getNodeName())
                    }
                    
                    // Sequentially run on each node to avoid deadlock on single-executor nodes.
                    for (def nodeName : nodeNames) {
                        results[nodeName] = [:]
                        node(nodeName) {
                            // Get the system hostname.
                            def host = sh(script: 'hostname', returnStdout: true).trim()
                            // Get the internal IP address.
                            def ip = sh(script: 'hostname -I || hostname -i', returnStdout: true).trim()
                            
                            // Try reading the floating IP from /etc/jenkins_node_info.txt.
                            def floatingIP = "N/A"
                            try {
                                def fileContent = sh(script: 'cat /etc/jenkins_node_info.txt', returnStdout: true).trim()
                                def matcher = fileContent =~ /Floating IP:\s*(\S+)/
                                if (matcher.find()) {
                                    floatingIP = matcher.group(1)
                                }
                            } catch (Exception e) {
                                echo "File /etc/jenkins_node_info.txt not found on ${nodeName}"
                            }
                            
                            // Store the gathered info.
                            results[nodeName].hostname = host
                            results[nodeName].ip = ip
                            results[nodeName].floatingIP = floatingIP
                        }
                    }
                    
                    // Convert the results map to a JSON string and store it in an environment variable.
                    env.NODE_INFO = JsonOutput.toJson(results)
                }
            }
        }
        stage('Generate HTML Report') {
            // Run on a dedicated node (e.g., master) so that file I/O works properly.
            agent { label 'master' }
            steps {
                script {
                    // Parse the JSON and force a deep conversion to a plain map.
                    def nodeResults = parseJson(env.NODE_INFO)
                    def tableRows = ""
                    nodeResults.each { nodeName, info ->
                        tableRows += "<tr><td>${nodeName}</td><td>${info.hostname}</td><td>${info.floatingIP}</td><td>${info.ip}</td></tr>\n"
                    }
                    
                    def htmlContent = """
                    <html>
                        <head>
                            <style>
                                table { border-collapse: collapse; width: 100%; }
                                th, td { border: 1px solid #ddd; padding: 8px; }
                                th { background-color: #4CAF50; color: white; }
                            </style>
                        </head>
                        <body>
                            <h2>Jenkins Node Information</h2>
                            <table>
                                <tr>
                                    <th>Node Name</th>
                                    <th>Hostname</th>
                                    <th>Floating IP</th>
                                    <th>IP Address</th>
                                </tr>
                                ${tableRows}
                            </table>
                        </body>
                    </html>
                    """
                    
                    writeFile file: 'nodeInfo.html', text: htmlContent
                    echo "HTML report generated at ${env.WORKSPACE}/nodeInfo.html"
                }
            }
        }
    }
    post {
        always {
            // Publish the HTML report from a node context.
            node('master') {
                publishHTML(target: [
                    allowMissing: false,
                    alwaysLinkToLastBuild: true,
                    keepAll: true,
                    reportDir: '.',
                    reportFiles: 'nodeInfo.html',
                    reportName: 'Jenkins Nodes Information'
                ])
            }
        }
    }
}
