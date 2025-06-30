import jenkins.model.Jenkins
import groovy.json.JsonOutput
import groovy.json.JsonSlurper

@NonCPS
List<String> getNodeNames(String filter) {
    if (filter) {
        return [filter]
    }
    return Jenkins.instance.getNodes().collect { it.nodeName }
}

@NonCPS
boolean nodeExists(String name) {
    return Jenkins.instance.getComputer(name) != null
}

@NonCPS
boolean nodeOnline(String name) {
    def comp = Jenkins.instance.getComputer(name)
    return comp != null && !comp.isOffline() && !comp.isTemporarilyOffline()
}

@NonCPS
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
                    def filter    = params.NODE_FILTER?.trim()
                    def nodeNames = getNodeNames(filter)

                    for (String nodeName : nodeNames) {
                        if (!nodeExists(nodeName)) {
                            echo "❌ Node ${nodeName} not found – skipping."
                            results[nodeName] = [ error: 'Not found' ]
                            continue
                        }
                        if (!nodeOnline(nodeName)) {
                            echo "⚠️ Node ${nodeName} is offline – skipping."
                            results[nodeName] = [ error: 'Offline' ]
                            continue
                        }

                        try {
                            timeout(time: 20, unit: 'SECONDS') {
                                node(nodeName) {
                                    def host = sh(script: 'hostname', returnStdout: true).trim()
                                    def ip   = sh(
                                        script: '''
ip addr show ens3 | grep 'inet ' | awk '{print $2}' | cut -d/ -f1
''', returnStdout: true
                                    ).trim()

                                    def floatingIP = 'N/A'
                                    try {
                                        def txt = sh(script: 'cat /etc/jenkins_node_info.txt', returnStdout: true).trim()
                                        def m   = (txt =~ /Floating IP:\s*(\S+)/)
                                        if (m.find()) floatingIP = m.group(1)
                                    } catch(e) {
                                        echo "No /etc/jenkins_node_info.txt on ${nodeName}"
                                    }

                                    def kvmOut = sh(script: 'virsh --connect qemu:///system list --all', returnStdout: true).trim()
                                    def domains = []
                                    kvmOut.split('\n').eachWithIndex { line, idx ->
                                        if (idx > 1 && line.trim()) {
                                            def cols = line.trim().split(/\s+/)
                                            if (cols.size() >= 3) domains << "${cols[1]}:${cols[2]}"
                                        }
                                    }

                                    def dockOut = sh(script: 'docker ps', returnStdout: true).trim()
                                    def conts = []
                                    dockOut.split('\n').drop(1).each { ln ->
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
                            echo "Node ${nodeName} timed out – skipping."
                            results[nodeName] = [ error: 'Timeout' ]
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
                    def rows = nodeResults.collect { n, info ->
                        "<tr><td>${n}</td><td>${info.hostname ?: ''}</td><td>${info.floatingIP ?: ''}</td><td>${info.ip ?: ''}</td><td>${info.KVM_DOMAINS ?: ''}</td><td>${info.DOCKERs ?: ''}</td></tr>"
                    }.join('\n')

                    def html = """
<html>
  <head>
    <meta charset="utf-8">
    <title>Jenkins Node Information</title>
    <style>
      body { font-family:Arial; background:#f9f9f9; padding:20px; }
      table{width:90%;margin:auto;border-collapse:collapse;}
      th,td{padding:12px;border:1px solid #ddd;}
      th{background:#4CAF50;color:#fff;}
      tr:nth-child(even){background:#f2f2f2;} tr:hover{background:#e9e9e9;}
    </style>
  </head>
  <body>
    <h2 style="text-align:center;color:#333;">Jenkins Node Information</h2>
    <table>
      <tr><th>Node</th><th>Host</th><th>Floating IP</th><th>IP</th><th>KVM Domains</th><th>Docker</th></tr>
      ${rows}
    </table>
  </body>
</html>
""".trim()

                    writeFile file:'nodeInfo.html', text:html
                    echo "HTML report generated at ${env.WORKSPACE}/nodeInfo.html"
                }
            }
        }
    }

    post {
        always {
            node('master') {
                publishHTML(target:[
                    allowMissing:false, alwaysLinkToLastBuild:true,
                    keepAll:true, reportDir:'.', reportFiles:'nodeInfo.html', reportName:'Jenkins Nodes Information'
                ])
            }
        }
    }
}
