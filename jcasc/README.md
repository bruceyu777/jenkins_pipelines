# Jenkins LDAP Migration

## Overview
This directory contains Jenkins Configuration as Code (JCasC) files for migrating from local authentication to LDAP while maintaining local admin access.

## Files
- `jenkins-ldap.yaml` - Main JCasC configuration with LDAP + local admin

## Migration Steps

### Phase 1: Preparation
1. **Test LDAP connectivity:**
   ```bash
   cd /home/fosqa/jenkins-master
   python3 scripts/test-ldap-connection.py
   ```

2. **Backup current configuration:**
   ```bash
   ./scripts/backup-jenkins-config.sh
   ```

### Phase 2: Apply Configuration
1. **Review LDAP settings** in `jenkins-ldap.yaml`

2. **Set LDAP bind password:**
   ```bash
   export LDAP_BIND_PASSWORD="your-secure-password"
   ```

3. **Apply configuration:**
   ```bash
   ./scripts/apply-ldap-config.sh
   ```

### Phase 3: Verification
1. Login with local admin: `admin` / `ftnt123!`
2. Test LDAP login with domain user
3. Verify permissions are correct

## Rollback
If issues occur:
```bash
# Restore from backup
cd /home/fosqa/jenkins-master/backups/jenkins_config_YYYYMMDD_HHMMSS
# Follow restoration instructions from backup script
```

## Security
- Local admin always works as fallback
- LDAP bind password stored in environment variable
- Anonymous read access enabled for CI/CD
