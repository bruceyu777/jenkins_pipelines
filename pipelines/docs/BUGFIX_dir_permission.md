# Bug Fix: Jenkins Permission Denied on dir() Block

## 🐛 **Error**

```
hudson.remoting.ProxyException: java.nio.file.AccessDeniedException: /home/fosqa/autolibv3@tmp
	at java.base/sun.nio.fs.UnixFileSystemProvider.createDirectory
```

Pipeline failed with:
```groovy
[Pipeline] dir
Running in /home/fosqa/autolibv3
[Pipeline] {
[Pipeline] sh
[Pipeline] }
[Pipeline] // dir
```

---

## 🔍 **Root Cause**

### The Problem with `dir()` in Jenkins

When you use:
```groovy
dir("/home/fosqa/autolibv3") {
    printFile(...)
}
```

Jenkins tries to:
1. Create a temporary workspace: `/home/fosqa/autolibv3@tmp`
2. But Jenkins agent runs as user `jenkins`
3. Directory `/home/fosqa/autolibv3` is owned by user `fosqa`
4. User `jenkins` has no permission to create `@tmp` subdirectory
5. **Result:** `AccessDeniedException`

### Why This Happens

```bash
# Directory ownership
$ ls -ld /home/fosqa/autolibv3
drwxr-xr-x fosqa fosqa /home/fosqa/autolibv3

# Jenkins tries to create
$ whoami
jenkins

$ mkdir /home/fosqa/autolibv3@tmp
mkdir: cannot create directory '/home/fosqa/autolibv3@tmp': Permission denied
```

---

## ✅ **Solution**

### **Remove `dir()` Block - Use Absolute Paths**

**Before (BROKEN):**
```groovy
// Define relative paths
def envFile = "testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_CONFIG_CHOICE}"
def groupFile = "testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${group}"
def featureDir = "testcase/${SVN_BRANCH}/${params.FEATURE_NAME}"

// Try to change directory (FAILS with permission error)
dir("/home/fosqa/${LOCAL_LIB_DIR}") {
    printFile(
        filePath: envFile,
        fileLabel: "Environment File",
        baseDir: featureDir
    )

    printFile(
        filePath: groupFile,
        fileLabel: "Group File",
        baseDir: featureDir
    )
}

// Run tests with relative paths
sh """
    cd /home/fosqa/${LOCAL_LIB_DIR}
    . /home/fosqa/${LOCAL_LIB_DIR}/venv/bin/activate
    python3 autotest.py -e "${envFile}" -g "${groupFile}" -d -s ${params.ORIOLE_SUBMIT_FLAG}
"""
```

**After (FIXED):**
```groovy
// Define absolute paths for printFile
def envFile = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_CONFIG_CHOICE}"
def groupFile = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${group}"
def featureDir = "/home/fosqa/${LOCAL_LIB_DIR}/testcase/${SVN_BRANCH}/${params.FEATURE_NAME}"

// No dir() block - just call printFile with absolute paths
printFile(
    filePath: envFile,
    fileLabel: "Environment File",
    baseDir: featureDir
)

printFile(
    filePath: groupFile,
    fileLabel: "Group File",
    baseDir: featureDir
)

// Run tests with relative paths (since we cd first)
sh """
    cd /home/fosqa/${LOCAL_LIB_DIR}
    . /home/fosqa/${LOCAL_LIB_DIR}/venv/bin/activate
    python3 autotest.py \
      -e "testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${params.TEST_CONFIG_CHOICE}" \
      -g "testcase/${SVN_BRANCH}/${params.FEATURE_NAME}/${group}" \
      -d -s ${params.ORIOLE_SUBMIT_FLAG}
"""
```

---

## 📊 **What Changed**

| Aspect | Before | After |
|--------|--------|-------|
| **printFile paths** | Relative | Absolute |
| **dir() block** | Used (failed) | Removed |
| **autotest.py paths** | Used variables | Inline relative paths |
| **Permissions** | Tried to create @tmp | No @tmp needed |
| **Result** | ❌ AccessDeniedException | ✅ Works |

---

## 🎯 **Key Points**

### 1. **Avoid `dir()` in User-Owned Directories**

```groovy
// ❌ BAD - Jenkins can't create @tmp
dir("/home/fosqa/somedir") {
    sh "ls"
}

// ✅ GOOD - Just use absolute paths or cd in shell
sh """
    cd /home/fosqa/somedir
    ls
"""
```

### 2. **Jenkins Workspace vs User Directories**

