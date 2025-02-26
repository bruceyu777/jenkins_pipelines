def call(String workspace, String buildNumber) {
    println "Publish Test Results ..."
    def reportRelativePath = "${buildNumber}/report/report.xml"

    // Check if the specific report.xml file exists
    def reportExists = sh(script: "test -f ${workspace}/${reportRelativePath} && echo 'exists' || echo 'not exists'", returnStdout: true).trim()

    if (reportExists == 'exists') {
        try {
            // Use the relative path within the workspace
            junit "${reportRelativePath}"
        } catch (Exception err) {
            echo "junit report.xml, Caught: ${err}"
        }
    } else {
        echo "No report.xml found at ${workspace}/${reportRelativePath}. Skipping test results publishing."
    }
}
