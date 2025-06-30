import jenkins.model.Jenkins
import org.jenkinsci.plugins.simpletheme.SimpleThemeDecorator
import org.jenkinsci.plugins.simpletheme.Theme

// 1) Disable the built-in CSP (so your CSS can load from wherever)
System.setProperty("hudson.model.DirectoryBrowserSupport.CSP", "")

// 2) Point the Simple Theme Plugin at your custom CSS
def j = Jenkins.getInstance()
def desc = j.getDescriptorByType(SimpleThemeDecorator.class)
// replace the URL below with your own .css location (can be on a file://, http://, etc)
def myCss = new Theme("https://example.com/path/to/custom.css", null)
desc.setElements([ myCss ])
desc.save()
