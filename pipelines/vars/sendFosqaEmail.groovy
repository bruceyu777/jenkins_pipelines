/**
 * sendFosqaEmail – fetch on master, send on agent, hide secret via heredoc.
 */
def call(Map args = [:]) {
    if (!args.to) {
        error "sendFosqaEmail: missing 'to'"
    }

    // defaults
    def subject    = args.subject    ?: "Jenkins Notification"
    def body       = args.body       ?: ""
    def smtpServer = args.smtpServer ?: "mail.fortinet.com"
    def port       = args.port       ?: 465
    def useSsl     = (args.useSsl  != false)
    def useTls     = args.useTls     ?: false
    def username   = args.username   ?: "fosqa"

    // 1) On master, get the password
    def pw = ''
    node('master') {
        pw = sh(
            script: "/usr/bin/python3 /home/fosqa/resources/tools/get_fosqa_credential.py",
            returnStdout: true
        ).trim()
        echo "🔑 Retrieved SMTP password on master (length=${pw.length()})"
    }

    // 2) Back on the agent: heredoc into test_email.py
    // 2) On the agent: write secret to a temp file via heredoc, call test_email.py, then delete it
  node { 
    withEnv(["SMTP_PW=${pw}"]) {
      sh """
        #!/usr/bin/env bash
        set -uo pipefail

        # Create a temp file in the workspace (Jenkins won’t log its contents)
        cat <<EOF > secret.pw
        \$SMTP_PW
EOF

        # Invoke email send using the file
        python3 /home/fosqa/resources/tools/test_email.py \\
          --to-addr ${args.to} \\
          --subject \"${subject.replace('\"','\\\\\"')}\" \\
          --body \"${body.replace('\"','\\\\\"')}\" \\
          --smtp-server ${smtpServer} \\
          --port ${port} \\
          ${useSsl ? '--use-ssl' : ''} \\
          ${useTls ? '--use-tls' : ''} \\
          --username ${username} \\
          --password-file secret.pw

        # Clean up immediately
        shred -u secret.pw || rm -f secret.pw
      """
    }
  }
}
