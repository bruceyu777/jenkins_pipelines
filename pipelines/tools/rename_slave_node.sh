#!/usr/bin/env bash
set -euo pipefail

# Jenkins connection info
JENKINS_URL="http://localhost:8080"
JENKINS_CLI="./jenkins-cli.jar"
AUTH="fosqa:110dec5c2d2974a67968074deafccc1414"

# 1) Get list of old names
old_names=$(cat <<'EOF' | java -jar "$JENKINS_CLI" -s "$JENKINS_URL" -auth "$AUTH" groovy =
import jenkins.model.Jenkins
Jenkins.instance.nodes
        .findAll { it.nodeName.startsWith('all-in-one-') }
        .each { println it.nodeName }
EOF
)

# 2) Loop through each
for old in $old_names; do
  new=${old#all-in-one-}
  echo
  echo "===== Renaming '$old' → '$new' ====="

  # a) Export config
  tmpfile=$(mktemp)
  java -jar "$JENKINS_CLI" -s "$JENKINS_URL" -auth "$AUTH" get-node "$old" > "$tmpfile"

  # b) Replace <name> tag
  sed -i "s|<name>$old</name>|<name>$new</name>|g" "$tmpfile"

  # c) Create new node from modified XML
  java -jar "$JENKINS_CLI" -s "$JENKINS_URL" -auth "$AUTH" create-node "$new" < "$tmpfile"

  # d) Delete the old node
  java -jar "$JENKINS_CLI" -s "$JENKINS_URL" -auth "$AUTH" delete-node "$old"

  rm "$tmpfile"
done

echo
echo "All done! Your agents are now named without the 'all-in-one-' prefix."
