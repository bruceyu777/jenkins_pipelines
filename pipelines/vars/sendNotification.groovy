def call(String buildName, String buildStatus, def env, String buildNumber, String sendTo, String config, String dutRelease, String dutBuildNumber) {
    // Ensure essential parameters are not null or empty
    if (!sendTo || !buildName  || !buildNumber) {
        error("Missing required parameters for email notification.")
    }

    def subjectLine = "${buildName}: $config [$dutRelease B$dutBuildNumber] - ${buildStatus}"
    def emailBody = """
        <html>
        <body>
            <p>View the detailed <strong>artifacts, report.html and logs</strong> via the following link:</p>
            <p><a href="${env.JOB_URL}${buildNumber}">View Logs and Reports</a></p>
        </body>
        </html>
    """

    try {
        emailext(
            from: 'fosqa@fortinet.com',
            to: sendTo,
            subject: subjectLine,
            body: emailBody,
            mimeType: 'text/html'
        )
        echo "Email sent successfully to ${sendTo}"
    } catch (Exception e) {
        echo "Failed to send email: ${e.message}"
    }
}
