#!/bin/bash
# filepath: /home/fosqa/jenkins-master/scripts/apply-ldap-config.sh

set -e

cd /home/fosqa/jenkins-master

echo "=========================================="
echo "🔐 Applying LDAP Configuration to Jenkins"
echo "=========================================="
echo ""

# Safety check
read -p "⚠️  Have you run backup-jenkins-config.sh? (y/n): " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "❌ Please backup first: ./scripts/backup-jenkins-config.sh"
    exit 1
fi

# Check if JCasC config exists
if [ ! -f "jcasc/jenkins-ldap.yaml" ]; then
    echo "❌ Configuration file not found: jcasc/jenkins-ldap.yaml"
    exit 1
fi

echo "⏳ Step 1: Uncommenting JCasC configuration in docker-compose.yml..."

# Uncomment the JCasC volume mount
sed -i 's/# - \/home\/fosqa\/jenkins-master\/jcasc:/- \/home\/fosqa\/jenkins-master\/jcasc:/' docker-compose.yml
sed -i 's/# CASC_JENKINS_CONFIG:/CASC_JENKINS_CONFIG:/' docker-compose.yml

echo "✅ Configuration updated"
echo ""

echo "⏳ Step 2: Rebuilding Jenkins container..."
docker-compose up -d --build

echo ""
echo "⏳ Waiting for Jenkins to start (60 seconds)..."
sleep 60

echo ""
echo "=========================================="
echo "✅ LDAP Configuration Applied"
echo "=========================================="
echo ""
echo "Next steps:"
echo "1. Open Jenkins: http://10.96.227.206:8080"
echo "2. Login with local admin: admin / ftnt123!"
echo "3. Go to Manage Jenkins → Configuration as Code"
echo "4. Verify LDAP settings are loaded"
echo "5. Test LDAP login with a domain user"
echo ""
echo "⚠️  Local admin login still works as fallback!"
echo ""
