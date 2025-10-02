# gitUpdate.groovy - Usage Examples

## 📚 Overview

The `gitUpdate` helper provides a robust, reusable way to update local git repositories in Jenkins pipelines. It handles stashing local changes, pulling updates, and restoring changes with comprehensive error handling.

## 🎯 Key Features

- ✅ **Robust Error Handling**: Try/catch blocks at every step
- ✅ **Non-blocking**: Can continue pipeline on failure
- ✅ **Verbose Logging**: Clear status messages with emojis
- ✅ **Flexible Configuration**: Customize behavior via parameters
- ✅ **Safe Stash Management**: Automatically handles local changes
- ✅ **Return Status**: Get detailed information about the operation

---

## 📖 Basic Usage

### Simple Update (Default Settings)
```groovy
// Uses defaults: /home/fosqa/resources/tools, user: fosqa, non-blocking
gitUpdate()
```

### With Custom Repository Path
```groovy
gitUpdate(repoPath: '/home/fosqa/git/autolib_v3')
```

### With All Options
```groovy
def result = gitUpdate(
    repoPath: '/home/fosqa/resources/tools',
    user: 'fosqa',
    failOnError: false,
    stashMessage: 'Jenkins auto-stash',
    verbose: true
)

if (result.success) {
    echo "Git update succeeded!"
    if (result.changes) {
        echo "Local changes were stashed and restored"
    }
} else {
    echo "Git update failed: ${result.message}"
}
```

---

## 🔄 Integration Examples

### Example 1: fortistackProvisionFgts.groovy

**Before:**
```groovy
stage('GIT Pull') {
    steps {
        script {
            withCredentials([usernamePassword(credentialsId: 'LDAP', usernameVariable: 'SVN_USER', passwordVariable: 'SVN_PASS')]) {
                echo "=== Step 1: Local Git update ==="
                def innerGitCmd = "sudo -u fosqa bash -c 'cd /home/fosqa/resources/tools && " +
                                  "if [ -n \"\$(git status --porcelain)\" ]; then git stash push -m \"temporary stash\"; fi; " +
                                  "git pull; " +
                                  "if git stash list | grep -q \"temporary stash\"; then git stash pop; fi'"
                echo "Executing local git pull command: ${innerGitCmd}"
                try {
                    sh innerGitCmd
                } catch (Exception e) {
                    echo "Local git pull failed: ${e.getMessage()}. Continuing without updating."
                }
            }
        }
    }
}
```

**After:**
```groovy
stage('GIT Pull') {
    steps {
        script {
            echo "=== Local Git Update ==="
            gitUpdate(
                repoPath: '/home/fosqa/resources/tools',
                failOnError: false
            )
        }
    }
}
```

---

### Example 2: fortistackRunTests.groovy

**Before:**
```groovy
echo "=== Step 1: Local Git update ==="
def innerGitCmd = """
    sudo -u fosqa bash -c '
      cd /home/fosqa/resources/tools && \
      if [ -n "\$(git status --porcelain)" ]; then \
        git stash push -m "temporary stash"; \
      fi; \
      git pull; \
      if git stash list | grep -q "temporary stash"; then \
        git stash pop; \
      fi
    '
"""
try {
    sh innerGitCmd
} catch (Exception e) {
    echo "Local git pull failed: ${e.getMessage()}. Continuing without updating."
}
```

**After:**
```groovy
echo "=== Step 1: Local Git update ==="
def gitResult = gitUpdate(
    repoPath: '/home/fosqa/resources/tools',
    failOnError: false
)

if (!gitResult.success) {
    echo "⚠️ Git update had issues: ${gitResult.message}"
    echo "   Continuing with existing code..."
}
```

---

## 📊 Return Value Structure

The helper returns a map with the following structure:

```groovy
[
    success: true/false,           // Overall success status
    message: "description",        // Human-readable status message
    changes: true/false,           // Whether local changes existed
    stashed: true/false,           // Whether changes were stashed
    pulled: true/false,            // Whether pull succeeded
    popped: true/false             // Whether stash was restored
]
```

### Example: Using Return Values
```groovy
def result = gitUpdate(repoPath: '/home/fosqa/resources/tools')

if (result.success) {
    if (result.changes) {
        echo "✅ Updated repository with local changes preserved"
    } else {
        echo "✅ Repository updated (no local changes)"
    }
} else {
    echo "❌ Failed: ${result.message}"

    // Make decisions based on what failed
    if (!result.pulled) {
        echo "⚠️ Could not fetch latest changes from remote"
    }
    if (result.stashed && !result.popped) {
        echo "⚠️ Local changes are still stashed - manual intervention needed"
        echo "   Run: cd /home/fosqa/resources/tools && git stash pop"
    }
}
```

