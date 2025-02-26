def call(String testFolder, String sessionId) {
    try {
        def output = sh(script: "cd ${testFolder} && . ../venv*/bin/activate && python3 docker_test_report_manager.py --method read_docker_combined_report --file_path ${testFolder}/docker_combined_report_list.txt --session_id=${sessionId}", returnStdout: true).trim()
        println("Raw Python script output: $output")

        def jsonOutput = readJSON text: output
        println("After JSON Parsing: ${jsonOutput}")

        return jsonOutput
    } catch (Throwable t) {
        echo "Caught Throwable: ${t}"
        currentBuild.result = 'UNSTABLE'
        error("Failed to parse Python script output")
    }
}
