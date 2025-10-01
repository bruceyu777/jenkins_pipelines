/**
 * Retry a shell command with configurable backoff intervals.
 *
 * This is a shared Jenkins pipeline helper that can be called from any pipeline.
 *
 * @param attempts Maximum number of retry attempts (default: 3)
 * @param sleepsSec List of sleep intervals in seconds between retries.
 *                  If the list is shorter than attempts-1, the last value is reused.
 *                  Default: [10] (10 seconds between each retry)
 * @param cmd Shell command to execute
 * @return void (throws error if all attempts fail)
 *
 * @example
 * // Simple retry with default 10s sleep
 * runWithRetry(3, [], "svn update")
 *
 * // Custom backoff: 5s, 15s, 45s
 * runWithRetry(4, [5, 15, 45], "svn checkout https://...")
 *
 * // Exponential backoff
 * runWithRetry(5, [5, 10, 20, 40], "git pull")
 */
def call(int attempts = 3, List<Integer> sleepsSec = [10], String cmd) {
    // Validate inputs
    if (attempts < 1) {
        error "runWithRetry: attempts must be >= 1, got ${attempts}"
    }
    if (!cmd?.trim()) {
        error "runWithRetry: cmd cannot be empty"
    }

    // Default sleep interval if list is empty
    if (sleepsSec == null || sleepsSec.isEmpty()) {
        sleepsSec = [10]
    }

    def lastException = null

    for (int i = 1; i <= attempts; i++) {
        try {
            echo "Executing command (attempt ${i}/${attempts})..."

            // Execute command and check return status
            int rc = sh(script: cmd, returnStatus: true)

            if (rc == 0) {
                if (i > 1) {
                    echo "✅ Command succeeded on attempt ${i}/${attempts}"
                }
                return // Success!
            }

            // Non-zero return code
            def errorMsg = "Command failed with exit code ${rc}"
            echo "❌ ${errorMsg} (attempt ${i}/${attempts})"
            lastException = new Exception(errorMsg)

        } catch (Exception e) {
            // Handle exceptions from sh step
            echo "❌ Command threw exception: ${e.getMessage()} (attempt ${i}/${attempts})"
            lastException = e
        }

        // If not the last attempt, sleep before retry
        if (i < attempts) {
            // Get sleep interval: use list value or last value if list is shorter
            int sleepInterval = (sleepsSec.size() >= i) ? sleepsSec[i - 1] : sleepsSec[-1]
            echo "⏳ Waiting ${sleepInterval}s before retry..."
            sleep time: sleepInterval, unit: 'SECONDS'
        }
    }

    // All attempts failed
    def finalMsg = "Command failed after ${attempts} attempt(s)"
    echo "💥 ${finalMsg}"

    if (lastException) {
        error "${finalMsg}: ${lastException.getMessage()}"
    } else {
        error finalMsg
    }
}
