import jenkins.model.Jenkins
import jenkins.model.JenkinsLocationConfiguration

println "=========================================="
println "JENKINS AUTO-CONFIGURATION SCRIPT"
println "Script executed at: ${new Date()}"
println "=========================================="

def jenkins = Jenkins.getInstance()

// Display Jenkins version and basic info
println "Jenkins Version: ${jenkins.getVersion()}"
println "Jenkins URL: ${jenkins.getRootUrl() ?: 'Not configured'}"
println "Number of executors: ${jenkins.getNumExecutors()}"

// Show that the script is running with admin privileges
println "Script running with system privileges"

// Example configuration: Set Jenkins URL automatically using the correct API
def jenkinsUrl = "http://localhost:8080/"
def locationConfig = JenkinsLocationConfiguration.get()
locationConfig.setUrl(jenkinsUrl)
locationConfig.save()
println "Jenkins URL set to: ${jenkinsUrl}"

// Save configuration
jenkins.save()
println "Configuration saved successfully!"
println "=========================================="
