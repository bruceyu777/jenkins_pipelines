#!/usr/bin/env python3
"""
Test LDAP connection before applying to Jenkins
filepath: /home/fosqa/jenkins-master/scripts/test-ldap-connection.py
"""

import sys
from getpass import getpass

import ldap

# LDAP Configuration
LDAP_SERVER = "ldap://fac-dev.corp.fortinet.com:389"
LDAP_BASE_DN = "dc=fortinet,dc=com"
LDAP_USER_SEARCH_BASE = "OU=Users,dc=fortinet,dc=com"
LDAP_GROUP_SEARCH_BASE = "OU=Groups,dc=fortinet,dc=com"


def test_ldap_connection():
    """Test LDAP server connectivity"""
    print("=" * 60)
    print("🔍 Jenkins LDAP Migration - Connection Test")
    print("=" * 60)
    print()

    # Get credentials
    print("Enter LDAP service account credentials:")
    bind_dn = input(f"Bind DN (e.g., CN=jenkins-service,OU=ServiceAccounts,{LDAP_BASE_DN}): ").strip()
    bind_password = getpass("Password: ")

    if not bind_dn or not bind_password:
        print("❌ Credentials required!")
        return False

    print()
    print(f"Testing connection to: {LDAP_SERVER}")
    print(f"Base DN: {LDAP_BASE_DN}")
    print()

    try:
        # Initialize LDAP connection
        print("⏳ Connecting to LDAP server...")
        conn = ldap.initialize(LDAP_SERVER)
        conn.protocol_version = ldap.VERSION3
        conn.set_option(ldap.OPT_REFERRALS, 0)

        # Bind with service account
        print("⏳ Authenticating...")
        conn.simple_bind_s(bind_dn, bind_password)
        print("✅ Authentication successful!")
        print()

        # Test user search
        print("⏳ Testing user search...")
        test_username = input("Enter a test username to search (or press Enter to skip): ").strip()

        if test_username:
            search_filter = f"(sAMAccountName={test_username})"
            result = conn.search_s(LDAP_USER_SEARCH_BASE, ldap.SCOPE_SUBTREE, search_filter, ["cn", "mail", "displayName", "memberOf"])

            if result:
                print(f"✅ Found user: {test_username}")
                dn, attrs = result[0]
                print(f"   DN: {dn}")
                print(f"   Display Name: {attrs.get('displayName', [b'N/A'])[0].decode()}")
                print(f"   Email: {attrs.get('mail', [b'N/A'])[0].decode()}")

                groups = attrs.get("memberOf", [])
                if groups:
                    print(f"   Groups ({len(groups)}):")
                    for group in groups[:5]:  # Show first 5 groups
                        group_cn = group.decode().split(",")[0].replace("CN=", "")
                        print(f"      - {group_cn}")
                    if len(groups) > 5:
                        print(f"      ... and {len(groups) - 5} more")
            else:
                print(f"⚠️  User not found: {test_username}")

        print()

        # Test group search
        print("⏳ Testing group search...")
        search_filter = "(objectClass=group)"
        result = conn.search_s(LDAP_GROUP_SEARCH_BASE, ldap.SCOPE_SUBTREE, search_filter, ["cn"])

        if result:
            print(f"✅ Found {len(result)} groups in {LDAP_GROUP_SEARCH_BASE}")
            print("   Sample groups:")
            for dn, attrs in result[:5]:
                group_name = attrs.get("cn", [b"Unknown"])[0].decode()
                print(f"      - {group_name}")
        else:
            print("⚠️  No groups found")

        conn.unbind_s()

        print()
        print("=" * 60)
        print("✅ LDAP CONNECTION TEST PASSED")
        print("=" * 60)
        print()
        print("Next steps:")
        print("1. Save the bind DN and password securely")
        print("2. Review jcasc/jenkins-ldap.yaml configuration")
        print("3. Run backup-jenkins-config.sh before applying")
        print("4. Apply LDAP configuration when ready")

        return True

    except ldap.INVALID_CREDENTIALS:
        print("❌ Authentication failed - Invalid credentials")
        return False
    except ldap.SERVER_DOWN:
        print(f"❌ Cannot connect to LDAP server: {LDAP_SERVER}")
        print("   Check network connectivity and server address")
        return False
    except ldap.LDAPError as e:
        print(f"❌ LDAP Error: {str(e)}")
        return False
    except Exception as e:
        print(f"❌ Unexpected error: {str(e)}")
        return False


if __name__ == "__main__":
    success = test_ldap_connection()
    sys.exit(0 if success else 1)
