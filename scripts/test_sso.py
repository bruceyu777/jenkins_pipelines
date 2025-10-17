#!/usr/bin/env python3
# filepath: /home/fosqa/jenkins-master/scripts/test-saml-auth.py

"""
SAML Authentication Test Script
================================
Tests SAML SSO authentication flow with IdP using username/password.
Simulates what Jenkins would do when authenticating via SAML.
"""

import base64
import re
import sys
import urllib.parse
import uuid
import xml.etree.ElementTree as ET
import zlib
from datetime import datetime
from getpass import getpass

import requests

# Disable SSL warnings for internal certificates
import urllib3

urllib3.disable_warnings(urllib3.exceptions.InsecureRequestWarning)


class SAMLAuthTester:
    def __init__(self, idp_metadata_path):
        self.idp_metadata_path = idp_metadata_path
        self.idp_sso_url = None
        self.idp_entity_id = None
        self.idp_logout_url = None
        self.session = requests.Session()
        self.session.verify = False  # Accept self-signed certs

    def parse_idp_metadata(self):
        """Parse IdP metadata XML to extract endpoints"""
        print("⏳ Parsing IdP metadata...")

        try:
            tree = ET.parse(self.idp_metadata_path)
            root = tree.getroot()

            # Define namespaces
            ns = {"md": "urn:oasis:names:tc:SAML:2.0:metadata", "ds": "http://www.w3.org/2000/09/xmldsig#"}

            # Extract Entity ID
            self.idp_entity_id = root.get("entityID")
            print(f"✅ IdP Entity ID: {self.idp_entity_id}")

            # Extract SSO URLs
            sso_services = root.findall(".//md:SingleSignOnService", ns)
            for sso in sso_services:
                binding = sso.get("Binding")
                location = sso.get("Location")
                if "HTTP-POST" in binding or "HTTP-Redirect" in binding:
                    self.idp_sso_url = location
                    print(f"✅ SSO URL: {location}")
                    print(f"   Binding: {binding}")
                    break

            # Extract Logout URL
            logout_services = root.findall(".//md:SingleLogoutService", ns)
            if logout_services:
                self.idp_logout_url = logout_services[0].get("Location")
                print(f"✅ Logout URL: {self.idp_logout_url}")

            # Extract certificate
            cert_elem = root.find(".//ds:X509Certificate", ns)
            if cert_elem is not None:
                cert = cert_elem.text.strip()
                print(f"✅ Found signing certificate ({len(cert)} chars)")

            if not self.idp_sso_url:
                print("❌ Could not find SSO URL in metadata")
                return False

            return True

        except Exception as e:
            print(f"❌ Error parsing metadata: {e}")
            return False

    def create_saml_authn_request(self, sp_entity_id, acs_url):
        """Create a SAML AuthnRequest (simplified)"""
        request_id = f"_{uuid.uuid4()}"
        issue_instant = datetime.utcnow().strftime("%Y-%m-%dT%H:%M:%SZ")

        saml_request = f"""<?xml version="1.0" encoding="UTF-8"?>
<samlp:AuthnRequest
    xmlns:samlp="urn:oasis:names:tc:SAML:2.0:protocol"
    xmlns:saml="urn:oasis:names:tc:SAML:2.0:assertion"
    ID="{request_id}"
    Version="2.0"
    IssueInstant="{issue_instant}"
    Destination="{self.idp_sso_url}"
    ProtocolBinding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-POST"
    AssertionConsumerServiceURL="{acs_url}">
    <saml:Issuer>{sp_entity_id}</saml:Issuer>
    <samlp:NameIDPolicy
        Format="urn:oasis:names:tc:SAML:2.0:nameid-format:unspecified"
        AllowCreate="true"/>
</samlp:AuthnRequest>"""

        return saml_request

    def encode_saml_request(self, saml_request):
        """Encode SAML request for HTTP-Redirect binding"""
        # Deflate
        compressed = zlib.compress(saml_request.encode("utf-8"))[2:-4]
        # Base64 encode
        encoded = base64.b64encode(compressed).decode("utf-8")
        # URL encode
        return urllib.parse.quote_plus(encoded)

    def test_idp_accessibility(self):
        """Test if IdP SSO endpoint is accessible"""
        print("\n" + "=" * 60)
        print("🌐 Testing IdP Accessibility")
        print("=" * 60)

        try:
            print(f"⏳ Connecting to: {self.idp_sso_url}")
            response = self.session.get(self.idp_sso_url, timeout=10, allow_redirects=True)

            print(f"✅ HTTP Status: {response.status_code}")
            print(f"✅ Response Size: {len(response.content)} bytes")

            # Check if it's a login page
            if "login" in response.text.lower() or "username" in response.text.lower():
                print("✅ Detected login page (good sign!)")
                return True
            elif response.status_code == 200:
                print("⚠️  Page accessible but no login form detected")
                return True
            else:
                print(f"⚠️  Unexpected response: {response.status_code}")
                return False

        except requests.exceptions.ConnectionError:
            print("❌ Connection failed - IdP unreachable")
            return False
        except requests.exceptions.Timeout:
            print("❌ Connection timeout")
            return False
        except Exception as e:
            print(f"❌ Error: {e}")
            return False

    def attempt_authentication(self, username, password):
        """Attempt to authenticate with username/password"""
        print("\n" + "=" * 60)
        print("🔐 Attempting SAML Authentication")
        print("=" * 60)

        try:
            # Step 1: Create SAML AuthnRequest
            print("\n📝 Step 1: Creating SAML AuthnRequest...")
            sp_entity_id = "https://releaseqa-stackjenkins.corp.fortinet.com"
            acs_url = f"{sp_entity_id}/securityRealm/finishLogin"

            saml_request = self.create_saml_authn_request(sp_entity_id, acs_url)
            encoded_request = self.encode_saml_request(saml_request)

            print(f"✅ Request ID created")
            print(f"✅ SP Entity ID: {sp_entity_id}")

            # Step 2: Send AuthnRequest to IdP
            print("\n🌐 Step 2: Sending AuthnRequest to IdP...")

            # Build SSO URL with SAMLRequest parameter
            sso_url_with_params = f"{self.idp_sso_url}?SAMLRequest={encoded_request}"

            response = self.session.get(sso_url_with_params, allow_redirects=True, timeout=15)

            print(f"✅ Response received: {response.status_code}")
            print(f"✅ Final URL: {response.url[:80]}...")

            # Step 3: Look for login form
            print("\n🔍 Step 3: Analyzing login page...")

            html = response.text

            # Try to find form action
            form_action_match = re.search(r'<form[^>]*action=["\']([^"\']+)["\']', html, re.IGNORECASE)
            if form_action_match:
                form_action = form_action_match.group(1)
                print(f"✅ Found form action: {form_action[:80]}...")
            else:
                print("⚠️  No form action found")
                form_action = response.url

            # Try to find username/password field names
            username_field = self._find_field_name(html, ["username", "user", "email", "login"])
            password_field = self._find_field_name(html, ["password", "pass", "pwd"])

            if username_field:
                print(f"✅ Found username field: {username_field}")
            else:
                print("⚠️  Username field not found, using default: 'username'")
                username_field = "username"

            if password_field:
                print(f"✅ Found password field: {password_field}")
            else:
                print("⚠️  Password field not found, using default: 'password'")
                password_field = "password"

            # Step 4: Submit credentials
            print(f"\n🔑 Step 4: Submitting credentials for user: {username}")

            # Build absolute form action URL
            if form_action.startswith("http"):
                login_url = form_action
            elif form_action.startswith("/"):
                parsed_url = urllib.parse.urlparse(response.url)
                login_url = f"{parsed_url.scheme}://{parsed_url.netloc}{form_action}"
            else:
                login_url = urllib.parse.urljoin(response.url, form_action)

            print(f"⏳ POSTing to: {login_url[:80]}...")

            # Prepare form data
            form_data = {
                username_field: username,
                password_field: password,
            }

            # Try to find any hidden fields
            hidden_fields = re.findall(r'<input[^>]*type=["\']hidden["\'][^>]*name=["\']([^"\']+)["\'][^>]*value=["\']([^"\']*)["\']', html, re.IGNORECASE)
            for field_name, field_value in hidden_fields:
                form_data[field_name] = field_value
                print(f"   Including hidden field: {field_name}")

            # Submit login form
            auth_response = self.session.post(login_url, data=form_data, allow_redirects=True, timeout=15)

            print(f"✅ Authentication response: {auth_response.status_code}")
            print(f"✅ Final URL: {auth_response.url[:80]}...")

            # Step 5: Check for success indicators
            print("\n🔍 Step 5: Analyzing authentication result...")

            auth_html = auth_response.text.lower()

            # Check for SAML response (success indicator)
            if "samlresponse" in auth_html:
                print("✅✅✅ SUCCESS! SAML Response received!")
                print("✅ Authentication successful - IdP accepted credentials")

                # Try to extract SAML response
                saml_response_match = re.search(r'name=["\']SAMLResponse["\'][^>]*value=["\']([^"\']+)["\']', auth_response.text, re.IGNORECASE)
                if saml_response_match:
                    saml_resp = saml_response_match.group(1)
                    print(f"✅ SAML Response length: {len(saml_resp)} chars")

                return True

            # Check for error messages
            error_indicators = ["invalid", "incorrect", "failed", "error", "denied", "wrong", "authentication failed", "login failed"]

            found_error = False
            for indicator in error_indicators:
                if indicator in auth_html:
                    print(f"❌ Found error indicator: '{indicator}'")
                    found_error = True

            if found_error:
                print("❌ Authentication appears to have failed")
                print("   Check username/password or 2FA requirements")
                return False

            # Check if we're still on login page
            if "login" in auth_html and (username_field in auth_html or "username" in auth_html):
                print("❌ Still on login page - authentication likely failed")
                return False

            # Check for 2FA/MFA prompt
            mfa_indicators = ["verification", "authenticate", "token", "code", "otp", "two-factor", "2fa", "mfa"]
            for indicator in mfa_indicators:
                if indicator in auth_html:
                    print(f"⚠️  2FA/MFA detected: '{indicator}'")
                    print("⚠️  Additional authentication required")
                    print("⚠️  This test cannot complete 2FA automatically")
                    return None  # Neither success nor failure

            # Ambiguous result
            print("⚠️  Authentication result unclear")
            print(f"   Response size: {len(auth_response.content)} bytes")
            print("   Manual verification recommended")

            return None

        except Exception as e:
            print(f"❌ Authentication error: {e}")
            import traceback

            traceback.print_exc()
            return False

    def _find_field_name(self, html, possible_names):
        """Find input field name in HTML"""
        for name in possible_names:
            # Look for name= attribute
            pattern = rf'<input[^>]*name=["\']([^"\']*{name}[^"\']*)["\']'
            match = re.search(pattern, html, re.IGNORECASE)
            if match:
                return match.group(1)
        return None