---

## 🎨 Output Examples

### Successful Update (No Changes)
```
╔════════════════════════════════════════════════════════════╗
║              Git Update Helper - Starting                 ║
╠════════════════════════════════════════════════════════════╣
║ Repository: /home/fosqa/resources/tools                   ║
║ User:       fosqa                                          ║
╚════════════════════════════════════════════════════════════╝
📂 Checking if repository exists...
🔍 Checking for local changes...
✅ No local changes to stash
⬇️  Pulling latest changes from remote...
✅ Git pull completed
   ℹ️  Repository was already up to date
╔════════════════════════════════════════════════════════════╗
║              Git Update Helper - Completed                ║
╠════════════════════════════════════════════════════════════╣
║ Status:  ✅ SUCCESS                                        ║
║ Changes: No                                                ║
╚════════════════════════════════════════════════════════════╝
```

### Successful Update (With Changes)
```
╔════════════════════════════════════════════════════════════╗
║              Git Update Helper - Starting                 ║
╠════════════════════════════════════════════════════════════╣
║ Repository: /home/fosqa/resources/tools                   ║
║ User:       fosqa                                          ║
╚════════════════════════════════════════════════════════════╝
📂 Checking if repository exists...
🔍 Checking for local changes...
💾 Local changes detected, stashing...
✅ Stash created successfully
⬇️  Pulling latest changes from remote...
✅ Git pull completed
   ℹ️  Repository updated with new changes
♻️  Restoring stashed changes...
✅ Stashed changes restored successfully
╔════════════════════════════════════════════════════════════╗
║              Git Update Helper - Completed                ║
╠════════════════════════════════════════════════════════════╣
║ Status:  ✅ SUCCESS                                        ║
║ Changes: Yes (stashed & restored)                         ║
╚════════════════════════════════════════════════════════════╝
```

---

## 🛠️ Configuration Parameters

| Parameter | Type | Default | Description |
|-----------|------|---------|-------------|
| `repoPath` | String | `/home/fosqa/resources/tools` | Absolute path to git repository |
| `user` | String | `fosqa` | Unix user to run commands as |
| `failOnError` | Boolean | `false` | Whether to fail build on error |
| `stashMessage` | String | `temporary stash` | Custom message for git stash |
| `verbose` | Boolean | `true` | Enable detailed logging output |

---

## 🔒 Error Handling Strategies

### Strategy 1: Non-blocking (Recommended for most cases)
```groovy
// Continues pipeline even if git update fails
gitUpdate(
    repoPath: '/home/fosqa/resources/tools',
    failOnError: false
)
// Pipeline continues with existing code
```

### Strategy 2: Blocking (Critical updates)
```groovy
// Fails pipeline if git update fails
gitUpdate(
    repoPath: '/home/fosqa/resources/tools',
    failOnError: true
)
// Pipeline stops here if update fails
```

### Strategy 3: Conditional Logic
```groovy
def result = gitUpdate(failOnError: false)

if (!result.success) {
    echo "⚠️ Using potentially outdated code"

    // Maybe send notification or set build as unstable
    currentBuild.result = 'UNSTABLE'
}
```

---

## 📝 Comparison: Old vs New Approach

### Old Approach (fortistackProvisionFgts.groovy)
```groovy
// ❌ Problems:
// - Single-line bash command is hard to read
// - String concatenation is error-prone
// - Basic try/catch only at outermost level
// - No visibility into what step failed
// - No return value to check status

def innerGitCmd = "sudo -u fosqa bash -c 'cd /home/fosqa/resources/tools && " +
                  "if [ -n \"\$(git status --porcelain)\" ]; then git stash push -m \"temporary stash\"; fi; " +
                  "git pull; " +
                  "if git stash list | grep -q \"temporary stash\"; then git stash pop; fi'"
try {
    sh innerGitCmd
} catch (Exception e) {
    echo "Local git pull failed: ${e.getMessage()}. Continuing without updating."
}
```

### Old Approach (fortistackRunTests.groovy)
```groovy
// ❌ Problems:
// - Multi-line but still embedded in pipeline
// - Duplicated code across multiple pipelines
// - Hard to maintain consistently
// - No detailed status reporting

def innerGitCmd = """
    sudo -u fosqa bash -c '
      cd /home/fosqa/resources/tools && \
      if [ -n "\$(git status --porcelain)" ]; then \
        git stash push -m "temporary stash"; \
      fi; \
      git pull; \
      if git stash list | grep -q "temporary stash"; then \
        git stash pop; \
      fi
    '
"""
try {
    sh innerGitCmd
} catch (Exception e) {
    echo "Local git pull failed: ${e.getMessage()}. Continuing without updating."
}
```

