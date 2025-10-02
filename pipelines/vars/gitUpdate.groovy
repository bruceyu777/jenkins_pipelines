/**
 * gitUpdate.groovy
 *
 * A robust helper for updating local git repositories in Jenkins pipelines.
 * Handles git stash/pull/pop operations with error handling and logging.
 *
 * Usage:
 *   gitUpdate(repoPath: '/home/fosqa/resources/tools')
 *   gitUpdate(repoPath: '/home/fosqa/resources/tools', user: 'fosqa', failOnError: false)
 *
 * Parameters:
 *   - repoPath: Absolute path to the git repository (required)
 *   - user: Unix user to run the command as (default: 'fosqa')
 *   - failOnError: Whether to fail the build on error (default: false)
 *   - stashMessage: Custom stash message (default: 'temporary stash')
 *   - verbose: Enable verbose output (default: true)
 *
 * Returns:
 *   A map with: [success: boolean, message: string, changes: boolean]
 */

def call(Map config = [:]) {
    // Default configuration
    def repoPath = config.repoPath ?: '/home/fosqa/resources/tools'
    def user = config.user ?: 'fosqa'
    def failOnError = config.failOnError ?: false
    def stashMessage = config.stashMessage ?: 'temporary stash'
    def verbose = config.get('verbose', true)

    def result = [
        success: false,
        message: '',
        changes: false,
        stashed: false,
        pulled: false,
        popped: false
    ]

    if (verbose) {
        echo "╔════════════════════════════════════════════════════════════╗"
        echo "║              Git Update Helper - Starting                 ║"
        echo "╠════════════════════════════════════════════════════════════╣"
        echo "║ Repository: ${repoPath.padRight(41)} ║"
        echo "║ User:       ${user.padRight(41)} ║"
        echo "╚════════════════════════════════════════════════════════════╝"
    }

    try {
        // Step 1: Check if repository exists
        if (verbose) echo "📂 Checking if repository exists..."
        def repoExists = sh(
            script: "test -d ${repoPath}/.git",
            returnStatus: true
        ) == 0

        if (!repoExists) {
            result.message = "Repository not found at ${repoPath}"
            if (verbose) echo "❌ ${result.message}"
            if (failOnError) {
                error(result.message)
            }
            return result
        }

        // Step 2: Check for local changes
        if (verbose) echo "🔍 Checking for local changes..."
        def hasChanges = sh(
            script: """
                sudo -u ${user} bash -c 'cd ${repoPath} && git status --porcelain'
            """,
            returnStdout: true
        ).trim()

        result.changes = !hasChanges.isEmpty()

        // Step 3: Stash if there are changes
        if (result.changes) {
            if (verbose) echo "💾 Local changes detected, stashing..."
            try {
                sh """
                    sudo -u ${user} bash -c 'cd ${repoPath} && git stash push -m "${stashMessage}"'
                """
                result.stashed = true
                if (verbose) echo "✅ Stash created successfully"
            } catch (Exception stashErr) {
                result.message = "Failed to stash changes: ${stashErr.getMessage()}"
                if (verbose) echo "⚠️  ${result.message}"
                if (failOnError) throw stashErr
                return result
            }
        } else {
            if (verbose) echo "✅ No local changes to stash"
        }

        // Step 4: Git pull
        if (verbose) echo "⬇️  Pulling latest changes from remote..."
        try {
            def pullOutput = sh(
                script: """
                    sudo -u ${user} bash -c 'cd ${repoPath} && git pull'
                """,
                returnStdout: true
            ).trim()

            result.pulled = true
            if (verbose) {
                echo "✅ Git pull completed"
                if (pullOutput.contains('Already up to date')) {
                    echo "   ℹ️  Repository was already up to date"
                } else if (pullOutput.contains('Updating')) {
                    echo "   ℹ️  Repository updated with new changes"
                }
            }
        } catch (Exception pullErr) {
            result.message = "Failed to pull changes: ${pullErr.getMessage()}"
            if (verbose) echo "❌ ${result.message}"

            // If we stashed, try to pop before failing
            if (result.stashed) {
                if (verbose) echo "⚠️  Attempting to restore stashed changes..."
                try {
                    sh """
                        sudo -u ${user} bash -c 'cd ${repoPath} && git stash pop'
                    """
                    result.popped = true
                    if (verbose) echo "✅ Stashed changes restored"
                } catch (Exception popErr) {
                    if (verbose) echo "⚠️  Failed to restore stash: ${popErr.getMessage()}"
                }
            }

            if (failOnError) throw pullErr
            return result
        }

        // Step 5: Pop stash if we created one
        if (result.stashed) {
            if (verbose) echo "♻️  Restoring stashed changes..."
            try {
                // Check if our stash still exists
                def stashExists = sh(
                    script: """
                        sudo -u ${user} bash -c 'cd ${repoPath} && git stash list | grep -q "${stashMessage}"'
                    """,
                    returnStatus: true
                ) == 0

                if (stashExists) {
                    sh """
                        sudo -u ${user} bash -c 'cd ${repoPath} && git stash pop'
                    """
                    result.popped = true
                    if (verbose) echo "✅ Stashed changes restored successfully"
                } else {
                    if (verbose) echo "⚠️  Stash not found (may have been auto-applied)"
                    result.popped = true  // Consider it successful if stash disappeared
                }
            } catch (Exception popErr) {
                result.message = "Failed to restore stash: ${popErr.getMessage()}"
                if (verbose) {
                    echo "⚠️  ${result.message}"
                    echo "   💡 You may need to manually resolve conflicts"
                    echo "   💡 Run: cd ${repoPath} && git stash list"
                }
                if (failOnError) throw popErr
                return result
            }
        }

        // Success!
        result.success = true
        result.message = "Git update completed successfully"

        if (verbose) {
            echo "╔════════════════════════════════════════════════════════════╗"
            echo "║              Git Update Helper - Completed                ║"
            echo "╠════════════════════════════════════════════════════════════╣"
            echo "║ Status:  ✅ SUCCESS                                        ║"
            echo "║ Changes: ${(result.changes ? 'Yes (stashed & restored)' : 'No').padRight(41)} ║"
            echo "╚════════════════════════════════════════════════════════════╝"
        }

    } catch (Exception e) {
        result.success = false
        result.message = "Unexpected error during git update: ${e.getMessage()}"

        if (verbose) {
            echo "╔════════════════════════════════════════════════════════════╗"
            echo "║              Git Update Helper - Failed                   ║"
            echo "╚════════════════════════════════════════════════════════════╝"
            echo "❌ ${result.message}"
        }

        if (failOnError) {
            error(result.message)
        }
    }

    return result
}
