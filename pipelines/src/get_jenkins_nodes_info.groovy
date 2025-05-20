import jenkins.model.Jenkins
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

@NonCPS
// Convert complex JSON structures to plain maps/lists
 def toPlainMap(obj) {
    if (obj instanceof Map) {
        def copy = [:]
        obj.each { k, v -> copy[k] = toPlainMap(v) }
        return copy
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
    
    // Optional: filter to a single node name, or blank for all
    parameters {
        string(
            name: 'NODE_FILTER', 
            defaultValue: '', 
            description: 'Optional: collect only this node (empty = all nodes)'
        )
    }

    stages {
        stage('Gather Node Information') {
            steps {
                script {
                    def results = [:]
                    // Determine which nodes to query based on filter
                    def filter = params.NODE_FILTER?.trim()
                    def nodeNames = filter ? [filter] : Jenkins.instance.getNodes().collect { it.nodeName }

                    for (def nodeName : nodeNames) {
                        // Skip missing or offline agents
                        def comp = Jenkins.instance.getComputer(nodeName)
                        if (comp == null) {
                            echo "❌ Node ${nodeName} not found – skipping."
                            results[nodeName] = [ error: 'Not found' ]
                            continue
                        }
                        if (comp.isOffline() || comp.isTemporarilyOffline()) {
                            echo "⚠️ Node ${nodeName} is offline – skipping."
                            results[nodeName] = [ error: 'Offline' ]
                            continue
                        }

                        // Collect host details
                        try {
                            timeout(time: 20, unit: 'SECONDS') {
                                node(nodeName) {
                                    def host = sh(script: 'hostname', returnStdout: true).trim()
                                    
                                    // Get ens3 IP
                                    def ip = sh(
                                        script: '''
ip addr show ens3 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1
''', returnStdout: true
                                    ).trim()

                                    // Floating IP if available
                                    def floatingIP = 'N/A'
                                    try {
                                        def txt = sh(script: 'cat /etc/jenkins_node_info.txt', returnStdout: true).trim()
                                        def m = (txt =~ /Floating IP:\s*(\S+)/)
                                        if (m.find()) {
                                            floatingIP = m.group(1)
                                        }
                                    } catch(e) {
                                        echo "No /etc/jenkins_node_info.txt on ${nodeName}"
                                    }

                                    // List KVM domains
                                    def kvmOut = sh(script: 'virsh --connect qemu:///system list --all', returnStdout: true).trim()
                                    def domains = []
                                    kvmOut.split("\n").eachWithIndex { line, idx ->
                                        if (idx > 1 && line.trim()) {
                                            def cols = line.trim().split(/\s+/)
                                            if (cols.size() >= 3) domains << "${cols[1]}:${cols[2]}"
                                        }
                                    }

                                    // List Docker containers
                                    def dockOut = sh(script: 'docker ps', returnStdout: true).trim()
                                    def conts = []
                                    dockOut.split("\n").drop(1).each { ln ->
                                        if (ln.trim()) {
                                            def parts = ln.trim().split(/\s+/)
                                            conts << parts[-1]
                                        }
                                    }

                                    results[nodeName] = [
                                        hostname   : host,
                                        ip         : ip,
                                        floatingIP : floatingIP,
                                        KVM_DOMAINS: domains ? domains.join(',') : 'none',
                                        DOCKERs    : conts   ? conts.join(',')   : 'none'
                                    ]
                                }
                            }
                        } catch(err) {
                            echo "Node ${nodeName} not available within timeout, skipping..."
                            results[nodeName] = [ error: 'Timeout collecting info' ]
                        }
                    }
                    
                    env.NODE_INFO = JsonOutput.toJson(results)
                }
            }
        }

        stage('Generate HTML Report') {
            agent { label 'master' }
            steps {
                script {
                    def nodeResults = parseJson(env.NODE_INFO)
                    def tableRows = nodeResults.collect { nodeName, info ->
                        """
                        <tr>
                          <td>${nodeName}</td>
                          <td>${info.hostname ?: ''}</td>
                          <td>${info.floatingIP ?: ''}</td>
                          <td>${info.ip ?: ''}</td>
                          <td>${info.KVM_DOMAINS ?: ''}</td>
                          <td>${info.DOCKERs ?: ''}</td>
                        </tr>
                        """.stripIndent()
                    }.join('\n')

                    def html = """
                    <html>
                      <head>
                        <meta charset=\"utf-8\">
                        <title>Jenkins Node Information</title>
                        <style>
                          body { font-family: Arial; margin: 20px; background: #f9f9f9; }
                          table { border-collapse: collapse; width: 90%; margin: auto; }
                          th, td { padding: 12px; border: 1px solid #ddd; }
                          th { background: #4CAF50; color: white; }
                          tr:nth-child(even) { background: #f2f2f2; }
                          tr:hover { background: #e9e9e9; }
                        </style>
                      </head>
                      <body>
                        <h2 style=\"text-align:center;color:#333;\">Jenkins Node Information</h2>
                        <table>
                          <tr><th>Node Name</th><th>Hostname</th><th>Floating IP</th><th>IP Address (ens3)</th><th>KVM Domains</th><th>Docker Containers</th></tr>
                          ${tableRows}
                        </table>
                      </body>
                    </html>
                    """.stripIndent().trim()

                    writeFile file: 'nodeInfo.html', text: html
                    echo "HTML report generated at ${env.WORKSPACE}/nodeInfo.html"
                }
            }
        }
    }

    post {
        always {
            node('master') {
                publishHTML(target: [
                    allowMissing:          false,
                    alwaysLinkToLastBuild: true,
                    keepAll:               true,
                    reportDir:             '.',
                    reportFiles:           'nodeInfo.html',
                    reportName:            'Jenkins Nodes Information'
                ])
            }
        }
    }
}
