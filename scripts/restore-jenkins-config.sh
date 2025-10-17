#!/bin/bash
#
# Jenkins Restore Script
# ======================
# Description:
#   Restores selected Jenkins configuration archives (core/users/secrets/jobs) from a backup
#   directory into the Jenkins home of a Docker container (default: jenkins-master).
#
# Prerequisites:
#   - Docker installed and accessible (docker CLI)
#   - Target container exists and has /var/jenkins_home mounted (bind or named volume)
#   - Backup directory created by backup-jenkins-config.sh
#
# Usage:
#   restore-jenkins-config.sh /home/fosqa/jenkins-master/backups/jenkins_config_YYYYmmdd_HHMMSS
#   restore-jenkins-config.sh --help
#
# Environment variables:
#   JENKINS_CONTAINER   Container name (default: jenkins-master)
#
# What it does:
#   - Validates input and Docker environment
#   - Detects the Jenkins home mount (type + source)
#   - Stops the Jenkins container if running
#   - Extracts any present archives (core/users/secrets/jobs) from backup into /var/jenkins_home
#   - Starts the Jenkins container
#
# Notes:
#   - Only known archives are extracted; missing archives are skipped silently
#   - Safe for both bind mounts and named volumes
#   - Use “docker logs -f <container>” to watch Jenkins start-up after restore
#
# Exit codes:
#   0  Success
#   1  Usage error or invalid backup path
#   2  Docker not available or container not suitable
#   3  Failed to determine Jenkins home mount
#

set -Eeuo pipefail

JENKINS_CONTAINER="${JENKINS_CONTAINER:-jenkins-master}"

usage() {
  echo "Usage: $0 /home/fosqa/jenkins-master/backups/jenkins_config_YYYYmmdd_HHMMSS"
  echo ""
  echo "Recent backups:"
  find /home/fosqa/jenkins-master/backups -maxdepth 1 -type d -name "jenkins_config_*" \
    -printf "%T@ %p\n" 2>/dev/null | sort -nr | head -5 | awk '{ $1=""; sub(/^ /,""); print " - " $0 }'
}

# Print usage on -h/--help
if [ "${1:-}" = "-h" ] || [ "${1:-}" = "--help" ]; then
  usage
  exit 0
fi

BACKUP_PATH="${1:-}"
if [ -z "${BACKUP_PATH}" ]; then
  usage
  exit 1
fi

[ -d "${BACKUP_PATH}" ] || { echo "❌ Backup path not found: ${BACKUP_PATH}"; exit 1; }
command -v docker >/dev/null || { echo "❌ docker not found"; exit 1; }


# BACKUP_PATH="${1:-}"
# [ -n "${BACKUP_PATH}" ] || { usage; exit 1; }
# [ -d "${BACKUP_PATH}" ] || { echo "❌ Backup path not found: ${BACKUP_PATH}"; exit 1; }

# Ensure docker available
# command -v docker >/dev/null || { echo "❌ docker not found"; exit 1; }

echo "=========================================="
echo "🔁 Restoring Jenkins Configuration"
echo "=========================================="
echo "Backup: ${BACKUP_PATH}"
echo "Container: ${JENKINS_CONTAINER}"
echo ""

# Resolve Jenkins home mount (volume name or bind path)
MOUNT_INFO="$(docker inspect -f '{{json .Mounts}}' "${JENKINS_CONTAINER}" 2>/dev/null || true)"
if [ -z "${MOUNT_INFO}" ] || ! echo "${MOUNT_INFO}" | grep -q '/var/jenkins_home'; then
  echo "❌ Could not inspect container or /var/jenkins_home not mounted. Ensure '${JENKINS_CONTAINER}' exists."
  exit 1
fi

# Extract mount source and type
JH_TYPE="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/jenkins_home"}}{{.Type}}{{end}}{{end}}' "${JENKINS_CONTAINER}")"
JH_SRC="$(docker inspect -f '{{range .Mounts}}{{if eq .Destination "/var/jenkins_home"}}{{if eq .Type "volume"}}{{.Name}}{{else}}{{.Source}}{{end}}{{end}}{{end}}' "${JENKINS_CONTAINER}")"

if [ -z "${JH_TYPE}" ] || [ -z "${JH_SRC}" ]; then
  echo "❌ Failed to determine Jenkins home mount"
  exit 1
fi

echo "🔍 Jenkins home mount: type=${JH_TYPE} src=${JH_SRC}"

# Stop Jenkins (if running)
if docker inspect -f '{{.State.Running}}' "${JENKINS_CONTAINER}" 2>/dev/null | grep -q true; then
  echo "⏹️  Stopping Jenkins container..."
  docker stop "${JENKINS_CONTAINER}" >/dev/null
fi

# # Choose bind or volume mount for helper container
# if [ "${JH_TYPE}" = "bind" ]; then
#   HELPER_MOUNT="-v ${JH_SRC}:/var/jenkins_home"
# else
#   HELPER_MOUNT="-v ${JH_SRC}:/var/jenkins_home"
# fi

HELPER_MOUNT="-v ${JH_SRC}:/var/jenkins_home"

# Extract available archives
echo "📦 Restoring archives (core/users/secrets/jobs if present)..."
docker run --rm \
  ${HELPER_MOUNT} \
  -v "${BACKUP_PATH}:/backup:ro" \
  ubuntu:22.04 bash -c '
    set -e
    cd /var/jenkins_home
    for f in /backup/jenkins_core_*.tar.gz /backup/jenkins_users_*.tar.gz /backup/jenkins_secrets_*.tar.gz /backup/jenkins_jobs_*.tar.gz; do
      if [ -f "$f" ]; then
        echo " - Extracting $(basename "$f")"
        tar xzf "$f"
      fi
    done
    echo "✅ Restore extraction complete"
  '

# Start Jenkins again
echo "▶️  Starting Jenkins container..."
docker start "${JENKINS_CONTAINER}" >/dev/null || true

echo ""
echo "=========================================="
echo "✅ Restore Complete!"
echo "=========================================="
echo "Check logs: docker logs -f ${JENKINS_CONTAINER}"
