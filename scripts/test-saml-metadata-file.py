#!/usr/bin/env python3
"""
Test local SAML metadata file
filepath: /home/fosqa/jenkins-master/scripts/test-saml-metadata-file.py
"""

import os
import xml.etree.ElementTree as ET

METADATA_FILE = "/home/fosqa/jenkins-master/ldap/idp-metadata.xml"


def test_saml_metadata_file():
    """Test if local SAML IdP metadata file is valid"""
    print("=" * 60)
    print("🔍 Jenkins SAML - Local Metadata File Test")
    print("=" * 60)
    print()

    print(f"Testing metadata file:")
    print(f"  {METADATA_FILE}")
    print()

    # Check if file exists
    if not os.path.exists(METADATA_FILE):
        print(f"❌ Metadata file not found: {METADATA_FILE}")
        print()
        print("Please save the IdP metadata XML to this location.")
        return False

    print("✅ Metadata file exists")
    print()

    try:
        # Parse XML
        print("⏳ Parsing XML...")
        tree = ET.parse(METADATA_FILE)
        root = tree.getroot()
        print("✅ Metadata XML is valid")
        print()

        # Extract important info
        namespaces = {"md": "urn:oasis:names:tc:SAML:2.0:metadata", "ds": "http://www.w3.org/2000/09/xmldsig#"}

        # Get EntityID
        entity_id = root.get("entityID")
        if entity_id:
            print(f"🆔 Entity ID: {entity_id}")
            print()

        # Find SSO endpoints
        sso_endpoints = root.findall(".//md:SingleSignOnService", namespaces)
        if sso_endpoints:
            print("📍 Single Sign-On Endpoints:")
            for endpoint in sso_endpoints:
                binding = endpoint.get("Binding", "Unknown")
                location = endpoint.get("Location", "Unknown")
                print(f"   Binding: {binding.split(':')[-1]}")
                print(f"   Location: {location}")
            print()
        else:
            print("⚠️  No SSO endpoints found")
            print()

        # Find SLO endpoints
        slo_endpoints = root.findall(".//md:SingleLogoutService", namespaces)
        if slo_endpoints:
            print("🚪 Single Logout Endpoints:")
            for endpoint in slo_endpoints:
                binding = endpoint.get("Binding", "Unknown")
                location = endpoint.get("Location", "Unknown")
                print(f"   Binding: {binding.split(':')[-1]}")
                print(f"   Location: {location}")
            print()

        # Check for signing certificate
        cert_elements = root.findall(".//ds:X509Certificate", namespaces)
        if cert_elements:
            print(f"🔐 Found {len(cert_elements)} signing certificate(s)")
            print()
        else:
            print("⚠️  No signing certificate found (might be okay)")
            print()

        print("=" * 60)
        print("✅ SAML METADATA FILE TEST PASSED")
        print("=" * 60)
        print()
        print("Metadata file is ready to use with Jenkins!")
        print()
        print("Next steps:")
        print("1. ✅ Metadata file validated")
        print("2. Update jenkins-saml.yaml to use file path")
        print("3. Backup Jenkins config")
        print("4. Apply SAML configuration")
        print("5. Register Jenkins with IdP (ask MIS colleague)")

        return True

    except ET.ParseError as e:
        print(f"❌ Invalid XML metadata: {str(e)}")
        return False
    except Exception as e:
        print(f"❌ Unexpected error: {str(e)}")
        return False


if __name__ == "__main__":
    import sys

    success = test_saml_metadata_file()
    sys.exit(0 if success else 1)
