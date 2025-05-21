/**
 * sendFosqaEmail step
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
    // validate
    if (!args.to) {
        error "sendFosqaEmail: missing required parameter 'to'"
    }

    // defaults
    def subject    = args.subject    ?: "Jenkins Notification"
    def body       = args.body       ?: ""
    def smtpServer = args.smtpServer ?: "mail.fortinet.com"
    def port       = args.port       ?: 465
    def useSsl     = args.useSsl     != false  // default true
    def useTls     = args.useTls     ?: false
    def username   = args.username   ?: "fosqa"

    // 1) fetch the password via your Vault helper
    def pw = sh(
        script: "/usr/bin/python3 /home/fosqa/resources/tools/get_fosqa_credential.py",
        returnStdout: true
    ).trim()

    // 2) invoke the email script
    sh """
      /usr/bin/python3 /home/fosqa/resources/tools/test_email.py \
        --to-addr ${args.to} \
        --subject "${subject.replace('"','\\"')}" \
        --body "${body.replace('"','\\"')}" \
        --smtp-server ${smtpServer} \
        --port ${port} \
        ${useSsl? '--use-ssl' : ''} \
        ${useTls? '--use-tls' : ''} \
        --username ${username} \
        --password "${pw}"
    """
}
