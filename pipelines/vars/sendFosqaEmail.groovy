/**
 * sendFosqaEmail step
 *
 * 1) On master: run get_fosqa_credential.py to retrieve the SMTP password  
 * 2) Back on the original agent: call test_email.py with that password
 *
 * @param to         – recipient email address (required)
 * @param subject    – email subject (default: “Jenkins Notification”)
 * @param body       – HTML body (default: empty)
 * @param smtpServer – SMTP host (default: mail.fortinet.com)
 * @param port       – SMTP port (default: 465)
 * @param useSsl     – true to use SSL (default: true)
 * @param useTls     – true to use STARTTLS (default: false)
 * @param username   – SMTP username (default: “fosqa”)
 */
def call(Map args = [:]) {
    if (!args.to) {
        error "sendFosqaEmail: missing required parameter 'to'"
    }

    // set defaults
    def subject    = args.subject    ?: "Jenkins Notification"
    def body       = args.body       ?: ""
    def smtpServer = args.smtpServer ?: "mail.fortinet.com"
    def port       = args.port       ?: 465
    def useSsl     = (args.useSsl  != false)
    def useTls     = args.useTls     ?: false
    def username   = args.username   ?: "fosqa"

    // 1) Fetch the password on master
    def pw = ''
    node('master') {
        pw = sh(
            script: "/usr/bin/python3 /home/fosqa/resources/tools/get_fosqa_credential.py",
            returnStdout: true
        ).trim()
        echo "🔑 Retrieved SMTP password (length=${pw.length()}) on master"
    }

    // 2) Back on the original agent, send the email
        node(params.NODE_NAME) {
        // Now on the slave
        withEnv(["SMTP_PW=${pw}"]) {
        sh '''
            bash -c '
            python3 /home/fosqa/resources/tools/test_email.py \
                --to-addr ${args.to} \
                --subject "${args.subject.replace('"','\\"')}" \
                --body "${args.body.replace('"','\\"')}" \
                --smtp-server ${args.smtpServer} \
                --port ${args.port} \
                ${args.useSsl? "--use-ssl" : ""} \
                ${args.useTls? "--use-tls" : ""} \
                --username ${args.username} \
                --password-stdin <<<"$SMTP_PW"
            '
        '''
        }
    }
}
