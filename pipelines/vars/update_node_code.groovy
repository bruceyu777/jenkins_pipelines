def call() {
    pipeline {
        agent { label "${params.NODE_NAME}" }
        stages {
            stage('Update Git Repository') {
                steps {
                    script {
                        withCredentials([
                            usernamePassword(
                                credentialsId: 'LDAP',
                                usernameVariable: 'SVN_USER',
                                passwordVariable: 'SVN_PASS'
                            )
                        ]) {
                            echo "=== Step 1: Local Git update ==="
                            def innerGitCmd = """
                                sudo -u fosqa bash -c '
                                  cd /home/fosqa/resources/tools && \\
                                  if [ -n "\$(git status --porcelain)" ]; then \\
                                    git stash push -m "temporary stash"; \\
                                  fi; \\
                                  git pull; \\
                                  if git stash list | grep -q "temporary stash"; then \\
                                    git stash pop; \\
                                  fi
                                '
                            """
                            try {
                                sh innerGitCmd
                            } catch (Exception e) {
                                echo "Local git pull failed: \${e.getMessage()}. Continuing without updating."
                            }
                        }
                    }
                }
            }
        }
    }
}
