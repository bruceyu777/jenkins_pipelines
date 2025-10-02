/**
 * Print file contents with debug information
 *
 * Helper to check if a file exists and display its contents with formatted output.
 * Used for debugging configuration files in Jenkins pipelines.
 *
 * @param filePath Path to the file to check and print
 * @param fileLabel Descriptive label for the file (e.g., "Environment File", "Docker Compose File")
 * @param baseDir Optional base directory to list if file doesn't exist (for troubleshooting)
 *
 * @example
 * printFile(
 *     filePath: "testcase/branch/feature/test.env",
 *     fileLabel: "Environment File",
 *     baseDir: "testcase/branch/feature"
 * )
 */
def call(Map config) {
    def filePath = config.filePath
    def fileLabel = config.fileLabel ?: "File"
    def baseDir = config.baseDir ?: ""

    sh """
        echo "=== DEBUG: ${fileLabel} Path ==="
        echo "${fileLabel}: ${filePath}"

        if [ -f "${filePath}" ]; then
            echo "✅ ${fileLabel} exists"
            echo "=== DEBUG: ${fileLabel} Contents ==="
            cat "${filePath}"
            echo "=== END DEBUG: ${fileLabel} Contents ==="
        else
            echo "❌ ${fileLabel} does not exist!"
            if [ -n "${baseDir}" ]; then
                echo "Directory contents:"
                ls -la "${baseDir}" || echo "Directory does not exist: ${baseDir}"
            fi
        fi
    """
}
