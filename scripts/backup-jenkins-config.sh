#!/bin/bash
#
# Jenkins Backup Script
# =====================
# Description:
#   Creates a timestamped backup of key Jenkins home content from a running Docker
#   container (default: jenkins-master) into /home/fosqa/jenkins-master/backups.
#   Backs up: core configs, users, secrets, and job configurations (excluding build history).
#
# Prerequisites:
#   - Docker CLI installed and usable
#   - Jenkins container exists (default name: jenkins-master)
#   - Container has /var/jenkins_home mounted
#
# Usage:
#   ./scripts/backup-jenkins-config.sh
#
# Environment variables:
#   JENKINS_CONTAINER   Name of the Jenkins container (default: jenkins-master)
#
# What it does:
#   - Verifies docker and container presence
#   - Starts container if it is stopped (so files are accessible)
#   - Archives:
#       • Core config files (config.xml, credentials.xml, hudson.*.xml, jenkins.*.xml, keys)
#       • Users directory
#       • Secrets directory
#       • Job configurations only (config.xml and nextBuildNumber), not build history
#   - Copies tarballs to a new backup directory with a manifest
#
# Exit codes:
#   0  Success
#   1  Missing docker, container not found, or other runtime error
#
# Notes:
#   - Job build history is intentionally excluded to keep backups small
#   - The manifest includes a one-line restore command for this exact backup

set -Eeuo pipefail

JENKINS_CONTAINER="${JENKINS_CONTAINER:-jenkins-master}"
BACKUP_DIR="/home/fosqa/jenkins-master/backups"
TIMESTAMP="$(date +%Y%m%d_%H%M%S)"
BACKUP_PATH="${BACKUP_DIR}/jenkins_config_${TIMESTAMP}"


# --- Help/usage ---
usage() {
  cat <<EOF
Jenkins Backup Script

Usage:
  $(basename "$0") [options]

Options:
  -h, --help          Show this help and exit

Environment:
  JENKINS_CONTAINER   Jenkins container name (default: ${JENKINS_CONTAINER})
  BACKUP_DIR          Backup root directory (default: ${BACKUP_DIR})

Creates a timestamped backup at:
  ${BACKUP_PATH}

Examples:
  ${0}                          # run backup with defaults
  JENKINS_CONTAINER=jenkins2 ${0}  # target a different container
EOF
}

# Print help or reject unknown args
if [[ "${1:-}" == "-h" || "${1:-}" == "--help" ]]; then
  usage
  exit 0
