// vars/sendFosqaEmail.groovy
def call(Map args = [:]) {
  if (!args.to) error "sendFosqaEmail: missing 'to' address"

  // remember which agent we started on
  def origNode = env.NODE_NAME
  if (!origNode) error "sendFosqaEmail: NODE_NAME is unset"

  // defaults
  def subject    = args.subject    ?: "Jenkins Notification"
  def body       = args.body       ?: ""
  def smtpServer = args.smtpServer ?: "mail.fortinet.com"
  def port       = args.port       ?: 465
  def useSsl     = args.useSsl     != false
  def useTls     = args.useTls     ?: false
  def username   = args.username   ?: "fosqa"

  // a little helper to shell-escape single quotes
  def esc = { String s -> s.replace("'", "'\\\\''") }

  // 1) get the password on master
  def pw = ''
  node('master') {
    pw = sh(
      script: "/usr/bin/python3 /home/fosqa/resources/tools/get_fosqa_credential.py",
      returnStdout: true
    ).trim()
    echo "🔑 Retrieved SMTP password on master (length=${pw.length()})"
  }

  // 2) back on the original agent: write it, send it—and hide the commands
  node(origNode) {
    withEnv(["SMTP_PW=${pw}"]) {
      // Turn off echo for this block so nothing leaks
      sh script: """
        #!/usr/bin/env bash
        set -eu
        # write the secret to disk (Jenkins WON’T log this command)
        printf '%s' "\$SMTP_PW" > "\$WORKSPACE/secret.pw"

        # invoke the email sender
        python3 /home/fosqa/resources/tools/test_email.py \\
          --to-addr '${esc(args.to)}' \\
          --subject   '${esc(subject)}' \\
          --body      '${esc(body)}' \\
          --smtp-server '${esc(smtpServer)}' \\
          --port      ${port} \\
          ${useSsl? '--use-ssl' : ''} \\
          ${useTls? '--use-tls' : ''} \\
          --username '${esc(username)}' \\
          --password-file "\$WORKSPACE/secret.pw"

        # clean up
        shred -u "\$WORKSPACE/secret.pw" || rm -f "\$WORKSPACE/secret.pw"
      """.stripIndent(), echo: false
    }
  }
}