def main():
    print("=" * 60)
    print("🔐 SAML Authentication Test Tool")
    print("=" * 60)
    print()

    # Path to IdP metadata
    metadata_path = "/home/fosqa/jenkins-master/jcasc/idp-metadata.xml"

    print(f"📄 IdP Metadata: {metadata_path}")
    print()

    # Initialize tester
    tester = SAMLAuthTester(metadata_path)

    # Parse IdP metadata
    if not tester.parse_idp_metadata():
        print("\n❌ Failed to parse IdP metadata")
        sys.exit(1)

    # Test IdP accessibility
    if not tester.test_idp_accessibility():
        print("\n❌ IdP is not accessible")
        print("   Check network connectivity and VPN")
        sys.exit(1)

    # Get credentials
    print("\n" + "=" * 60)
    print("🔑 Enter Your SSO Credentials")
    print("=" * 60)
    username = input("Username: ").strip()
    password = getpass("Password: ")

    if not username or not password:
        print("❌ Username and password are required")
        sys.exit(1)

    # Attempt authentication
    result = tester.attempt_authentication(username, password)

    # Summary
    print("\n" + "=" * 60)
    print("📊 Test Summary")
    print("=" * 60)

    if result is True:
        print("✅ Status: AUTHENTICATION SUCCESSFUL")
        print("✅ Your credentials are valid")
        print("✅ IdP metadata is configured correctly")
        print("\n✅ Jenkins SAML integration should work!")
    elif result is False:
        print("❌ Status: AUTHENTICATION FAILED")
        print("❌ Possible reasons:")
        print("   - Invalid username/password")
        print("   - Account locked or disabled")
        print("   - IdP configuration issue")
        print("\n⚠️  Verify credentials and try again")
    elif result is None:
        print("⚠️  Status: ADDITIONAL AUTHENTICATION REQUIRED")
        print("⚠️  2FA/MFA is likely enabled")
        print("\n💡 This means:")
        print("   - Primary credentials are valid")
        print("   - But 2FA is required (can't be automated)")
        print("   - Jenkins SAML will require 2FA for users")

    print()


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        print("\n\n⚠️  Test interrupted by user")
        sys.exit(1)
    except Exception as e:
        print(f"\n❌ Unexpected error: {e}")
        import traceback

        traceback.print_exc()
        sys.exit(1)
