// vars/getDutInfo.groovy
def call(String testFolder, String workspace, def env, def config) {  // Accept `env` as def (dynamic type) to avoid type issues
    def TMP_FILE = "${workspace}/${env.JOB_NAME}_${env.BUILD_NUMBER}_output.json"

    // Run Python script to retrieve build info
    sh """
        cd ${testFolder} && . ../venv*/bin/activate && python3 docker_test_report_manager.py --method fgt_build_info --tmpfile ${TMP_FILE} --config ${config}
    """

    def dut_release = 'unknown'
    def dut_build = 'unknown'

    if (fileExists(TMP_FILE)) {
        def jsonDUT = readJSON file: TMP_FILE
        dut_release = jsonDUT.release ?: 'unknown'
        dut_build = jsonDUT.build ?: 'unknown'
    } else {
        error "Output file not found: ${TMP_FILE}"
    }

    sh "rm -f ${TMP_FILE}"  // Clean up the temporary file

    return [dut_release: dut_release, dut_build: dut_build]
}
