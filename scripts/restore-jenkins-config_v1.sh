#!/bin/bash
# filepath: /home/fosqa/jenkins-master/scripts/restore-jenkins-config.sh

# set -e

# if [ -z "$1" ]; then
#     echo "Usage: $0 <backup_path>"
#     echo ""
#     echo "Example:"
#     echo "  $0 /home/fosqa/jenkins-master/backups/jenkins_config_20241010_123456"
#     echo ""
#     echo "Available backups:"
#     ls -dt /home/fosqa/jenkins-master/backups/jenkins_config_* 2>/dev/null | head -5
#     exit 1
# fi

# BACKUP_PATH="$1"

set -Eeuo pipefail

# If no argument, show the 5 most recent backups using find (avoids SC2012)
if [ -z "${1:-}" ]; then
    echo "Usage: $0 /home/fosqa/jenkins-master/backups/jenkins_config_YYYYmmdd_HHMMSS"
    echo ""
    echo "Recent backups:"
    find /home/fosqa/jenkins-master/backups -maxdepth 1 -type d -name 'jenkins_config_*' \
        -printf '%T@ %p\n' 2>/dev/null | sort -nr | head -5 | awk '{ $1=""; sub(/^ /,""); print " - " $0 }'
    exit 1
fi

BACKUP_PATH="$1"

if [ ! -d "$BACKUP_PATH" ]; then
    echo "❌ Backup directory not found: $BACKUP_PATH"
    exit 1
fi

echo "=========================================="
echo "🔄 Jenkins Configuration Restore"
echo "=========================================="
echo ""
echo "⚠️  WARNING: This will overwrite current Jenkins configuration!"
echo ""
echo "Backup source: $BACKUP_PATH"
echo ""
read -p "Are you sure you want to continue? (yes/no): " -r
if [[ ! $REPLY =~ ^[Yy][Ee][Ss]$ ]]; then
    echo "Restore cancelled."
    exit 0
fi

echo ""
echo "⏳ Stopping Jenkins..."
cd /home/fosqa/jenkins-master
docker-compose down

echo ""
echo "📦 Restoring configuration files..."

# Copy backup files to container volume
for backup_file in "${BACKUP_PATH}"/*.tar.gz; do
    if [ -f "$backup_file" ]; then
        filename=$(basename "$backup_file")
        echo "   Restoring: $filename"

        # Start temporary container to extract files
        docker run --rm \
            -v jenkins_data:/var/jenkins_home \
            -v "${backup_file}:/tmp/${filename}" \
            ubuntu:20.04 \
            bash -c "cd /var/jenkins_home && tar xzf /tmp/${filename}"
    fi
done

echo ""
echo "⏳ Starting Jenkins..."
docker-compose up -d

echo ""
echo "=========================================="
echo "✅ Restore Complete!"
echo "=========================================="
echo ""
echo "Jenkins is starting up. Wait ~60 seconds then access:"
echo "   http://10.96.227.206:8080"
echo ""
echo "Login with: fosqa / Ftnt123!"
echo ""
