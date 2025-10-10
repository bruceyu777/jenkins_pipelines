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

# Backup current security configuration
echo "⏳ Backing up Jenkins configuration..."

docker exec jenkins-master bash -c "
    cd /var/jenkins_home
    tar czf /tmp/jenkins_backup_${TIMESTAMP}.tar.gz \
        config.xml \
        users/ \
        secrets/ \
        identity.key* \
        jobs/
"

docker cp jenkins-master:/tmp/jenkins_backup_${TIMESTAMP}.tar.gz "${BACKUP_PATH}/"

docker exec jenkins-master rm -f /tmp/jenkins_backup_${TIMESTAMP}.tar.gz

echo "✅ Backup saved to: ${BACKUP_PATH}"
echo ""
echo "To restore from this backup:"
echo "  cd ${BACKUP_PATH}"
echo "  tar xzf jenkins_backup_${TIMESTAMP}.tar.gz"
echo "  # Copy files back to /var/jenkins_home"
echo ""
