// import hudson.security.csrf.DefaultCrumbIssuer
// import jenkins.model.Jenkins

// // Get Jenkins instance
// def instance = Jenkins.getInstance()
// def crumbIssuer = instance.getCrumbIssuer()

// import hudson.security.csrf.DefaultCrumbIssuer
// import jenkins.model.Jenkins

// // Get Jenkins instance
// def instance = Jenkins.getInstance()
// def crumbIssuer = instance.getCrumbIssuer()

// // Configure CSRF protection - this script ENABLES CSRF with proper settings
// // to resolve HTTP 403 crumb errors while maintaining security

// // Option 1: Disable CSRF protection completely (NOT RECOMMENDED for production)
// /*
// if (crumbIssuer != null) {
//     println "CSRF protection is currently enabled. Disabling it..."
//     instance.setCrumbIssuer(null)
//     instance.save()
//     println "CSRF protection has been disabled."
// } else {
//     println "CSRF protection is already disabled."
// }
// */

// // Option 2: Enable CSRF protection with proper configuration (RECOMMENDED)
// if (crumbIssuer == null) {
//     println "Enabling CSRF protection with proper configuration..."
//     def newCrumbIssuer = new DefaultCrumbIssuer(false) // false = exclude client IP from crumb
//     instance.setCrumbIssuer(newCrumbIssuer)
//     instance.save()
//     println "CSRF protection enabled with DefaultCrumbIssuer."
// } else {
//     println "CSRF protection already configured."
// }
