def call(List dockerReportFoldersList, String workspace, String buildNumber) {
    dockerReportFoldersList.each { folderPath ->
        try {
            withCredentials([string(credentialsId: 'sudoPassword', variable: 'SUDO_PASS')]) {
                sh """
                echo $SUDO_PASS | sudo -S chmod -R 777 ${folderPath}/artifacts/screenshots || true
                echo $SUDO_PASS | sudo -S mkdir -p ${workspace}/${buildNumber}/report
                echo $SUDO_PASS | sudo -S mkdir -p ${workspace}/${buildNumber}/report/artifacts/screenshots
                echo $SUDO_PASS | sudo -S cp -r ${folderPath}/artifacts/screenshots/* ${workspace}/${buildNumber}/report/artifacts/screenshots || echo 'No screenshot files found'
                echo $SUDO_PASS | sudo -S cp -r ${folderPath}/report.html ${workspace}/${buildNumber}/report || echo 'No report files found'
                echo $SUDO_PASS | sudo -S cp -r ${folderPath}/report.log ${workspace}/${buildNumber}/report || echo 'No report files found'
                echo $SUDO_PASS | sudo -S cp -r ${folderPath}/report.xml ${workspace}/${buildNumber}/report || echo 'No report files found'
                echo $SUDO_PASS | sudo -S chmod -R 777 ${workspace}/${buildNumber}/report
                """
            }

            sh "ls -l ${workspace}/${buildNumber}/artifacts/screenshots/*.png || echo 'No screenshot files copied'"
            sh "ls -l ${workspace}/${buildNumber}/report/* || echo 'No report files copied'"

            archiveArtifacts allowEmptyArchive: true, artifacts: "${buildNumber}/report/artifacts/screenshots/*.png, ${buildNumber}/report/*", fingerprint: false
            echo "archiveArtifacts done"
        } catch (Exception err) {
            echo "Error during file copy: ${err.toString()}"
        }
    }
}
