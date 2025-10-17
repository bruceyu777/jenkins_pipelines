#!/usr/bin/env python3
"""
Migrate features from feature_list.py to PostgreSQL database.

Usage:
    python3 migrate_features_to_db.py
"""

import logging
import sys

import requests
from feature_list import FEATURE_LIST

# Configure logging
logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(levelname)s: %(message)s")

# API configuration
API_BASE_URL = "http://localhost:8000/api/features"


def convert_feature_to_api_format(feature_entry: dict) -> dict:
    """Convert feature_list.py format to API schema format."""
    return {
        "feature_name": feature_entry["FEATURE_NAME"],
        "test_case_folder": feature_entry.get("test_case_folder", []),
        "test_config": feature_entry.get("test_config", []),
        "test_groups": feature_entry.get("test_groups", []),
        "docker_compose": feature_entry.get("docker_compose", []),
        "email": feature_entry.get("email", []),
        "provision_vmpc": feature_entry.get("PROVISION_VMPC", False),
        "vmpc_names": feature_entry.get("VMPC_NAMES", ""),
        "provision_docker": feature_entry.get("PROVISION_DOCKER", True),
        "oriole_submit_flag": feature_entry.get("ORIOLE_SUBMIT_FLAG", "all"),
        "enabled": True,
        "extra_data": {},  # ← CHANGED from 'metadata'
    }


def migrate_features():
    """Migrate all features from feature_list.py to database."""
    logging.info(f"Starting migration of {len(FEATURE_LIST)} features...")

    success_count = 0
    error_count = 0
    skipped_count = 0

    for feature_entry in FEATURE_LIST:
        feature_name = feature_entry["FEATURE_NAME"]

        try:
            # Check if feature already exists
            check_response = requests.get(f"{API_BASE_URL}/by-name/{feature_name}", timeout=10)

            if check_response.status_code == 200:
                logging.warning(f"Feature '{feature_name}' already exists, skipping...")
                skipped_count += 1
                continue

            # Convert and create feature
            api_data = convert_feature_to_api_format(feature_entry)

            response = requests.post(API_BASE_URL, json=api_data, timeout=10)

            if response.status_code == 201:
                logging.info(f"✓ Created feature: {feature_name}")
                success_count += 1
            else:
                logging.error(f"✗ Failed to create feature '{feature_name}': " f"{response.status_code} - {response.text}")
                error_count += 1

        except requests.exceptions.ConnectionError:
            logging.error(f"✗ Cannot connect to API at {API_BASE_URL}. Is the API server running?")
            sys.exit(1)
        except Exception as e:
            logging.error(f"✗ Error migrating feature '{feature_name}': {e}")
            error_count += 1

    # Print summary
    logging.info("=" * 60)
    logging.info("MIGRATION SUMMARY")
    logging.info("=" * 60)
    logging.info(f"Total features: {len(FEATURE_LIST)}")
    logging.info(f"Successfully migrated: {success_count}")
    logging.info(f"Skipped (already exist): {skipped_count}")
    logging.info(f"Errors: {error_count}")
    logging.info("=" * 60)

    if error_count > 0:
        logging.warning("Some features failed to migrate. Please review the errors above.")
        sys.exit(1)

    logging.info("✅ Migration completed successfully!")


if __name__ == "__main__":
    migrate_features()
