// vars/sendFosqaEmail.groovy
def call(Map args = [:]) {
  if (!args.to) error "sendFosqaEmail: missing 'to' address"

  // 1) Figure out where we started
  def origNode = env.NODE_NAME
  if (!origNode) error "sendFosqaEmail: NODE_NAME is unset; cannot return to agent"

  // 2) Defaults + esc helper
  def subject    = args.subject    ?: "Jenkins Notification"
  def body       = args.body       ?: ""
  def smtpServer = args.smtpServer ?: "mail.fortinet.com"
  def port       = args.port       ?: 465
  def useSsl     = (args.useSsl  != false)
  def useTls     = args.useTls     ?: false
  def username   = args.username   ?: "fosqa"

  // Helper to escape single quotes
  def esc = { String s -> s.replace("'", "'\\\\''") }

  // 3) On master: fetch the SMTP password
  def pw = ''
  node('master') {
    pw = sh(
      script: "/usr/bin/python3 /home/fosqa/resources/tools/get_fosqa_credential.py",
      returnStdout: true
    ).trim()
    echo "🔑 Retrieved SMTP password on master (length=${pw.length()})"
  }

  // 4) Back on the original agent: write it safely & send email
  node(origNode) {
    withEnv(["SMTP_PW=${pw}"]) {
      // WRITE the password to a file without any shell echo of its contents
      writeFile file: 'secret.pw', text: pw

      // Now invoke the email script—turn off echo so none of this leaks
      sh(script: """
        #!/usr/bin/env bash
        set -eu

        python3 /home/fosqa/resources/tools/test_email.py \\
          --to-addr  '${esc(args.to)}' \\
          --subject   '${esc(subject)}' \\
          --body      '${esc(body)}' \\
          --smtp-server '${esc(smtpServer)}' \\
          --port      '${port}' \\
          ${useSsl ? '--use-ssl' : ''} \\
          ${useTls ? '--use-tls' : ''} \\
          --username '${esc(username)}' \\
          --password-file 'secret.pw'

        # clean up immediately
        shred -u secret.pw || rm -f secret.pw
      """.stripIndent(), echo: false)
    }
  }
}
