import jenkins.model.*
import hudson.security.*

def instance = Jenkins.getInstance()

// Check if admin user exists, if not create it
def hudsonRealm = new HudsonPrivateSecurityRealm(false)
def existingUser = null

try {
    existingUser = User.get('fosqa', false)
} catch (Exception e) {
    println "User 'fosqa' does not exist yet"
}

if (!existingUser) {
    println "Creating local admin user 'fosqa'..."
    hudsonRealm.createAccount('fosqa', 'Ftnt123!')
    instance.setSecurityRealm(hudsonRealm)

    def strategy = new FullControlOnceLoggedInAuthorizationStrategy()
    strategy.setAllowAnonymousRead(false)
    instance.setAuthorizationStrategy(strategy)

    instance.save()
    println "Local admin user 'fosqa' created successfully"
} else {
    println "Local admin user 'fosqa' already exists"
}
