pipeline {
  agent { label 'master' }

  parameters {
    text(
      name: 'NODES_JSON',
      defaultValue: '''[
  {"name":"all-in-one-node21","ip":"10.96.234.49"},
  {"name":"all-in-one-node22","ip":"10.96.234.41"}
]''',
      description: 'JSON array of { name, ip } objects'
    )
    string(
      name: 'JENKINS_URL',
      defaultValue: 'https://releaseqa-stackjenkins.corp.fortinet.com',
      description: 'Jenkins Master URL'
    )
    booleanParam(
      name: 'CLEANUP',
      defaultValue: true,
      description: 'Cleanup previous agent install?'
    )
  }

  stages {
    stage('Dispatch Downstreams') {
      steps {
        script {
          // Use built-in readJSON for serializable objects
          def nodes = readJSON text: params.NODES_JSON
          if (!(nodes instanceof List) || nodes.isEmpty()) {
            error "NODES_JSON must be a non-empty JSON array"
          }

          // Sequentially trigger downstream for each node
          for (def n : nodes) {
            def rawName  = n.name.trim()
            def nodeName = rawName.replaceFirst(/^all-in-one-/, '')
            def nodeIp   = n.ip.trim()

            echo "Triggering add_new_jenkins_node for ${nodeName}@${nodeIp}"
            build(
              job: 'add_new_jenkins_node',
              wait: true,
              parameters: [
                string(name: 'NODE_NAME',   value: nodeName),
                string(name: 'NODE_IP',     value: nodeIp),
                string(name: 'JENKINS_URL', value: params.JENKINS_URL),
                booleanParam(name: 'CLEANUP', value: params.CLEANUP)
              ]
            )
          }
        }
      }
    }
  }
}
