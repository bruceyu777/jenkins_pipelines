// vars/sendFosqaEmail.groovy
def call(Map args = [:]) {
  if (!args.to)       error "sendFosqaEmail: missing 'to' address"
  if (!env.NODE_NAME) error "sendFosqaEmail: NODE_NAME is unset; cannot return to agent"
  def origNode = env.NODE_NAME

  // Defaults + esc helper
  def subject         = args.subject    ?: "Jenkins Notification"
  def body            = args.body       ?: ""
  def smtpServer      = args.smtpServer ?: "mail.fortinet.com"
  def port            = args.port       ?: 465
  def useSsl          = args.useSsl  != false
  def useTls          = args.useTls     ?: false
  def username        = args.username   ?: "fosqa"
  def fallbackFrom    = args.fallbackFromAddr ?: "yzhengfeng@fortinet.com"

  def esc = { String s -> s.replace("'", "'\\\\''") }

  // Create safe versions of subject and body by removing line breaks.
  def safeSubject = esc(subject.replaceAll("\\n", " "))

  // 1) Fetch Vault‐stored SMTP password on master Node: Only the master node (by IP whitelist) can access the Vault.
  def pw = ''
  node('master') {
    pw = sh(
      script: "/usr/bin/python3 /home/fosqa/resources/tools/get_fosqa_credential.py",
      returnStdout: true
    ).trim().replaceAll(/^"|"$/, '')  // strip any stray quotes
    echo "🔑 Retrieved SMTP password on master (length=${pw.length()})"
  }

  // 2) Back on the original agent: write pw, grab LDAP creds, then call Python script.
  node(origNode) {
    // stash the SMTP pw in an env var and write it out
    withEnv(["SMTP_PW=${pw}"]) {
      writeFile file: 'secret.pw', text: pw

      // Bind your LDAP credentials from Jenkins → SVN_USER / SVN_PASS
      withCredentials([usernamePassword(credentialsId: 'LDAP',
                                        usernameVariable: 'SVN_USER',
                                        passwordVariable: 'SVN_PASS')]) {

        // Write HTML content to temporary file
        def tempHtmlFile = 'email_body.html'
        writeFile file: tempHtmlFile, text: body

        // Run the Python script, passing both primary and fallback flags.
        sh(script: """
          #!/usr/bin/env bash
          set -eu

          python3 /home/fosqa/resources/tools/test_email.py \\
            --to-addr  '${esc(args.to)}' \\
            --subject  '${safeSubject}' \\
            --body-file '${tempHtmlFile}' \\
            --smtp-server '${esc(smtpServer)}' \\
            --port      '${port}' \\
            ${useSsl ? '--use-ssl' : ''} \\
            ${useTls ? '--use-tls' : ''} \\
            --username '${esc(username)}' \\
            --password-file 'secret.pw' \\
            --fallback-username '$SVN_USER' \\
            --fallback-password '$SVN_PASS' \\
            --fallback-from-addr '${esc(fallbackFrom)}'
        """.stripIndent())

        // Clean up temp file
        sh "rm -f ${tempHtmlFile}"

        // Cleanup the secret file
        sh "shred -u secret.pw || rm -f secret.pw"
      }
    }
  }
}
