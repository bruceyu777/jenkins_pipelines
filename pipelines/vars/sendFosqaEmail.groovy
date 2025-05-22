// vars/sendFosqaEmail.groovy
def call(Map args = [:]) {
  if (!args.to) error "sendFosqaEmail: missing 'to' address"

  // Determine original node label
  def origNode = env.NODE_NAME
  if (!origNode) error "sendFosqaEmail: NODE_NAME is unset; cannot return to agent"

  // Defaults
  def subject    = args.subject    ?: "Jenkins Notification"
  def body       = args.body       ?: ""
  def smtpServer = args.smtpServer ?: "mail.fortinet.com"
  def port       = args.port       ?: 465
  def useSsl     = (args.useSsl  != false)
  def useTls     = args.useTls     ?: false
  def username   = args.username   ?: "fosqa"

  // Helper to escape single quotes in a shell‐safe way
  def esc = { String s -> s.replace("'", "'\\\\''") }

  // 1) Fetch password on master
  def pw = ''
  node('master') {
    pw = sh(
      script: "/usr/bin/python3 /home/fosqa/resources/tools/get_fosqa_credential.py",
      returnStdout: true
    ).trim()
    echo "🔑 Retrieved SMTP password on master (length=${pw.length()})"
  }

  // 2) Back on original agent
  node(origNode) {
    withEnv(["SMTP_PW=${pw}"]) {
        // Use single-quoted GString so Groovy does not interpolate pw
        sh '''
        #!/usr/bin/env bash
        set -eu

        # write secret to a file WITHOUT ever echoing it
        printf '%s' "$SMTP_PW" > "$WORKSPACE/secret.pw"

        # send the email
        python3 /home/fosqa/resources/tools/test_email.py \
            --to-addr '${args.to}' \
            --subject '${subject.replace("'", "'\\''")}' \
            --body '${body.replace("'", "'\\''")}' \
            --smtp-server '${smtpServer}' \
            --port '${port}' \
            ''' + (useSsl ? "--use-ssl" : "") + ''' \
            ''' + (useTls ? "--use-tls" : "") + ''' \
            --username '${username}' \
            --password-file "$WORKSPACE/secret.pw"

        # clean up
        shred -u "$WORKSPACE/secret.pw" || rm -f "$WORKSPACE/secret.pw"
        '''
    }
    }
}