elif [[ $# -gt 0 ]]; then
  echo "Unknown option: $1"
  echo
  usage
  exit 1
fi


echo "=========================================="
echo "🔒 Jenkins Configuration Backup"
echo "=========================================="
echo ""

# Pre-flight
if ! command -v docker >/dev/null 2>&1; then
  echo "❌ docker not found in PATH"; exit 1
fi

if ! docker ps -a --format '{{.Names}}' | grep -qx "${JENKINS_CONTAINER}"; then
  echo "❌ Container '${JENKINS_CONTAINER}' not found"; exit 1
fi

if [ "$(docker inspect -f '{{.State.Running}}' "${JENKINS_CONTAINER}")" != "true" ]; then
  echo "ℹ️  Starting container '${JENKINS_CONTAINER}'..."
  docker start "${JENKINS_CONTAINER}" >/dev/null
fi

# Create backup directory
mkdir -p "${BACKUP_PATH}"

echo "⏳ Backing up Jenkins configuration..."
echo ""

# Backup essential configuration files (fast, small)
# echo "📄 Step 1/4: Backing up core configuration..."
# docker exec "${JENKINS_CONTAINER}" bash -c "
#     set -e
#     cd /var/jenkins_home
#     tar czf /tmp/jenkins_core_${TIMESTAMP}.tar.gz \
#         config.xml \
#         credentials.xml \
#         identity.key* \
#         secret.key* \
#         nodeMonitors.xml \
#         hudson.*.xml \
#         jenkins.*.xml \
#         2>&1
# " && echo "   ✅ Core config backed up" || echo "   ⚠️  Some files may not exist (OK)"


echo "📄 Step 1/4: Backing up core configuration..."
docker exec "${JENKINS_CONTAINER}" bash -c '
    set -e
    cd /var/jenkins_home
    shopt -s nullglob
    files=(config.xml credentials.xml nodeMonitors.xml hudson.*.xml jenkins.*.xml identity.key* secret.key*)
    if [ ${#files[@]} -eq 0 ]; then
        echo "   ⚠️  No core files found to back up"
        exit 0
    fi
    tar -czf /tmp/jenkins_core_'"${TIMESTAMP}"'.tar.gz "${files[@]}"
' && echo "   ✅ Core config backed up" || echo "   ⚠️  Some core files may not exist (OK)"

docker cp "${JENKINS_CONTAINER}:/tmp/jenkins_core_${TIMESTAMP}.tar.gz" "${BACKUP_PATH}/" 2>/dev/null || true


docker cp "${JENKINS_CONTAINER}:/tmp/jenkins_core_${TIMESTAMP}.tar.gz" "${BACKUP_PATH}/" 2>/dev/null || true

# Backup users directory (small)
echo ""
echo "👥 Step 2/4: Backing up users..."
docker exec "${JENKINS_CONTAINER}" bash -c "
    set -e
    cd /var/jenkins_home
    if [ -d users ]; then
        tar czf /tmp/jenkins_users_${TIMESTAMP}.tar.gz users/
        echo '   ✅ Users backed up'
    else
        echo '   ⚠️  No users directory found'
    fi
"
docker cp "${JENKINS_CONTAINER}:/tmp/jenkins_users_${TIMESTAMP}.tar.gz" "${BACKUP_PATH}/" 2>/dev/null || true

# Backup secrets directory (small)
echo ""
echo "🔐 Step 3/4: Backing up secrets..."
docker exec "${JENKINS_CONTAINER}" bash -c "
    set -e
    cd /var/jenkins_home
    if [ -d secrets ]; then
        tar czf /tmp/jenkins_secrets_${TIMESTAMP}.tar.gz secrets/
        echo '   ✅ Secrets backed up'
    else
        echo '   ⚠️  No secrets directory found'
    fi
"
docker cp "${JENKINS_CONTAINER}:/tmp/jenkins_secrets_${TIMESTAMP}.tar.gz" "${BACKUP_PATH}/" 2>/dev/null || true

# Backup job configurations ONLY (without build history - fast)
# echo ""
# echo "📋 Step 4/4: Backing up job configurations (excluding build history)..."
# docker exec "${JENKINS_CONTAINER}" bash -c "
#     set -e
#     cd /var/jenkins_home
#     if [ -d jobs ]; then
#         # Only backup config.xml from each job, exclude builds/ directory
#         find jobs -name 'config.xml' -o -name 'nextBuildNumber' | \
#         tar czf /tmp/jenkins_jobs_${TIMESTAMP}.tar.gz -T - 2>/dev/null
#         echo '   ✅ Job configurations backed up (build history excluded)'
#     else
#         echo '   ⚠️  No jobs directory found'
#     fi
# "
# docker cp "${JENKINS_CONTAINER}:/tmp/jenkins_jobs_${TIMESTAMP}.tar.gz" "${BACKUP_PATH}/" 2>/dev/null || true


echo ""
echo "📋 Step 4/4: Backing up job configurations (excluding build history)..."
docker exec "${JENKINS_CONTAINER}" bash -c '
    set -e
    cd /var/jenkins_home
    if [ -d jobs ]; then
        # Only config.xml and nextBuildNumber; robust to spaces in paths
        find jobs -type f \( -name "config.xml" -o -name "nextBuildNumber" \) -print0 \
        | tar -czf /tmp/jenkins_jobs_'"${TIMESTAMP}"'.tar.gz --null -T -
        echo "   ✅ Job configurations backed up (build history excluded)"
    else
        echo "   ⚠️  No jobs directory found"
    fi
'
docker cp "${JENKINS_CONTAINER}:/tmp/jenkins_jobs_${TIMESTAMP}.tar.gz" "${BACKUP_PATH}/" 2>/dev/null || true



# Cleanup temp files
echo ""
echo "🧹 Cleaning up temporary files..."
docker exec "${JENKINS_CONTAINER}" bash -c "
    rm -f /tmp/jenkins_core_${TIMESTAMP}.tar.gz
    rm -f /tmp/jenkins_users_${TIMESTAMP}.tar.gz
    rm -f /tmp/jenkins_secrets_${TIMESTAMP}.tar.gz
    rm -f /tmp/jenkins_jobs_${TIMESTAMP}.tar.gz
" || true

# Create backup manifest
echo ""
echo "📝 Creating backup manifest..."
cat > "${BACKUP_PATH}/BACKUP_INFO.txt" << EOF
Jenkins Configuration Backup
============================
Timestamp: ${TIMESTAMP}
Backup Location: ${BACKUP_PATH}
Jenkins Container: ${JENKINS_CONTAINER}

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

💾 To restore from this backup:
   ./scripts/restore-jenkins-config.sh ${BACKUP_PATH}

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
