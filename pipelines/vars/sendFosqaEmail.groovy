/**
 * sendFosqaEmail – 
 *  1) fetch creds on master
 *  2) return to the original agent (by label) and send the email from there
 */
def call(Map args = [:]) {
    if (!args.to) error "sendFosqaEmail: missing 'to' address"

    // 1) Figure out which agent we're originally on
    //    Jenkins injects NODE_NAME into env
    def origNode = env.NODE_NAME
    if (!origNode) {
        error "sendFosqaEmail: cannot determine original node (NODE_NAME is unset)"
    }

    // defaults
    def subject    = args.subject    ?: "Jenkins Notification"
    def body       = args.body       ?: ""
    def smtpServer = args.smtpServer ?: "mail.fortinet.com"
    def port       = args.port       ?: 465
    def useSsl     = (args.useSsl  != false)
    def useTls     = args.useTls     ?: false
    def username   = args.username   ?: "fosqa"

    // 2) On master, retrieve the SMTP password
    def pw = ''
    node('master') {
        pw = sh(
            script: "/usr/bin/python3 /home/fosqa/resources/tools/get_fosqa_credential.py",
            returnStdout: true
        ).trim()
        echo "🔑 Retrieved SMTP password on master (length=${pw.length()})"
    }

    // 3) Back on the original agent, send the email
    node(origNode) {
        // stash password in env var for this node
        withEnv(["SMTP_PW=${pw}"]) {
            // write it to a file (no logging of contents)
            sh """
                #!/usr/bin/env bash
                set -eu

                printf '%s' "\$SMTP_PW" > "\$WORKSPACE/secret.pw"

                python3 /home/fosqa/resources/tools/test_email.py \\
                  --to-addr ${args.to} \\
                  --subject "${subject.replace('"','\\"')}" \\
                  --body "${body.replace('"','\\"')}" \\
                  --smtp-server ${smtpServer} \\
                  --port ${port} \\
                  ${useSsl ? '--use-ssl' : ''} \\
                  ${useTls ? '--use-tls' : ''} \\
                  --username ${username} \\
                  --password-file "\$WORKSPACE/secret.pw"

                shred -u "\$WORKSPACE/secret.pw" || rm -f "\$WORKSPACE/secret.pw"
            """.stripIndent()
        }
    }
}
