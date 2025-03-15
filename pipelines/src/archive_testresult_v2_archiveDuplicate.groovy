pipeline {
    parameters {
        string(
            name: 'NODE_NAME',
            defaultValue: 'node1',
            trim: true,
            description: 'Jenkins node label'
        )
        string(
            name: 'LOCAL_LIB_DIR',
            defaultValue: 'autolibv3',
            trim: true,
            description: 'Local library directory'
        )
        string(
            name: 'TEST_GROUP_CHOICE',
            defaultValue: 'grp.avfortisandbox_fortistack.full',
            trim: true,
            description: 'Test group file name (legacy)'
        )
        string(
            name: 'TEST_GROUPS',
            defaultValue: '',
            trim: true,
            description: 'Optional list of test groups in JSON format or comma-separated. If provided, overrides TEST_GROUP_CHOICE.'
        )
    }
    
    agent { label "${params.NODE_NAME}" }
    options {
        buildDiscarder(logRotator(numToKeepStr: '100'))
    }
    
    stages {
        stage('Set Build Display Name') {
            steps {
                script {
                    // Determine test groups from TEST_GROUPS if provided, otherwise fallback to TEST_GROUP_CHOICE.
                    def testGroups = []
                    if (params.TEST_GROUPS?.trim()) {
                        try {
                            testGroups = readJSON text: params.TEST_GROUPS
                        } catch (Exception e) {
                            echo "Error parsing TEST_GROUPS as JSON: ${e}. Splitting by comma."
                            testGroups = params.TEST_GROUPS.split(",").collect { it.trim() }
                        }
                    }
                    if (!testGroups || testGroups.isEmpty()) {
                        testGroups = [params.TEST_GROUP_CHOICE]
                    }
                    currentBuild.displayName = "#${currentBuild.number} ${params.NODE_NAME}-${testGroups.join(',')}"
                }
            }
        }
        
        stage('Archive Test Results') {
            steps {
                script {
                    def outputsDir = "/home/fosqa/${params.LOCAL_LIB_DIR}/outputs"

                    def testGroups = []
                    if (params.TEST_GROUPS) {
                        if (params.TEST_GROUPS instanceof String) {
                            def tg = params.TEST_GROUPS.trim()
                            // Remove surrounding quotes if present.
                            if (tg.startsWith("\"") && tg.endsWith("\"")) {
                                tg = tg.substring(1, tg.length()-1).trim()
                            }
                            if (tg.startsWith("[")) {
                                try {
                                    def parsed = readJSON text: tg
                                    if (parsed instanceof List) {
                                        testGroups = parsed
                                    } else {
                                        testGroups = tg.split(",").collect { it.trim() }
                                    }
                                } catch (e) {
                                    echo "Error parsing TEST_GROUPS as JSON: ${e}. Falling back to splitting by comma."
                                    testGroups = tg.split(",").collect { it.trim() }
                                }
                            } else {
                                testGroups = tg.split(",").collect { it.trim() }
                            }
                        } else if (params.TEST_GROUPS instanceof List) {
                            testGroups = params.TEST_GROUPS
                        } else {
                            testGroups = [params.TEST_GROUPS.toString()]
                        }
                    }

                    if (testGroups == null || testGroups.isEmpty()) {
                        testGroups = [params.TEST_GROUP_CHOICE]
                    }

                    echo "Determined test groups: ${testGroups}"

                    
                    def archivedFolders = []
                    // Iterate over each test group and find its latest matching folder.
                    for (group in testGroups) {
                        def folder = sh(
                            returnStdout: true,
                            script: """
                                find ${outputsDir} -mindepth 2 -maxdepth 2 -type d -name "*--group--${group}" -printf '%T@ %p\\n' | sort -nr | head -1 | cut -d' ' -f2-
                            """
                        ).trim()
                        
                        if (!folder) {
                            echo "Warning: No test results folder found for test group '${group}' in ${outputsDir}."
                        } else {
                            echo "Found folder for group '${group}': ${folder}"
                            archivedFolders << folder
                            // Create a subfolder for this group and copy the results.
                            sh "mkdir -p ${WORKSPACE}/test_results/${group}"
                            sh "cp -r ${folder} ${WORKSPACE}/test_results/${group}/"
                            // Copy the summary file with a unique name.
                            sh "cp ${folder}/summary/summary.html ${WORKSPACE}/summary_${group}.html"
                        }
                    }
                    
                    if (archivedFolders.isEmpty()) {
                        echo "No test results were found for any test group."
                    } else {
                        // Archive all collected test results and summary files.
                        archiveArtifacts artifacts: "test_results/**, summary_*.html", fingerprint: false
                        // Optionally, publish one of the HTML summary reports.
                        publishHTML(target: [
                            reportDir: ".",
                            reportFiles: "summary_${testGroups[0]}.html",
                            reportName: "Test Results Summary for ${testGroups[0]}"
                        ])
                    }
                }
            }
        }
    }
    
    post {
        always {
            echo "Pipeline completed. Check console output for details."
        }
    }
}
