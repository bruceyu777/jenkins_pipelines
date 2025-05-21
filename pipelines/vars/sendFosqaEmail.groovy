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
    node {  
        withEnv(["SMTP_PW=${pw}"]) {
            sh """
                cat <<EOF | python3 /home/fosqa/resources/tools/test_email.py \\
                  --to-addr ${args.to} \\
                  --subject \"${subject.replace('\"','\\\\\"')}\" \\
                  --body \"${body.replace('\"','\\\\\"')}\" \\
                  --smtp-server ${smtpServer} \\
                  --port ${port} \\
                  ${useSsl? '--use-ssl' : ''} \\
                  ${useTls? '--use-tls' : ''} \\
                  --username ${username} \\
                  --password-stdin
                \$SMTP_PW
EOF
            """
        }
    }
}