### New Approach (gitUpdate.groovy)
```groovy
// ✅ Advantages:
// - Single line in pipeline
// - DRY (Don't Repeat Yourself)
// - Comprehensive error handling at each step
// - Detailed logging with clear status messages
// - Return value for conditional logic
// - Easy to maintain in one place
// - Configurable behavior
// - Better error messages

gitUpdate(
    repoPath: '/home/fosqa/resources/tools',
    failOnError: false
)
```

---

## 🚀 Advanced Usage

### Multiple Repositories
```groovy
stage('Update All Repositories') {
    steps {
        script {
            def repos = [
                '/home/fosqa/resources/tools',
                '/home/fosqa/git/autolib_v3',
                '/home/fosqa/git/pipeline_lib'
            ]

            def results = [:]
            repos.each { repo ->
                echo "Updating ${repo}..."
                results[repo] = gitUpdate(repoPath: repo, failOnError: false)
            }

            // Check if any failed
            def failed = results.findAll { k, v -> !v.success }
            if (failed) {
                echo "⚠️ Some repositories failed to update:"
                failed.each { k, v ->
                    echo "  - ${k}: ${v.message}"
                }
            }
        }
    }
}
```

### With Email Notification on Failure
```groovy
def result = gitUpdate(repoPath: '/home/fosqa/resources/tools', failOnError: false)

if (!result.success) {
    sendFosqaEmail(
        to: 'yzhengfeng@fortinet.com',
        subject: "Git Update Failed in ${env.JOB_NAME}",
        body: """
            <p>Git update failed for build #${env.BUILD_NUMBER}</p>
            <p><b>Error:</b> ${result.message}</p>
            <p>Pipeline will continue with existing code.</p>
        """
    )
}
```

### Silent Mode (No Verbose Output)
```groovy
// Useful for scheduled jobs where you don't want log spam
def result = gitUpdate(
    repoPath: '/home/fosqa/resources/tools',
    failOnError: false,
    verbose: false
)

// Only log if something went wrong
if (!result.success) {
    echo "Git update failed: ${result.message}"
}
```

---

## 🔧 Troubleshooting

### Issue: "Repository not found"
```groovy
// Check if path is correct
def result = gitUpdate(repoPath: '/home/fosqa/resources/tools')
if (!result.success && result.message.contains('not found')) {
    echo "Repository doesn't exist. Creating it?"
    // Handle initialization
}
```

### Issue: Merge Conflicts During Pop
```groovy
def result = gitUpdate(repoPath: '/home/fosqa/resources/tools')
if (result.stashed && !result.popped) {
    echo "⚠️ Stash pop failed - likely merge conflicts"
    echo "   Manual steps:"
    echo "   1. cd /home/fosqa/resources/tools"
    echo "   2. git stash list  # Find your stash"
    echo "   3. git stash show -p  # Review changes"
    echo "   4. git stash pop  # Resolve conflicts"
}
```

### Issue: Permission Denied
```groovy
// Make sure the user parameter matches the repository owner
gitUpdate(
    repoPath: '/home/fosqa/resources/tools',
    user: 'fosqa',  // Must have write access to repo
    failOnError: false
)
```

---

## 📚 Best Practices

1. **Use failOnError: false for non-critical updates**
   - Allows pipeline to continue with existing code
   - Prevents unnecessary build failures

2. **Check return value for important updates**
   ```groovy
   def result = gitUpdate()
   if (!result.success) {
       currentBuild.result = 'UNSTABLE'
   }
   ```

3. **Keep verbose: true for debugging**
   - Only set to false for production/scheduled jobs
   - Helps troubleshoot issues quickly

4. **Use custom stash messages for tracking**
   ```groovy
   gitUpdate(stashMessage: "Jenkins-${env.BUILD_NUMBER}-auto-stash")
   ```

5. **Consider repository cleanup for long-running agents**
   ```groovy
   // Before update, clean up old stashes
   sh "cd /home/fosqa/resources/tools && git stash clear"
   gitUpdate()
   ```

---

## 🎓 Summary

The `gitUpdate` helper provides:
- ✅ **Robustness**: Try/catch at every step
- ✅ **Visibility**: Clear logging of what's happening
- ✅ **Flexibility**: Configurable behavior
- ✅ **Reusability**: DRY principle - write once, use everywhere
- ✅ **Safety**: Non-blocking by default, preserves local changes
- ✅ **Debugging**: Detailed return values and error messages

**Replace verbose inline shell scripts with a single, maintainable helper!** 🚀
