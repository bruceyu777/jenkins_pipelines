#!/bin/bash
# filepath: /home/fosqa/jenkins-master/scripts/backup-jenkins-config.sh

set -e

BACKUP_DIR="/home/fosqa/jenkins-master/backups"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_PATH="${BACKUP_DIR}/jenkins_config_${TIMESTAMP}"

echo "=========================================="
echo "🔒 Jenkins Configuration Backup"
echo "=========================================="
echo ""

# Create backup directory
mkdir -p "${BACKUP_PATH}"

echo "⏳ Backing up Jenkins configuration..."
echo ""

# Backup essential configuration files (fast, small)
echo "📄 Step 1/3: Backing up core configuration..."
docker exec jenkins-master bash -c "
    cd /var/jenkins_home
    tar czf /tmp/jenkins_core_${TIMESTAMP}.tar.gz \
        config.xml \
        credentials.xml \
        identity.key* \
        secret.key* \
        nodeMonitors.xml \
        hudson.*.xml \
        jenkins.*.xml \
        2>&1
" && echo "   ✅ Core config backed up" || echo "   ⚠️  Some files may not exist (OK)"

docker cp jenkins-master:/tmp/jenkins_core_${TIMESTAMP}.tar.gz "${BACKUP_PATH}/" 2>/dev/null || true

# Backup users directory (small)
echo ""
echo "👥 Step 2/3: Backing up users..."
docker exec jenkins-master bash -c "
    cd /var/jenkins_home
    if [ -d users ]; then
        tar czf /tmp/jenkins_users_${TIMESTAMP}.tar.gz users/
        echo '   ✅ Users backed up'
    else
        echo '   ⚠️  No users directory found'
    fi
"

docker cp jenkins-master:/tmp/jenkins_users_${TIMESTAMP}.tar.gz "${BACKUP_PATH}/" 2>/dev/null || true

# Backup secrets directory (small)
echo ""
echo "🔐 Step 3/3: Backing up secrets..."
docker exec jenkins-master bash -c "
    cd /var/jenkins_home
    if [ -d secrets ]; then
        tar czf /tmp/jenkins_secrets_${TIMESTAMP}.tar.gz secrets/
        echo '   ✅ Secrets backed up'
    else
        echo '   ⚠️  No secrets directory found'
    fi
"

docker cp jenkins-master:/tmp/jenkins_secrets_${TIMESTAMP}.tar.gz "${BACKUP_PATH}/" 2>/dev/null || true

# Backup job configurations ONLY (without build history - fast)
echo ""
echo "📋 Step 4/4: Backing up job configurations (excluding build history)..."
docker exec jenkins-master bash -c "
    cd /var/jenkins_home
    if [ -d jobs ]; then
        # Only backup config.xml from each job, exclude builds/ directory
        find jobs -name 'config.xml' -o -name 'nextBuildNumber' | \
        tar czf /tmp/jenkins_jobs_${TIMESTAMP}.tar.gz -T - 2>/dev/null
        echo '   ✅ Job configurations backed up (build history excluded)'
    else
        echo '   ⚠️  No jobs directory found'
    fi
"

docker cp jenkins-master:/tmp/jenkins_jobs_${TIMESTAMP}.tar.gz "${BACKUP_PATH}/" 2>/dev/null || true

# Cleanup temp files
echo ""
echo "🧹 Cleaning up temporary files..."
docker exec jenkins-master bash -c "
    rm -f /tmp/jenkins_core_${TIMESTAMP}.tar.gz
    rm -f /tmp/jenkins_users_${TIMESTAMP}.tar.gz
    rm -f /tmp/jenkins_secrets_${TIMESTAMP}.tar.gz
    rm -f /tmp/jenkins_jobs_${TIMESTAMP}.tar.gz
"

# Create backup manifest
echo ""
echo "📝 Creating backup manifest..."
cat > "${BACKUP_PATH}/BACKUP_INFO.txt" << EOF
Jenkins Configuration Backup
============================
Timestamp: ${TIMESTAMP}
Backup Location: ${BACKUP_PATH}
Jenkins Container: jenkins-master

Contents:
- jenkins_core_${TIMESTAMP}.tar.gz     : Core configuration files
- jenkins_users_${TIMESTAMP}.tar.gz    : User accounts and settings
- jenkins_secrets_${TIMESTAMP}.tar.gz  : Encrypted secrets and credentials
- jenkins_jobs_${TIMESTAMP}.tar.gz     : Job configurations (no build history)

Note: Build history is NOT included to keep backup size small.
To backup build history, use full volume backup instead.

Restore Instructions:
--------------------
1. Stop Jenkins: docker-compose down
2. Extract backups to /var/jenkins_home
3. Start Jenkins: docker-compose up -d

Files in this backup:
EOF

# List files in backup
ls -lh "${BACKUP_PATH}"/*.tar.gz >> "${BACKUP_PATH}/BACKUP_INFO.txt" 2>/dev/null || echo "No backup files created" >> "${BACKUP_PATH}/BACKUP_INFO.txt"

echo ""
echo "=========================================="
echo "✅ Backup Complete!"
echo "=========================================="
echo ""
echo "📁 Backup saved to: ${BACKUP_PATH}"
echo ""
echo "📊 Backup contents:"
du -sh "${BACKUP_PATH}"/*.tar.gz 2>/dev/null || echo "   (No files to display)"
echo ""
echo "📄 View backup manifest:"
echo "   cat ${BACKUP_PATH}/BACKUP_INFO.txt"
echo ""
echo "💾 To restore from this backup:"
echo "   ./scripts/restore-jenkins-config.sh ${BACKUP_PATH}"
echo ""
