// vars/sendFosqaEmail.groovy
def call(Map args = [:]) {
    if (!args.to) error "sendFosqaEmail: missing 'to'"

    // defaults
    def subject    = args.subject    ?: "Jenkins Notification"
    def body       = args.body       ?: ""
    def smtpServer = args.smtpServer ?: "mail.fortinet.com"
    def port       = args.port       ?: 465
    def useSsl     = (args.useSsl  != false)
    def useTls     = args.useTls     ?: false
    def username   = args.username   ?: "fosqa"

    // 1) Fetch on master
    def pw = ''
    node('master') {
      pw = sh(
        script: "/usr/bin/python3 /home/fosqa/resources/tools/get_fosqa_credential.py",
        returnStdout: true
      ).trim()
      echo "🔑 Retrieved SMTP password on master (length=${pw.length()})"
    }

    // 2) Back on agent: write secret to a file via heredoc (no indent!), debug MD5, send email
    node {
      withEnv(["SMTP_PW=${pw}"]) {
        sh """
        #!/usr/bin/env bash
        set -eu

        # 1) write secret to a file WITHOUT newline
        printf '%s' "\$SMTP_PW" > "\$WORKSPACE/secret.pw"

        # 2) DEBUG: show exactly what's in env vs. file
        echo ">>> ENV PW : [\$SMTP_PW]"
        echo ">>> FILE PW: [\$(cat \"\$WORKSPACE/secret.pw\")]"



        # --- debug: compare MD5s ---
        echo "MD5(env) : \$(printf "%s" "\$SMTP_PW" | md5sum | cut -d' ' -f1)"
        echo "MD5(file): \$(md5sum "\$WORKSPACE/secret.pw" | cut -d' ' -f1)"

        # --- send the email ---
        python3 /home/fosqa/resources/tools/test_email.py \
          --to-addr ${args.to} \
          --subject "${subject.replace('"','\\"')}" \
          --body "${body.replace('"','\\"')}" \
          --smtp-server ${smtpServer} \
          --port ${port} \
          ${useSsl ? '--use-ssl' : ''} \
          ${useTls ? '--use-tls' : ''} \
          --username ${username} \
          --password-file "\$WORKSPACE/secret.pw"

        # --- clean up ---
        shred -u "\$WORKSPACE/secret.pw" || rm -f "\$WORKSPACE/secret.pw"
        """.stripIndent()
      }
    }
}
