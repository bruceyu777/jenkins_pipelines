#!/usr/bin/env python3
"""
check_build_ready.py

Check if a specific FortiOS build is ready on the Image Server via REST API.
NOTE: Rsync should be triggered separately from the Jenkins pipeline.

Usage:
    python3 check_build_ready.py --release 7.6.0 --build 3620 --build-keyword KVM
"""

import argparse
import json
import logging
import sys
from typing import Dict, Tuple

import requests

# Configure logging
logging.basicConfig(level=logging.INFO, format="%(asctime)s - %(levelname)s - %(message)s")
logger = logging.getLogger(__name__)

# Image Server Configuration
IMAGE_SERVER_API_URL = "http://172.18.52.254:8090/api/buildready"


def check_build_ready(project: str, version: str, build: str, build_keyword: str) -> Tuple[bool, Dict]:
    """
    Check if a build is ready on the Image Server via REST API.

    Args:
        project: Project name (e.g., "FortiOS")
        version: Major version (e.g., "7")
        build: Build number (e.g., "3620")
        build_keyword: Build keyword (e.g., "KVM")

    Returns:
        Tuple of (is_ready: bool, response_data: dict)
    """
    # Prepare API request parameters
    params = {"project": project, "version": version, "build": build, "build_keyword": build_keyword}

    logger.info(f"Checking build readiness with parameters:")
    logger.info(f"  Project: {project}")
    logger.info(f"  Version: {version}")
    logger.info(f"  Build: {build}")
    logger.info(f"  Build Keyword: {build_keyword}")
    logger.info(f"  API URL: {IMAGE_SERVER_API_URL}")

    # Construct full URL for logging
    param_str = "&".join([f"{k}={v}" for k, v in params.items()])
    full_url = f"{IMAGE_SERVER_API_URL}?{param_str}"
    logger.info(f"  Full URL: {full_url}")

    try:
        response = requests.get(IMAGE_SERVER_API_URL, params=params, timeout=30)
        response.raise_for_status()

        data = response.json()
        logger.info(f"API Response: {json.dumps(data, indent=2)}")

        # Parse response - API returns a list with a single object:
        # [{"build_ready": true/false, "message": "...", "build": "3620", "version": "7"}]
        if isinstance(data, list) and len(data) > 0:
            build_info = data[0]
            is_ready = build_info.get("build_ready", False)
            message = build_info.get("message", "")

            if is_ready:
                logger.info(f"✅ Build {build} ({build_keyword}) is READY: {message}")
            else:
                logger.warning(f"⏳ Build {build} ({build_keyword}) is NOT ready: {message}")

            return is_ready, build_info
        # Fallback: check if it's a dict with "0" key (old format)
        elif isinstance(data, dict) and "0" in data:
            build_info = data["0"]
            is_ready = build_info.get("build_ready", False)
            message = build_info.get("message", "")

            if is_ready:
                logger.info(f"✅ Build {build} ({build_keyword}) is READY: {message}")
            else:
                logger.warning(f"⏳ Build {build} ({build_keyword}) is NOT ready: {message}")

            return is_ready, build_info
        else:
            logger.error(f"Unexpected API response format: {data}")
            return False, {"error": "Unexpected response format", "raw_response": data}

    except requests.exceptions.RequestException as e:
        logger.error(f"API request failed: {e}")
        return False, {"error": str(e)}
    except json.JSONDecodeError as e:
        logger.error(f"Failed to parse API response as JSON: {e}")
        return False, {"error": "Invalid JSON response"}
    except Exception as e:
        logger.error(f"Unexpected error: {e}")
        return False, {"error": str(e)}


def main():
    parser = argparse.ArgumentParser(
        description="Check if a FortiOS build is ready on the Image Server",
        epilog="""
Examples:
  # Check if KVM build is ready
  python3 check_build_ready.py -r 7.6.0 -b 3620 --build-keyword KVM

  # Get JSON output for pipeline
  python3 check_build_ready.py -r 7.6.0 -b 3620 --build-keyword KVM --json-output

Note: This script only checks build readiness. Rsync should be triggered
separately from the Jenkins pipeline using the host's sudo capabilities.
        """,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument("-r", "--release", required=True, help="Release version (e.g., 7.6.0)")
    parser.add_argument("-b", "--build", required=True, help="Build number (e.g., 3620)")
    parser.add_argument("--build-keyword", required=True, help="Build keyword (e.g., KVM, DOCKER, etc.)")
    parser.add_argument("--project", default="FortiOS", help="Project name (default: FortiOS)")
    parser.add_argument("--json-output", action="store_true", help="Output result as JSON for pipeline consumption")

    args = parser.parse_args()

    # Parse release to get major version (e.g., "7.6.0" -> "7")
    try:
        version_parts = args.release.split(".")
        major_version = version_parts[0]
    except Exception as e:
        logger.error(f"Failed to parse release version '{args.release}': {e}")
        sys.exit(2)

    # Check build readiness
    is_ready, build_info = check_build_ready(project=args.project, version=major_version, build=args.build, build_keyword=args.build_keyword)

    # Prepare output
    result = {
        "build_ready": is_ready,
        "build_info": build_info,
        "parameters": {"project": args.project, "version": major_version, "release": args.release, "build": args.build, "build_keyword": args.build_keyword},
    }

    if args.json_output:
        print(json.dumps(result, indent=2))

    # Exit with appropriate code
    if is_ready:
        sys.exit(0)  # Build is ready
    else:
        sys.exit(1)  # Build is not ready


if __name__ == "__main__":
    main()
