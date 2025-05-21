/**
 * sendFosqaEmail – fetch on master, send on agent, hide secret via a temp file.
 */
def call(Map args = [:]) {
    if (!args.to) error "sendFosqaEmail: missing 'to' address"

    // defaults
    def subject    = args.subject    ?: "Jenkins Notification"
    def body       = args.body       ?: ""
    def smtpServer = args.smtpServer ?: "mail.fortinet.com"
    def port       = args.port       ?: 465
    def useSsl     = (args.useSsl  != false)
    def useTls     = args.useTls     ?: false
    def username   = args.username   ?: "fosqa"

    // 1) On master, retrieve the password
    def pw = ''
    node('master') {
        pw = sh(script: "/usr/bin/python3 /home/fosqa/resources/tools/get_fosqa_credential.py",
                returnStdout: true).trim()
        echo "🔑 Retrieved SMTP password on master (length=${pw.length()})"
    }

    // 2) Back on the agent: write the secret to a temp file, call test_email.py, then delete it
    node {
        withEnv(["SMTP_PW=${pw}"]) {
            sh '''
            #!/usr/bin/env bash
            set -eu

            # write secret to file
            cat <<EOF > secret.pw
        $SMTP_PW
        EOF

            # DEBUG: compare the MD5 of the env var vs. the file
            echo "MD5(env) : $(printf "%s" "$SMTP_PW" | md5sum)"
            echo "MD5(file): $(md5sum secret.pw | cut -d" " -f1)"

            # send the email
            python3 /home/fosqa/resources/tools/test_email.py \
                --to-addr ''' + args.to + ''' \
                --subject "''' + subject.replace('"','\\"') + '''" \
                --body "'''    + body   .replace('"','\\"') + '''" \
                --smtp-server ''' + smtpServer + ''' \
                --port '''       + port      + ''' \
                ''' + (useSsl ? '--use-ssl' : '') + ''' \
                ''' + (useTls ? '--use-tls' : '') + ''' \
                --username '''  + username  + ''' \
                --password-file secret.pw

            # clean up
            shred -u secret.pw || rm -f secret.pw
            '''
        }
    }
}
