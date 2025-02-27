// listSharedLibMethods.groovy
// This function returns a list of global shared library method names.
// You must update this list manually if you add or remove globals.
def call() {
    return [
        "hello",
        "fortistackMasterParameters",
        "addArtifacts",
        "getDutInfo",
        "publishTestResults",
        "runAndParsePythonScript",
        "sendNotification"
    ]
}
