#!/usr/bin/env bash
set -euo pipefail

# Adjust these if needed:
JENKINS_URL="http://localhost:8080"
JENKINS_CLI="/home/fosqa/jenkins-master/pipelines/tools/jenkins-cli.jar"
AUTH="fosqa:110dec5c2d2974a67968074deafccc1414"

# 1) Fetch all agent names starting with "all-in-one-"
old_names=$(cat << 'EOF' | java -jar "$JENKINS_CLI" -s "$JENKINS_URL" -auth "$AUTH" groovy =
import jenkins.model.Jenkins
Jenkins.instance.nodes
        .findAll { it.nodeName.startsWith('all-in-one-') }
        .each { println it.nodeName }
EOF
)

# 2) Loop and rename
for old in $old_names; do
  new=${old#all-in-one-}
  echo "Renaming '$old' → '$new'..."
  java -jar "$JENKINS_CLI" -s "$JENKINS_URL" -auth "$AUTH" rename-node "$old" "$new"
done
