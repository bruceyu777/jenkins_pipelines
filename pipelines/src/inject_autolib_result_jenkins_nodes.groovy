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
        string(
            name: 'RELEASE', 
            defaultValue: '7.6.4', 
            description: 'Release of the autolib result to inject (e.g., 7.6.4)'
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

                                    script {
                                        withCredentials([usernamePassword(credentialsId: 'LDAP', usernameVariable: 'SVN_USER', passwordVariable: 'SVN_PASS')]) {
                                            // Local Git update
                                            echo "=== Step 1: Local Git update ==="
                                            def innerGitCmd = "sudo -u fosqa bash -c 'cd /home/fosqa/resources/tools && " +
                                                            "if [ -n \"\$(git status --porcelain)\" ]; then git stash push -m \"temporary stash\"; fi; " +
                                                            "git pull; " +
                                                            "if git stash list | grep -q \"temporary stash\"; then git stash pop; fi'"
                                            echo "Executing local git pull command: ${innerGitCmd}"
                                            try {
                                                sh innerGitCmd
                                            } catch (Exception e) {
                                                echo "Local git pull failed: ${e.getMessage()}. Continuing without updating."
                                            }

                                        }
                                    }
                                    
                                    echo "Inject autolib result from node: ${nodeName}"

                                    // inject inside a try/catch so failures are non-fatal
                                    try {
                                    sh """
                                        cd /home/fosqa/resources/tools
                                        . /home/fosqa/resources/tools/venv/bin/activate
                                        pip install -r requirements.txt

                                        sudo /home/fosqa/resources/tools/venv/bin/python3 \
                                        inject_autolib_result.py \
                                            -r ${params.RELEASE} \
                                    """
                                    echo "✅ inject_autolib_result.py succeeded"
                                    } catch (err) {
                                    echo "⚠️ inject_autolib_result.py failed, but pipeline will continue"
                                    }

                                }
                            }
                        } catch(err) {
                            echo "Node ${nodeName} timed out – skipping."
                            results[nodeName] = [ error: 'Timeout' ]
                        }
                    }

                }
            }
        }

}
}
