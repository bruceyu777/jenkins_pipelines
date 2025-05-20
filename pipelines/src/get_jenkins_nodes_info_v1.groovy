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
    agent none
    stages {
        stage('Gather Node Information') {
            steps {
                script {
                    def results = [:]
                    def nodeNames = []
                    
                    // Include all agent nodes.
                    for (node in Jenkins.instance.getNodes()) {
                        nodeNames.add(node.getNodeName())
                    }

                    for (def nodeName : nodeNames) {
                        results[nodeName] = [:]
                        try {
                            // Wait up to 10 seconds for the node to become available.
                            timeout(time: 10, unit: 'SECONDS') {
                                node(nodeName) {
                                    // Get the system hostname.
                                    def host = sh(script: 'hostname', returnStdout: true).trim()
                                    
                                    // Retrieve only the ens3 interface IP address using a triple-single-quoted string.
                                    def ip = sh(script: '''ip addr show ens3 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1''', returnStdout: true).trim()
                                    
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
                                    
                                    // Get KVM domain information using the system connection.
                                    def kvmOutput = sh(script: 'virsh --connect qemu:///system list --all', returnStdout: true).trim()
                                    def kvmDomains = []
                                    kvmOutput.split("\n").eachWithIndex { line, idx ->
                                        // Skip header lines.
                                        if (idx > 1 && line.trim()) {
                                            def columns = line.trim().split(/\s+/)
                                            if (columns.size() >= 3) {
                                                kvmDomains << "${columns[1]}:${columns[2]}"
                                            }
                                        }
                                    }
                                    results[nodeName].KVM_DOMAINS = kvmDomains ? kvmDomains.join(',') : "none"
                                    
                                    // Get Docker container information.
                                    def dockerOutput = sh(script: 'docker ps', returnStdout: true).trim()
                                    def dockerContainers = []
                                    def dockerLines = dockerOutput.split("\n")
                                    if (dockerLines.size() > 1) {
                                        dockerLines.drop(1).each { line ->
                                            if (line.trim()) {
                                                def cols = line.trim().split(/\s+/)
                                                dockerContainers << cols[-1]
                                            }
                                        }
                                    }
                                    results[nodeName].DOCKERs = dockerContainers ? dockerContainers.join(',') : "none"
                                    
                                    // Store gathered info.
                                    results[nodeName].hostname = host
                                    results[nodeName].ip = ip
                                    results[nodeName].floatingIP = floatingIP
                                }
                            }
                        } catch (err) {
                            echo "Node ${nodeName} not available within timeout, skipping..."
                            results[nodeName] = [error: "Node not available within timeout"]
                        }
                    }

                    // Convert the results map to a JSON string and store it in an environment variable.
                    env.NODE_INFO = JsonOutput.toJson(results)
                }
            }
        }
        stage('Generate HTML Report') {
            agent { label 'master' }
            steps {
                script {
                    def nodeResults = parseJson(env.NODE_INFO)
                    def tableRows = ""
                    nodeResults.each { nodeName, info ->
                        tableRows += "<tr>"
                        tableRows += "<td>${nodeName}</td>"
                        tableRows += "<td>${info.hostname}</td>"
                        tableRows += "<td>${info.floatingIP}</td>"
                        tableRows += "<td>${info.ip}</td>"
                        tableRows += "<td>${info.KVM_DOMAINS}</td>"
                        tableRows += "<td>${info.DOCKERs}</td>"
                        tableRows += "</tr>\n"
                    }
                    
                    def htmlContent = """
                    <html>
                        <head>
                            <meta charset="utf-8">
                            <title>Jenkins Node Information</title>
                            <style>
                                body {
                                    font-family: Arial, sans-serif;
                                    margin: 20px;
                                    background-color: #f9f9f9;
                                }
                                h2 {
                                    text-align: center;
                                    color: #333;
                                }
                                table {
                                    border-collapse: collapse;
                                    width: 90%;
                                    margin: 20px auto;
                                    box-shadow: 0 2px 8px rgba(0, 0, 0, 0.1);
                                }
                                th, td {
                                    padding: 12px 15px;
                                    border: 1px solid #ddd;
                                    text-align: left;
                                }
                                th {
                                    background-color: #4CAF50;
                                    color: white;
                                }
                                tr:nth-child(even) {
                                    background-color: #f2f2f2;
                                }
                                tr:hover {
                                    background-color: #e9e9e9;
                                }
                            </style>
                        </head>
                        <body>
                            <h2>Jenkins Node Information</h2>
                            <table>
                                <tr>
                                    <th>Node Name</th>
                                    <th>Hostname</th>
                                    <th>Floating IP</th>
                                    <th>IP Address (ens3)</th>
                                    <th>KVM Domains</th>
                                    <th>Docker Containers</th>
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