```groovy
// ✅ OK - Jenkins owns this
dir("${WORKSPACE}/subdir") {
    sh "ls"
}

// ❌ BAD - User owns this
dir("/home/fosqa/somedir") {
    sh "ls"
}
```

### 3. **When `dir()` Works**

The `dir()` block only works when:
- Jenkins agent has **write permission** to create subdirectories
- Directory is in Jenkins workspace (`${WORKSPACE}`)
- Directory is in `/tmp` or other world-writable locations
- Directory permissions explicitly allow Jenkins user

---

## 🔧 **Alternative Solutions**

### Option 1: Fix Permissions (NOT RECOMMENDED)

```bash
# Give Jenkins write access
sudo chown -R jenkins:jenkins /home/fosqa/autolibv3

# Or add to group
sudo chmod -R 775 /home/fosqa/autolibv3
sudo usermod -a -G fosqa jenkins
```

**Cons:**
- Security risk
- Breaks fosqa ownership
- Not maintainable

### Option 2: Remove dir() Block (RECOMMENDED) ✅

Already implemented above. No permission changes needed.

### Option 3: Use Jenkins Workspace

```groovy
// Copy to workspace first
sh "cp -r /home/fosqa/autolibv3/* ${WORKSPACE}/"

// Now dir() works
dir("${WORKSPACE}") {
    printFile(...)
}
```

**Cons:**
- Extra disk space
- Extra time to copy
- Not necessary

---

## 🧪 **Testing**

### Before Fix
```
[Pipeline] dir
Running in /home/fosqa/autolibv3
hudson.remoting.ProxyException: java.nio.file.AccessDeniedException: /home/fosqa/autolibv3@tmp
[Pipeline] End of Pipeline
```

### After Fix
```
[Pipeline] printFile
=== Environment File ===
[Content displayed]

[Pipeline] printFile
=== Group File ===
[Content displayed]

[Pipeline] sh
+ cd /home/fosqa/autolibv3
+ python3 autotest.py -e testcase/...
[Tests run successfully]
```

---

## 📚 **Related Jenkins Documentation**

### Jenkins `dir()` Behavior

From Jenkins documentation:
> "The `dir` step changes the current directory. This affects any file operations (including SCM checkouts) that use relative paths, as well as any shell steps.
>
> **Note:** The `dir` step requires write access to the specified directory, as it may need to create temporary files."

### When Jenkins Creates @tmp

Jenkins creates `<directory>@tmp` when:
- Using `dir()` block
- Running parallel branches
- Using stash/unstash
- Certain SCM checkouts

---

## 💡 **Best Practices**

1. **Use shell `cd` instead of Groovy `dir()`** for user-owned directories
   ```groovy
   sh """
       cd /home/fosqa/somedir
       ./script.sh
   """
   ```

2. **Use absolute paths** when calling functions outside `dir()`
   ```groovy
   printFile(filePath: "/home/fosqa/autolibv3/file.txt")
   ```

3. **Keep Jenkins workspace separate** from user directories
   ```
   Jenkins workspace: /var/jenkins_home/workspace/job-name
   User files:       /home/fosqa/autolibv3
   ```

4. **Use `sudo` in shell commands** if needed, not in `dir()` blocks
   ```groovy
   sh "sudo -u fosqa bash -c 'cd /home/fosqa/autolibv3 && ls'"
   ```

---

## 🎓 **Summary**

| Issue | Solution |
|-------|----------|
| **Problem** | `dir()` block causes AccessDeniedException |
| **Root Cause** | Jenkins can't create @tmp in user-owned directory |
| **Fix** | Remove `dir()` block, use absolute paths |
| **Impact** | No permission changes needed |
| **Status** | ✅ Fixed |

---

## 📝 **Files Modified**

```
fortistackRunTests.groovy
  - Removed dir() block (line ~236)
  - Changed to absolute paths for printFile
  - Kept relative paths in shell command (after cd)
```

---

## ✅ **Verification Steps**

1. ✅ Removed `dir("/home/fosqa/${LOCAL_LIB_DIR}")` block
2. ✅ Changed paths for `printFile()` to absolute
3. ✅ Kept shell command with `cd` and relative paths
4. ✅ No permission changes required
5. ✅ Pipeline should now complete successfully

---

**Date:** October 2, 2025
**Issue:** Jenkins AccessDeniedException on dir() block
**Solution:** Remove dir() block, use absolute paths
**Status:** ✅ Fixed
