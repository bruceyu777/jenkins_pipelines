/**
 * SVN Update Helper
 *
 * Handles SVN checkout and update operations with marker file tracking
 * to detect when TEST_CASE_FOLDER changes.
 */

/**
 * Update or checkout SVN repository with marker file tracking
 *
 * @param config Map containing:
 *   - LOCAL_LIB_DIR: Local library directory name
 *   - SVN_BRANCH: SVN branch name
 *   - FEATURE_NAME: Feature name
 *   - TEST_CASE_FOLDER: Test case folder (e.g., 'testcase' or 'testcase_v1')
 *   - SVN_USER: SVN username (from credentials)
 *   - SVN_PASS: SVN password (from credentials)
 *
 * @return Map with keys:
 *   - folderPath: Path to the checked out folder
 *   - action: 'checkout' or 'update'
 *   - reason: Reason for the action taken
 */
def updateOrCheckout(Map config) {
    def baseTestDir = "/home/fosqa/${config.LOCAL_LIB_DIR}/testcase/${config.SVN_BRANCH}"
    sh "sudo mkdir -p ${baseTestDir} && sudo chmod -R 777 ${baseTestDir}"

    def folderPath = "${baseTestDir}/${config.FEATURE_NAME}"
    def markerFile = "${folderPath}/.test_case_folder_marker"

    echo "=== SVN Update/Checkout Configuration ==="
    echo "Folder path: ${folderPath}"
    echo "TEST_CASE_FOLDER: ${config.TEST_CASE_FOLDER}"
    echo "SVN_BRANCH: ${config.SVN_BRANCH}"
    echo "Marker file: ${markerFile}"

    // Check if folder exists
    def folderExists = sh(
        script: "if [ -d '${folderPath}' ]; then echo exists; else echo notexists; fi",
        returnStdout: true
    ).trim()

    def needsCheckout = false
    def reason = ""

    if (folderExists == "notexists") {
        needsCheckout = true
        reason = "Folder does not exist"
    } else {
        // Check if the marker file exists and contains the current TEST_CASE_FOLDER
        def markerExists = sh(
            script: "if [ -f '${markerFile}' ]; then echo exists; else echo notexists; fi",
            returnStdout: true
        ).trim()

        if (markerExists == "notexists") {
            needsCheckout = true
            reason = "Marker file missing - cannot verify source"
        } else {
            def storedTestCaseFolder = sh(
                script: "cat '${markerFile}' 2>/dev/null || echo ''",
                returnStdout: true
            ).trim()

            echo "Stored TEST_CASE_FOLDER: '${storedTestCaseFolder}'"
            echo "Current TEST_CASE_FOLDER: '${config.TEST_CASE_FOLDER}'"

            if (storedTestCaseFolder != config.TEST_CASE_FOLDER) {
                needsCheckout = true
                reason = "TEST_CASE_FOLDER changed from '${storedTestCaseFolder}' to '${config.TEST_CASE_FOLDER}'"
            }
        }
    }

    echo "Folder exists: ${folderExists}"
    echo "Needs checkout: ${needsCheckout}"
    if (needsCheckout) {
        echo "Reason: ${reason}"
    }

    def action = ""

    if (needsCheckout) {
        echo "=== Performing fresh SVN checkout ==="
        if (folderExists == "exists") {
            echo "Removing existing folder due to: ${reason}"
            sh "sudo rm -rf '${folderPath}'"
        }

        // Use shared runWithRetry helper
        runWithRetry(4, [5, 15, 45], """
            cd ${baseTestDir} && \
            sudo svn checkout \
            https://qa-svn.corp.fortinet.com/svn/qa/FOS/${config.TEST_CASE_FOLDER}/${config.SVN_BRANCH}/${config.FEATURE_NAME} \
            --username "\$SVN_USER" --password "\$SVN_PASS" --non-interactive
        """)

        // Create marker file to track which TEST_CASE_FOLDER was used
        sh """
            echo '${config.TEST_CASE_FOLDER}' | sudo tee '${markerFile}' > /dev/null
            sudo chmod 666 '${markerFile}'
        """
        echo "✅ Created marker file: ${markerFile} with value: ${config.TEST_CASE_FOLDER}"
        action = "checkout"

    } else {
        echo "=== Performing SVN update on existing folder ==="

        // Use shared runWithRetry helper
        runWithRetry(4, [5, 15, 45], """
            cd ${folderPath} && \
            sudo svn update --username "\$SVN_USER" --password "\$SVN_PASS" --non-interactive
        """)

        action = "update"
        reason = "Folder exists and TEST_CASE_FOLDER matches"
    }

    // Ensure proper permissions
    sh "sudo chmod -R 777 ${baseTestDir}"

    echo "✅ SVN ${action} completed successfully"

    return [
        folderPath: folderPath,
        action: action,
        reason: reason
    ]
}

/**
 * Main entry point for calling from other pipelines
 * Wraps updateOrCheckout with credentials
 */
def call(Map config) {
    withCredentials([
        usernamePassword(
            credentialsId: 'LDAP',
            usernameVariable: 'SVN_USER',
            passwordVariable: 'SVN_PASS'
        )
    ]) {
        return updateOrCheckout(config)
    }
}
