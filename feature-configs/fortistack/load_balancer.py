#!/usr/bin/env python3
"""
Load balancer script for Jenkins test-feature dispatch.

This script:
  - Reads a master feature list from either Python (.py) or JSON (.json) file.
    The Python file must define a top-level `FEATURE_LIST` or `feature_list` variable.
  - Merges entries sharing the same FEATURE_NAME into a dict-based `features.json`.
  - Optionally filters features by inclusion/exclusion criteria.
  - Optionally filters test groups by type (all/crit/full/tmp).
  - Queries MongoDB for latest test durations or falls back to JSON file if specified.
  - Optionally queries Jenkins for idle agent nodes, or uses a provided node list.
  - Excludes reserved nodes and additional exclude nodes from the node selection.
  - Estimates total runtime per feature-entry (defaulting missing groups to 1 hr).
  - Allocates each entry proportionally across nodes.
  - Splits each entry's test-groups across its allocated nodes using greedy bin-packing.
  - Emits a dispatch JSON suitable for downstream pipeline triggers.

Usage:
  ./load_balancer.py [-a] [-e webfilter,antivirus] [-f dlp,waf] \
      -l feature_list.py \
      -n node1,node2,... [-x node2,node3] -o dispatch.json -g full
"""

import argparse
import importlib.util
import json
import logging
import os
import re
import sys
from logging.handlers import RotatingFileHandler
from math import floor
from pathlib import Path
from pprint import pformat
from typing import Any, Dict, List, Optional, Set, Tuple
from urllib.parse import urljoin, urlparse

import requests
from pymongo import MongoClient

log_level_map = {
    "notset": logging.NOTSET,
    "debug": logging.DEBUG,
    "info": logging.INFO,
    "warning": logging.WARNING,
    "error": logging.ERROR,
    "critical": logging.CRITICAL,
    "fatal": logging.FATAL,
    "warn": logging.WARNING,
}


def setup_logging(log_file=None, log_level="info", max_bytes=5 * 1024 * 1024, backup_count=5):
    """Initialize logging with RotatingFileHandler."""
    numeric_level = getattr(logging, log_level.upper(), None)
    if not isinstance(numeric_level, int):
        raise ValueError(f"Invalid log level: {log_level}")

    logger = logging.getLogger()
    logger.setLevel(numeric_level)

    formatter = logging.Formatter("[%(asctime)s] %(levelname)s [%(module)s:%(lineno)d]: %(message)s")

    # Stream Handler for console output
    stream_handler = logging.StreamHandler()
    stream_handler.setLevel(numeric_level)
    stream_handler.setFormatter(formatter)
    logger.addHandler(stream_handler)

    if log_file:
        log_file = Path(log_file)
        log_file.parent.mkdir(parents=True, exist_ok=True)

        # Rotating File Handler for log file
        file_handler = RotatingFileHandler(log_file, maxBytes=max_bytes, backupCount=backup_count)
        file_handler.setLevel(numeric_level)
        file_handler.setFormatter(formatter)
        logger.addHandler(file_handler)
    return logger


logger = setup_logging("load_balancer.log")

# =============================================================================
# CONSTANTS
# =============================================================================

ADMIN_EMAILS: Set[str] = {"yzhengfeng@fortinet.com", "wangd@fortinet.com", "rainxiao@fortinet.com"}

DEFAULT_JENKINS_URL: str = "http://10.96.227.206:8080"
DEFAULT_JENKINS_USER: str = "fosqa"
DEFAULT_JENKINS_TOKEN: str = "110dec5c2d2974a67968074deafccc1414"
DEFAULT_RESERVED_NODES: str = ",".join(
    [
        "Built-In Node",
        # "node1",  # pipeline try
        # "node5",  # Zhu Yu
        # "node11",  # volod
        # "node12",  # Leo Luo
        # "node13",  # tao wang
        # "node14",  # andrew huang
        # "node15",  # qi wang
        # "node19",  # Zach
        # "node20",  # Zach
        # "node26",  # Maryam
        # "node27",  # Maryam
        # # "node28", # Maryam, binding with foc
        # "node33",  # Eric son
        # "node34",  # Yang Shang
        # "node35",  # Yang Shang
        # "node39",  # Jiaran
        # "node40",  # Jiaran
        # "node43",  # Qi for ZTNA
        # "node49",  # Dawei for ddos
        "node48",  # Rain for debug
        # "node47",  # Hayder for spam
        # "node46",  # Dawei for ddos
    ]
)

# MongoDB connection settings
DEFAULT_MONGO_URI: str = "mongodb://10.96.227.206:27017"
DEFAULT_MONGO_DB: str = "autolib"
DEFAULT_MONGO_COLLECTION: str = "results"

# Feature names mapped to dedicated Jenkins nodes
FEATURE_NODE_STATIC_BINDING: Dict[str, str] = {
    "avfortisandbox": "node2",  # binding avfortisandbox to node2 https://app.clickup.com/t/86dxg5eem
    "ztna": "node15",  # binding ztna to node15, vmpc1 forticlient has to be fixed, uuid is fixed
    "foc": "node28",  # binding foc to node28
    "waf": "node40",
    "avfortindr": "node99",  # binding avfortindr to node99
}

# Features to exclude from processing (can be overridden via CLI)
EXCLUDE_FEATURES: List[str] = []

# ORIOLE test result submission strategy by feature
# Options: 'all' (default), 'succeeded', 'none'
ORIOLE_SUBMIT_STRATEGY: Dict[str, str] = {
    # "dlp": "succeeded",
}


# =============================================================================
# MONGODB CLIENT
# =============================================================================


class MongoDBClient:
    """
    Simple MongoDB wrapper for querying test results.
    """

    def __init__(self, uri: str, database: str, collection: str) -> None:
        self._client = MongoClient(uri)
        self._collection = self._client[database][collection]

    def find(self, query: Dict[str, Any]) -> List[Dict[str, Any]]:
        return list(self._collection.find(query))

    def find_latest_durations(self, release: Optional[str] = None) -> Tuple[Dict[str, Dict[str, str]], Dict[str, Dict[str, Any]]]:
        """
        Fetch the latest test durations from MongoDB for all features and groups.

        Args:
            release: Optional release filter (e.g., "7.6.4")

        Returns:
            Tuple of:
            - Dictionary mapping feature names to group duration mappings
              Format: {feature_name: {group_name: "2 hr 5 min"}}
            - Dictionary mapping (feature, group) to metadata
              Format: {(feature, group): {"release": "7.6.4", "build": 12345, "timestamp": "2025-10-28"}}
        """
        # Build query
        query = {}
        if release:
            query["release"] = release

        logging.info(f"MongoDB Query: {query}")

        # Generate equivalent shell command for debugging
        if query:
            # MongoDB shell command with query
            shell_cmd = f"mongo 10.96.227.206:27017/autolib --eval 'db.results.find({json.dumps(query)}).sort({{\"build\": -1}}).limit(10).pretty()'"
        else:
            # MongoDB shell command without query (all documents)
            shell_cmd = f"mongo 10.96.227.206:27017/autolib --eval 'db.results.find().sort({{\"build\": -1}}).limit(10).pretty()'"

        logging.info(f"Equivalent shell command to test manually:")
        logging.info(f"  {shell_cmd}")

        # Alternative mongosh command (newer MongoDB shell)
        if query:
            mongosh_cmd = f'mongosh "10.96.227.206:27017/autolib" --eval \'db.results.find({json.dumps(query)}).sort({{"build": -1}}).limit(10)\''
        else:
            mongosh_cmd = f'mongosh "10.96.227.206:27017/autolib" --eval \'db.results.find().sort({{"build": -1}}).limit(10)\''

        logging.info(f"Alternative mongosh command:")
        logging.info(f"  {mongosh_cmd}")

        # Get collection stats first
        try:
            stats = self._collection.database.command("collStats", self._collection.name)
            doc_count = stats.get("count", 0)
            logging.info(f"Collection '{self._collection.name}' contains {doc_count} total documents")
        except Exception as e:
            logging.warning(f"Could not get collection stats: {e}")

        # Check if collection exists
        collection_names = self._collection.database.list_collection_names()
        logging.info(f"Available collections in database '{self._collection.database.name}': {collection_names}")

        if self._collection.name not in collection_names:
            logging.error(f"Collection '{self._collection.name}' does not exist!")
            return {}, {}

        # Get multiple sample documents to understand the schema evolution
        try:
            # Check for documents with duration_human field specifically
            duration_docs = list(self._collection.find({"duration_human": {"$exists": True}}).sort("build", -1).limit(5))
            logging.info(f"=== DOCUMENTS WITH 'duration_human' FIELD ===")
            logging.info(f"Found {len(duration_docs)} documents with 'duration_human' field")

            if duration_docs:
                for i, doc in enumerate(duration_docs):
                    logging.info(f"Duration Doc {i+1}:")
                    logging.info(f"  duration_human: '{doc.get('duration_human', '')}'")
                    logging.info(f"  Build: {doc.get('build', 'N/A')}, Feature: {doc.get('feature', 'N/A')}, Group: {doc.get('feature_group', 'N/A')}")

            # Count documents with and without duration_human field
            with_duration = self._collection.count_documents({"duration_human": {"$exists": True}})
            without_duration = self._collection.count_documents({"duration_human": {"$exists": False}})
            logging.info(f"Documents WITH 'duration_human': {with_duration}")
            logging.info(f"Documents WITHOUT 'duration_human': {without_duration}")

        except Exception as e:
            logging.error(f"Error getting sample documents: {e}")
            return {}, {}

        # Modify query to only get documents with duration_human field
        duration_query = dict(query)  # Copy the original query
        duration_query["duration_human"] = {"$exists": True, "$ne": ""}

        logging.info(f"Modified query to include duration_human filter: {duration_query}")

        # Generate shell command for the modified query
        shell_cmd_duration = f"mongo 10.96.227.206:27017/autolib --eval 'db.results.find({json.dumps(duration_query)}).sort({{\"build\": -1}}).limit(10).pretty()'"
        logging.info(f"Shell command for documents with duration_human:")
        logging.info(f"  {shell_cmd_duration}")

        # Get all documents with duration_human field, sorted by build number (latest first)
        try:
            logging.info("Executing MongoDB query for documents with duration_human...")
            cursor = self._collection.find(duration_query).sort("build", -1)
            docs = list(cursor)
            logging.info(f"Query with duration_human filter returned {len(docs)} documents")
        except Exception as e:
            logging.error(f"Error executing MongoDB query with duration_human filter: {e}")
            return {}, {}

        if not docs:
            logging.warning(f"No duration data found in MongoDB for query: {duration_query}")
            return {}, {}

        # Group by feature and feature_group, keeping only the latest entry
        feature_durations = {}
        duration_metadata = {}  # NEW: Store metadata for each (feature, group) pair
        seen_combinations = set()

        logging.info("Processing documents to extract durations...")
        processed_count = 0

        for doc in docs:
            feature = doc.get("feature", "")
            feature_group = doc.get("feature_group", "")
            duration_human = doc.get("duration_human", "")
            build = doc.get("build", "")
            release_ver = doc.get("release", "")
            timestamp = doc.get("timestamp", "") or doc.get("created_at", "") or doc.get("date", "")

            # Debug: log first few documents
            if processed_count < 5:
                logging.info(f"Processing Doc {processed_count + 1}:")
                logging.info(f"  feature='{feature}', feature_group='{feature_group}'")
                logging.info(f"  duration_human='{duration_human}', build='{build}'")
                logging.info(f"  release='{release_ver}', timestamp='{timestamp}'")

            if not feature or not feature_group or not duration_human:
                if processed_count < 10:
                    logging.warning(f"Skipping document with missing fields: feature='{feature}', feature_group='{feature_group}', duration_human='{duration_human}'")
                continue

            # Create unique key for this feature+group combination
            combination_key = (feature, feature_group)

            # Skip if we've already seen this combination (keeping the latest)
            if combination_key in seen_combinations:
                continue

            seen_combinations.add(combination_key)

            # Initialize feature dict if needed
            if feature not in feature_durations:
                feature_durations[feature] = {}

            # Store the duration
            feature_durations[feature][feature_group] = duration_human

            # Store metadata
            duration_metadata[combination_key] = {"release": release_ver, "build": build, "timestamp": timestamp}

            processed_count += 1

        logging.info(f"Processed {processed_count} documents with valid duration data")
        logging.info(f"Loaded durations for {len(feature_durations)} features from MongoDB")

        # Log summary of what was found
        for feature_name, group_durations in feature_durations.items():
            logging.info(f"  {feature_name}: {len(group_durations)} groups")
            # Show a few examples
            sample_groups = list(group_durations.items())[:3]
            for group, duration in sample_groups:
                logging.info(f"    {group}: {duration}")
            if len(group_durations) > 3:
                logging.info(f"    ... and {len(group_durations) - 3} more groups")

        return feature_durations, duration_metadata


# =============================================================================
# FEATURE LIST LOADING AND MERGING
# =============================================================================


def load_feature_list(path: str) -> List[Dict[str, Any]]:
    """
    Load feature list from JSON (.json) or Python (.py) file.

    Args:
        path: Path to the feature list file (.py or .json)

    Returns:
        List of dict entries each containing 'FEATURE_NAME'

    Raises:
        ValueError: If the file format is invalid or required variables are missing
    """
    _, file_ext = os.path.splitext(path)
    feature_entries = []

    if file_ext == ".py":
        # Load Python module and normalize JSON-style booleans
        raw_text = open(path).read()
        normalized_text = re.sub(r"\btrue\b", "True", raw_text, flags=re.IGNORECASE)
        normalized_text = re.sub(r"\bfalse\b", "False", normalized_text, flags=re.IGNORECASE)

        # Execute as a module
        module_spec = importlib.util.spec_from_loader("feature_list", loader=None)
        feature_module = importlib.util.module_from_spec(module_spec)
        exec(normalized_text, feature_module.__dict__)

        # Extract feature list
        if hasattr(feature_module, "FEATURE_LIST"):
            feature_entries = getattr(feature_module, "FEATURE_LIST")
        elif hasattr(feature_module, "feature_list"):
            feature_entries = getattr(feature_module, "feature_list")
        else:
            raise ValueError(f"Python feature-list must define FEATURE_LIST or feature_list: {path}")

    else:
        # Assume JSON format
        raw_data = json.load(open(path))

        if isinstance(raw_data, dict):
            # Convert {feature_name: config} format to list format
            for feature_name, config in raw_data.items():
                entry = {"FEATURE_NAME": feature_name}
                entry.update(config)
                feature_entries.append(entry)

        elif isinstance(raw_data, list):
            for item in raw_data:
                if not isinstance(item, dict):
                    raise ValueError(f"Invalid entry in {path}: {item}")

                if "FEATURE_NAME" in item:
                    feature_entries.append(dict(item))
                elif len(item) == 1:
                    feature_name, config = next(iter(item.items()))
                    if not isinstance(config, dict):
                        raise ValueError(f"Config for {feature_name} not dict: {config}")
                    entry = {"FEATURE_NAME": feature_name}
                    entry.update(config)
                    feature_entries.append(entry)
                else:
                    raise ValueError(f"Cannot decode entry: {item}")
        else:
            raise ValueError(f"Feature list {path} must be .py or JSON dict/list")

    logging.info(f"Loaded {len(feature_entries)} entries from {path}")
    logger.info(f"Feature entries: {pformat(feature_entries, sort_dicts=False)}")
    return feature_entries


def merge_features(entries: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    """
    Merge entries with the same FEATURE_NAME into a single configuration.
    Normalize API snake_case keys to expected UPPERCASE keys for features.json.
    Shape email as a one-element list with a single comma-separated string.
    """
    merged_features: Dict[str, Dict[str, Any]] = {}

    # Map API snake_case to expected UPPERCASE keys in features.json
    key_map = {
        "provision_vmpc": "PROVISION_VMPC",
        "vmpc_names": "VMPC_NAMES",
        "provision_docker": "PROVISION_DOCKER",
        "oriole_submit_flag": "ORIOLE_SUBMIT_FLAG",
        # ignore: enabled, extra_data, id, created_at, updated_at
    }

    list_fields = ["test_case_folder", "test_config", "test_groups", "docker_compose"]
    scalar_fields_upper = ["PROVISION_VMPC", "PROVISION_DOCKER", "VMPC_NAMES", "ORIOLE_SUBMIT_FLAG"]

    def normalize_entry(raw: Dict[str, Any]) -> Tuple[str, Dict[str, Any]]:
        """Normalize a raw entry from file or API to a consistent dict."""
        entry = dict(raw)

        # Ensure FEATURE_NAME is present
        if "FEATURE_NAME" not in entry or not entry.get("FEATURE_NAME"):
            for k in ("feature_name", "feature", "name"):
                if k in entry and entry[k]:
                    entry["FEATURE_NAME"] = entry[k]
                    break
        feature_name = entry.get("FEATURE_NAME")
        if not feature_name:
            return "", {}

        # Start normalized config
        cfg: Dict[str, Any] = {}

        # Copy list fields as-is if present
        for f in list_fields:
            v = entry.get(f)
            if v is not None:
                # Ensure list
                if isinstance(v, list):
                    cfg[f] = v
                else:
                    cfg[f] = [v]

        # Normalize email into a set of addresses (flatten strings/lists)
        emails_set: Set[str] = set()
        raw_email = entry.get("email")
        if raw_email:
            if isinstance(raw_email, list):
                for item in raw_email:
                    if isinstance(item, str):
                        for addr in item.split(","):
                            addr = addr.strip()
                            if addr:
                                emails_set.add(addr)
            elif isinstance(raw_email, str):
                for addr in raw_email.split(","):
                    addr = addr.strip()
                    if addr:
                        emails_set.add(addr)

        # Add admin emails
        if emails_set:
            emails_set |= ADMIN_EMAILS

        # Store as one-element list with comma-separated string (if any)
        if emails_set:
            cfg["email"] = [",".join(sorted(emails_set))]

        # Map API snake_case to UPPERCASE scalar keys
        for src, dst in key_map.items():
            if src in entry and entry[src] is not None:
                cfg[dst] = entry[src]

        # Accept already UPPERCASE scalar fields
        for sf in scalar_fields_upper:
            if sf in entry and entry[sf] is not None:
                cfg[sf] = entry[sf]

        # Ensure default ORIOLE_SUBMIT_FLAG if missing
        if "ORIOLE_SUBMIT_FLAG" not in cfg:
            cfg["ORIOLE_SUBMIT_FLAG"] = "all"

        return feature_name, cfg

    for raw in entries:
        feature_name, config = normalize_entry(raw)
        if not feature_name:
            continue

        if feature_name not in merged_features:
            merged_features[feature_name] = dict(config)
            continue

        base = merged_features[feature_name]

        # Merge list fields with deduplication (preserve order)
        for f in list_fields:
            if f in config:
                base_list = base.get(f, [])
                if not isinstance(base_list, list):
                    base_list = [base_list]
                new_list = config.get(f, [])
                if not isinstance(new_list, list):
                    new_list = [new_list]
                combined = base_list + new_list
                deduped: List[Any] = []
                seen: Set[Any] = set()
                for val in combined:
                    key = json.dumps(val, sort_keys=True) if isinstance(val, (dict, list)) else val
                    if key not in seen:
                        seen.add(key)
                        deduped.append(val)
                base[f] = deduped

        # Merge email sets and re-shape to one-element list
        if "email" in config:
            current = base.get("email", [])
            current_set: Set[str] = set()
            if isinstance(current, list) and current:
                # FIX: Process ALL elements in the list, not just current[0]
                for item in current:
                    if isinstance(item, str):
                        for addr in item.split(","):
                            addr = addr.strip()
                            if addr:
                                current_set.add(addr)

            new_set: Set[str] = set()
            for item in config.get("email", []):
                if isinstance(item, str):
                    for addr in item.split(","):
                        addr = addr.strip()
                        if addr:
                            new_set.add(addr)

            all_emails = (current_set | new_set | ADMIN_EMAILS) if (current_set or new_set) else set()
            if all_emails:
                base["email"] = [",".join(sorted(all_emails))]

        # Overwrite scalar fields with latest occurrence
        for sf in scalar_fields_upper:
            if sf in config:
                base[sf] = config[sf]

    # Also ensure default ORIOLE_SUBMIT_FLAG after merge
    for _, base in merged_features.items():
        if "ORIOLE_SUBMIT_FLAG" not in base:
            base["ORIOLE_SUBMIT_FLAG"] = "all"

    return merged_features


def write_features_dict(merged_features: Dict[str, Dict[str, Any]], output_path: str = "features.json") -> None:
    """Write merged features to a JSON file."""
    with open(output_path, "w") as f:
        json.dump(merged_features, f, indent=2)
    logging.info(f"Wrote {len(merged_features)} merged features to {output_path}")


def write_features_dict_to_all_in_one_tools(merged_features: Dict[str, Dict[str, Any]], output_path: str = "/home/fosqa/resources/tools/features.json") -> None:
    """Write merged features to the all-in-one tools location."""
    with open(output_path, "w") as f:
        json.dump(merged_features, f, indent=2)
    logging.info(f"Wrote {len(merged_features)} merged features to {output_path}")


# =============================================================================
# DURATION PARSING AND PROCESSING
# =============================================================================


def load_duration_entries_from_file(path: str) -> List[Tuple[str, Dict[str, str]]]:
    """
    Load test duration data from JSON file (fallback method).

    Args:
        path: Path to the duration JSON file

    Returns:
        List of (feature_name, duration_map) pairs where duration_map
        maps test group names to duration strings (e.g., "2 hr 30 min")
    """
    raw_data = json.load(open(path))
    duration_entries = []

    if isinstance(raw_data, dict):
        for feature_name, duration_map in raw_data.items():
            if isinstance(duration_map, dict):
                duration_entries.append((feature_name, duration_map))
            else:
                logging.warning(f"Skipping durations for {feature_name}: not a dictionary")

    elif isinstance(raw_data, list):
        for item in raw_data:
            if not isinstance(item, dict):
                raise ValueError(f"Invalid duration entry: {item}")

            if len(item) == 1 and "durations" not in item:
                feature_name, duration_map = next(iter(item.items()))
                if isinstance(duration_map, dict):
                    duration_entries.append((feature_name, duration_map))
                else:
                    logging.warning(f"Skipping durations for {feature_name}: not a dictionary")

            elif "durations" in item:
                feature_name = item.get("feature") or item.get("FEATURE_NAME")
                duration_map = item["durations"]
                if isinstance(duration_map, dict):
                    duration_entries.append((feature_name, duration_map))
                else:
                    logging.warning(f"Skipping durations for {feature_name}: not a dictionary")
            else:
                logging.warning(f"Skipping unrecognized duration entry: {item}")
    else:
        raise ValueError(f"Durations {path} must be dict or list")

    logging.info(f"Loaded {len(duration_entries)} duration entries from {path}")
    return duration_entries


def log_duration_summary_table(feature_durations: Dict[str, Dict[str, str]], duration_metadata: Dict[Tuple[str, str], Dict[str, Any]] = None) -> None:
    """
    Log a summary table of all test group durations sorted by duration (descending).

    Args:
        feature_durations: Dictionary mapping feature names to group duration mappings
        duration_metadata: Optional dictionary mapping (feature, group) to metadata (release, build, timestamp)
    """
    if not feature_durations:
        logging.info("No duration data to display")
        return

    # Flatten all groups with their durations and metadata
    all_groups = []

    for feature_name, group_durations in feature_durations.items():
        for group_name, duration_str in group_durations.items():
            seconds = parse_duration_to_seconds(duration_str)

            # Get metadata if available
            metadata = {}
            if duration_metadata:
                metadata = duration_metadata.get((feature_name, group_name), {})

            all_groups.append(
                {
                    "feature": feature_name,
                    "group": group_name,
                    "duration_str": duration_str,
                    "seconds": seconds,
                    "release": metadata.get("release", "N/A"),
                    "build": metadata.get("build", "N/A"),
                    "timestamp": metadata.get("timestamp", "N/A"),
                }
            )

    # Sort by duration (descending)
    all_groups.sort(key=lambda x: x["seconds"], reverse=True)

    # Calculate column widths
    max_feature_len = max(len(g["feature"]) for g in all_groups) if all_groups else 10
    max_feature_len = max(max_feature_len, len("FEATURE"))

    max_group_len = max(len(g["group"]) for g in all_groups) if all_groups else 10
    max_group_len = max(max_group_len, len("GROUP"))

    max_release_len = max(len(str(g["release"])) for g in all_groups) if all_groups else 8
    max_release_len = max(max_release_len, len("RELEASE"))

    max_build_len = max(len(str(g["build"])) for g in all_groups) if all_groups else 6
    max_build_len = max(max_build_len, len("BUILD"))

    # Build the table as a list of lines
    table_lines = []
    table_lines.append("")
    table_lines.append("=" * 150)
    table_lines.append("DURATION SUMMARY TABLE - ALL GROUPS (Sorted by Duration - Descending)")
    table_lines.append("=" * 150)

    header = (
        f"{'FEATURE':<{max_feature_len}}  "
        f"{'GROUP':<{max_group_len}}  "
        f"{'DURATION':<20}  "
        f"{'SECONDS':>10}  "
        f"{'RELEASE':<{max_release_len}}  "
        f"{'BUILD':<{max_build_len}}  "
        f"{'TIMESTAMP':<20}"
    )
    table_lines.append(header)
    table_lines.append("-" * 150)

    # Add each group row
    for item in all_groups:
        # Format timestamp (truncate if too long)
        timestamp_str = str(item["timestamp"])[:20] if item["timestamp"] != "N/A" else "N/A"

        row = (
            f"{item['feature']:<{max_feature_len}}  "
            f"{item['group']:<{max_group_len}}  "
            f"{item['duration_str']:<20}  "
            f"{item['seconds']:>10}  "
            f"{str(item['release']):<{max_release_len}}  "
            f"{str(item['build']):<{max_build_len}}  "
            f"{timestamp_str:<20}"
        )
        table_lines.append(row)

    # Add summary footer
    total_groups = len(all_groups)
    total_features = len(feature_durations)
    total_time = sum(g["seconds"] for g in all_groups)

    table_lines.append("-" * 150)
    table_lines.append(
        f"{'TOTAL':<{max_feature_len}}  " f"{total_groups} groups" f"{'':>{max_group_len - 9}}  " f"{format_seconds_to_duration(total_time):<20}  " f"{total_time:>10}"
    )
    table_lines.append("=" * 150)
    table_lines.append(f"Features: {total_features} | " f"Groups: {total_groups} | " f"Total Time: {format_seconds_to_duration(total_time)}")
    table_lines.append("=" * 150)
    table_lines.append("")

    # Log the entire table as a single multi-line message
    logging.info("\n" + "\n".join(table_lines))


def get_duration_entries(mongo_client: Optional[MongoDBClient] = None, release: Optional[str] = None, fallback_file: Optional[str] = None) -> List[Tuple[str, Dict[str, str]]]:
    """
    Get duration entries from MongoDB or fallback to JSON file.

    Args:
        mongo_client: MongoDB client instance
        release: Release version to filter by
        fallback_file: Path to JSON file as fallback

    Returns:
        List of (feature_name, duration_map) pairs
    """
    duration_entries = []
    duration_metadata = {}

    # Try MongoDB first
    if mongo_client:
        try:
            logging.info("Fetching test durations from MongoDB...")
            feature_durations, duration_metadata = mongo_client.find_latest_durations(release)

            if feature_durations:
                duration_entries = list(feature_durations.items())
                logging.info(f"Successfully loaded {len(duration_entries)} duration entries from MongoDB")

                # Log detailed breakdown for each feature
                total_groups = sum(len(groups) for groups in feature_durations.values())
                logging.info(f"Total groups across all features: {total_groups}")
                logging.info("")

                for feature_name, group_durations in feature_durations.items():
                    logging.info(f"  {feature_name}: {len(group_durations)} groups")
                    # Show a few examples with metadata
                    sample_groups = list(group_durations.items())[:3]
                    for group, duration in sample_groups:
                        metadata = duration_metadata.get((feature_name, group), {})
                        logging.info(f"    {group}: {duration} (release: {metadata.get('release', 'N/A')}, build: {metadata.get('build', 'N/A')})")
                    if len(group_durations) > 3:
                        logging.info(f"    ... and {len(group_durations) - 3} more groups")

                # Display duration summary table at GROUP level with metadata
                log_duration_summary_table(feature_durations, duration_metadata)

                return duration_entries
            else:
                logging.warning("No duration data found in MongoDB")

        except Exception as e:
            logging.error(f"Failed to fetch durations from MongoDB: {e}")

    # Fallback to JSON file
    if fallback_file and os.path.exists(fallback_file):
        logging.info(f"Falling back to duration file: {fallback_file}")
        try:
            duration_entries = load_duration_entries_from_file(fallback_file)

            if duration_entries:
                # Convert to dict format for table display (no metadata from file)
                feature_durations_dict = dict(duration_entries)
                log_duration_summary_table(feature_durations_dict, None)

            return duration_entries
        except Exception as e:
            logging.error(f"Failed to load duration file {fallback_file}: {e}")

    # If both methods fail, return empty list
    logging.warning("No duration data available - will use default 1hr per group")
    return []


def parse_duration_to_seconds(duration_text: str) -> int:
    """
    Convert a duration string like '3 hr 45 min 20 sec' to total seconds.

    Args:
        duration_text: Human-readable duration string

    Returns:
        Total duration in seconds
    """
    hours = minutes = seconds = 0

    if hour_match := re.search(r"(\d+)\s*hr", duration_text):
        hours = int(hour_match.group(1))

    if minute_match := re.search(r"(\d+)\s*min", duration_text):
        minutes = int(minute_match.group(1))

    if second_match := re.search(r"(\d+)\s*sec", duration_text):
        seconds = int(second_match.group(1))

    return hours * 3600 + minutes * 60 + seconds


def format_seconds_to_duration(seconds: int) -> str:
    """
    Convert seconds to a human-readable duration string.

    Args:
        seconds: Duration in seconds

    Returns:
        Human-readable duration string (e.g., "2 hr 30 min 45 sec")
    """
    parts = []
    hours, remainder = divmod(seconds, 3600)
    minutes, seconds = divmod(remainder, 60)

    if hours:
        parts.append(f"{hours} hr")
    if minutes:
        parts.append(f"{minutes} min")
    if seconds:
        parts.append(f"{seconds} sec")

    return " ".join(parts) or "0 sec"


# =============================================================================
# NODE ALLOCATION AND LOAD BALANCING
# =============================================================================


def allocate_counts_to_nodes(durations: List[int], node_count: int, feature_entries: List[Dict]) -> List[int]:
    """
    Allocate each feature to a proportional number of nodes based on its duration.
    Features with longer total duration get more nodes to balance the overall load.

    Args:
        durations: List of feature durations in seconds
        node_count: Total number of available nodes
        feature_entries: List of feature entries (used for constraining by test group count)

    Returns:
        List of node counts for each feature (minimum 1 per feature)
    """
    if not durations or node_count <= 0:
        return [1] * len(durations)

    total_duration = sum(durations)
    if total_duration == 0:
        return [1] * len(durations)

    # Calculate proportional allocation
    raw_allocation = [duration / total_duration * node_count for duration in durations]
    fractional_parts = [raw - floor(raw) for raw in raw_allocation]
    node_counts = [max(1, floor(raw)) for raw in raw_allocation]

    sum_allocated = sum(node_counts)

    # Handle over-allocation by reducing nodes from features with highest allocations
    if sum_allocated > node_count:
        excess = sum_allocated - node_count
        # Sort features with more than 1 node by increasing raw allocation
        candidates = sorted([i for i, count in enumerate(node_counts) if count > 1], key=lambda i: raw_allocation[i])

        # Take nodes from candidates until we're at our target
        index = 0
        while excess > 0 and candidates:
            feature_index = candidates[index % len(candidates)]
            node_counts[feature_index] -= 1
            excess -= 1

            # If we've reduced to 1 node, remove from candidates
            if node_counts[feature_index] == 1:
                candidates.remove(feature_index)
            index += 1

    # Handle under-allocation by adding nodes to features with highest fractional parts
    elif sum_allocated < node_count:
        deficit = node_count - sum_allocated
        # Sort by fractional part (descending) to allocate extras fairly
        candidates = sorted(range(len(raw_allocation)), key=lambda i: fractional_parts[i], reverse=True)

        # Add nodes to candidates until we reach our target
        index = 0
        while deficit > 0:
            feature_index = candidates[index % len(candidates)]
            node_counts[feature_index] += 1
            deficit -= 1
            index += 1

    # CONSTRAINT: Don't allocate more nodes than test groups available
    for i, entry in enumerate(feature_entries):
        max_groups = len(entry.get("test_groups", []))
        if max_groups > 0:  # Only constrain if there are actual groups
            node_counts[i] = min(node_counts[i], max_groups)

    return node_counts


def distribute_groups_across_nodes(group_duration_map: Dict[str, int], bin_count: int) -> List[Tuple[List[str], int]]:
    """
    Distribute test groups across nodes using a greedy bin-packing algorithm.
    This ensures relatively balanced load across nodes while keeping groups intact.

    Args:
        group_duration_map: Map of group names to durations in seconds
        bin_count: Number of nodes/bins to distribute across

    Returns:
        List of (groups, total_duration) tuples for each node
    """
    if not group_duration_map or bin_count <= 0:
        return []

    # Sort groups by duration (descending) for better bin packing
    sorted_groups = sorted(group_duration_map.items(), key=lambda item: item[1], reverse=True)

    # Initialize bins (nodes)
    bins = [{"total_duration": 0, "groups": []} for _ in range(bin_count)]

    # Greedy bin packing: assign each group to the bin with least current load
    for group_name, duration in sorted_groups:
        target_bin = min(bins, key=lambda bin: bin["total_duration"])
        target_bin["groups"].append(group_name)
        target_bin["total_duration"] += duration

    # Convert to return format
    return [(bin["groups"], bin["total_duration"]) for bin in bins]


# =============================================================================
# JENKINS NODE DISCOVERY
# =============================================================================


def get_idle_jenkins_nodes(url: str, user: str, token: str) -> List[str]:
    """
    Query Jenkins API for idle nodes (not running fortistack jobs).

    Args:
        url: Jenkins base URL
        user: Jenkins username
        token: Jenkins API token

    Returns:
        List of idle node names, sorted numerically

    Raises:
        SystemExit: If the Jenkins API request fails
    """
    api_url = f"{url.rstrip('/')}/computer/api/json?" "tree=computer[displayName,offline,executors[currentExecutable[fullDisplayName]]]"

    try:
        response = requests.get(api_url, auth=(user, token))
        response.raise_for_status()
        data = response.json()
    except Exception as e:
        logging.error(f"Error querying Jenkins API: {e}")
        sys.exit(1)

    available_nodes = []

    for computer in data.get("computer", []) or []:
        node_name = computer.get("displayName")
        is_offline = computer.get("offline", True)

        # Skip master node, offline nodes, and unnamed nodes
        if not node_name or node_name == "master" or is_offline:
            continue

        # Check if any executor is running a fortistack job
        node_is_busy = False
        for executor in computer.get("executors", []) or []:
            current_executable = executor.get("currentExecutable")
            if not isinstance(current_executable, dict):
                continue

            job_name = current_executable.get("fullDisplayName", "")
            if job_name.startswith("fortistack_runtest") or job_name.startswith("fortistack_provision_fgts"):
                node_is_busy = True
                break

        if not node_is_busy:
            available_nodes.append(node_name)

    # Sort nodes numerically
    available_nodes.sort(key=lambda name: int(name[4:]) if name.startswith("node") and name[4:].isdigit() else float("inf"))

    logging.info(f"Found idle Jenkins nodes <{len(available_nodes)}>: {available_nodes}")
    return available_nodes


# =============================================================================
# FEATURE AND GROUP FILTERING
# =============================================================================


def filter_test_groups_by_choice(test_groups: List[str], group_choice: str) -> List[str]:
    """
    Filter test groups based on the group choice criteria.

    Args:
        test_groups: List of test group names
        group_choice: "all", "crit", "full", or "tmp"

    Returns:
        Filtered list of test groups
    """
    if group_choice == "all":
        return test_groups

    # Filter groups by suffix
    return [group for group in test_groups if group.endswith(f".{group_choice}")]


# =============================================================================
# DISPATCH GENERATION
# =============================================================================


def create_dispatch_entry(entry: Dict[str, Any], node_name: str, groups: List[str], total_seconds: int, duration_entries: List[Tuple[str, Dict[str, str]]]) -> Dict[str, Any]:
    """
    Create a single dispatch entry for a feature on a specific node.
    Accept both UPPERCASE (features.json style) and snake_case (API style) keys.
    """
    feature_name = entry["FEATURE_NAME"]

    # Extract feature configuration (list fields are already normalized in entries)
    test_folder = entry.get("test_case_folder", [None])[0]
    test_config = entry.get("test_config", [None])[0]
    docker_compose = entry.get("docker_compose", [None])[0]
    email_recipients = entry.get("email", "")

    # Handle email recipients (can be string or list)
    if isinstance(email_recipients, list) and email_recipients:
        email_str = ",".join(email_recipients)
    else:  #
        email_str = email_recipients

    # Create email list with admin addresses
    if email_str:
        email_addresses = {addr.strip() for addr in email_str.split(",") if addr.strip()}
        all_emails = ",".join(sorted(email_addresses | ADMIN_EMAILS))
    else:
        all_emails = ",".join(sorted(ADMIN_EMAILS))

    # Submission strategy: global override > entry UPPERCASE > entry snake_case > default
    submit_flag = ORIOLE_SUBMIT_STRATEGY.get(
        feature_name,
        entry.get("ORIOLE_SUBMIT_FLAG", entry.get("oriole_submit_flag", "all")),
    )

    # Read provisioning fields: prefer UPPERCASE (from features.json), fallback to snake_case (from API)
    provision_vmpc = entry.get("PROVISION_VMPC", entry.get("provision_vmpc", False))
    vmpc_names = entry.get("VMPC_NAMES", entry.get("vmpc_names", ""))
    provision_docker = entry.get("PROVISION_DOCKER", entry.get("provision_docker", True))

    return {
        "NODE_NAME": node_name,
        "FEATURE_NAME": feature_name,
        "TEST_CASE_FOLDER": test_folder,
        "TEST_CONFIG_CHOICE": test_config,
        "TEST_GROUP_CHOICE": groups[0] if groups else "",
        "TEST_GROUPS": groups,
        "SUM_DURATION": format_seconds_to_duration(total_seconds),
        "DOCKER_COMPOSE_FILE_CHOICE": docker_compose,
        "SEND_TO": all_emails,
        "PROVISION_VMPC": provision_vmpc,
        "VMPC_NAMES": vmpc_names,
        "PROVISION_DOCKER": provision_docker,
        "ORIOLE_SUBMIT_FLAG": submit_flag,
    }


# =============================================================================
# MAIN ENTRY POINT
# =============================================================================


def _build_api_session() -> requests.Session:
    s = requests.Session()
    s.headers.update({"Accept": "application/json"})
    s.timeout = 30
    return s


def authenticate_api(api_base: str, username: str | None, password: str | None, token: str | None) -> requests.Session:
    """
    Build an authenticated session. Tries, in order:
    1) Bearer token (if provided)
    2) FastAPI OAuth2 password flow: POST {base}/token (form), expects access_token
    3) FastAPI /auth/login (json), expects access_token or token
    4) HTTP Basic (fallback), validated by probing the features endpoint (caller validates)
    """
    session = _build_api_session()
    # Bearer token
    if token:
        session.headers["Authorization"] = f"Bearer {token}"
        return session

    # Need username/password for the flows below
    if not (username and password):
        return session  # anonymous; caller will try and fallback if 401

    # Try OAuth2 password token endpoint
    try:
        token_url = urljoin(api_base.rstrip("/") + "/", "token")
        resp = session.post(
            token_url,
            data={"username": username, "password": password, "grant_type": "password"},
        )
        if resp.ok:
            data = resp.json()
            access_token = data.get("access_token") or data.get("token")
            token_type = data.get("token_type", "bearer")
            if access_token:
                session.headers["Authorization"] = f"{token_type.capitalize()} {access_token}"
                return session
    except Exception:
        pass

    # Try /auth/login (common FastAPI pattern)
    try:
        login_url = urljoin(api_base.rstrip("/") + "/", "auth/login")
        resp = session.post(login_url, json={"username": username, "password": password})
        if resp.ok:
            data = {}
            try:
                data = resp.json()
            except Exception:
                data = {}
            access_token = data.get("access_token") or data.get("token")
            token_type = data.get("token_type", "bearer")
            if access_token:
                session.headers["Authorization"] = f"{token_type.capitalize()} {access_token}"
                return session
            # If session cookie auth, just keep cookies in the session
            if resp.cookies:
                return session
    except Exception:
        pass

    # Fallback to HTTP Basic
    session.auth = (username, password)
    return session


def load_features_from_api(session: requests.Session, api_url: str) -> List[Dict[str, Any]]:
    """
    Load features from the DB API. Accepts:
    - A plain list of dicts
    - A paginated object with 'items'/'results'/'data'
    - A dict mapping {feature_name: config}
    Normalizes to a list of dict entries with FEATURE_NAME.
    """
    logging.info(f"Fetching features from API: {api_url}")
    resp = session.get(api_url)
    # If unauthorized, raise for caller to fallback
    if resp.status_code in (401, 403):
        raise requests.HTTPError(f"Unauthorized (status {resp.status_code}) for {api_url}")
    resp.raise_for_status()
    payload = resp.json()

    feature_entries: List[Dict[str, Any]] = []

    # Case 1: list of dicts
    if isinstance(payload, list):
        items = payload
    # Case 2: paginated dict
    elif isinstance(payload, dict):
        items = payload.get("items") or payload.get("results") or payload.get("data")
        if items is None:
            # Case 3: dict mapping name->config
            if all(isinstance(v, (dict, list, str, int, float, bool, type(None))) for v in payload.values()):
                feature_entries = []
                for feature_name, config in payload.items():
                    entry = {"FEATURE_NAME": feature_name}
                    if isinstance(config, dict):
                        entry.update(config)
                    feature_entries.append(entry)
                logging.info(f"Loaded {len(feature_entries)} features from dict-map API")
                return feature_entries
            items = []
    else:
        raise ValueError("API response is neither list nor dict")

    if not isinstance(items, list):
        raise ValueError("API 'items' is not a list")

    # Normalize FEATURE_NAME
    for i, item in enumerate(items):
        if not isinstance(item, dict):
            logging.warning(f"Skipping non-dict entry at index {i}: {type(item)}")
            continue
        entry = dict(item)
        if "FEATURE_NAME" not in entry or not entry.get("FEATURE_NAME"):
            for key in ("FEATURE_NAME", "feature_name", "feature", "name"):
                if key in item and item[key]:
                    entry["FEATURE_NAME"] = item[key]
                    break
        if "FEATURE_NAME" not in entry or not entry.get("FEATURE_NAME"):
            logging.warning(f"Skipping entry missing FEATURE_NAME: keys={list(item.keys())}")
            continue
        feature_entries.append(entry)

    logging.info(f"Loaded {len(feature_entries)} features from API")
    logging.info(f"Feature entries: {pformat(feature_entries, sort_dicts=False)}")
    return feature_entries


def main():
    """Main entry point for the load balancer script."""
    logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(levelname)s [%(filename)s:%(lineno)d]: %(message)s")

    # Parse command line arguments
    parser = argparse.ArgumentParser(description="Generate dispatch JSON and update features.json")
    parser.add_argument("-l", "--feature-list", default="feature_list.py", help="Python or JSON feature list")
    parser.add_argument("-d", "--durations", default=None, help="JSON file with test durations (fallback if MongoDB fails)")
    parser.add_argument("-n", "--nodes", default="node1,node2,node3", help="Comma-separated list of nodes to use")
    parser.add_argument("-x", "--exclude-nodes", default="", help="Comma-separated list of additional nodes to exclude (applied after node selection)")
    parser.add_argument("-a", "--use-jenkins-nodes", action="store_true", help="Query Jenkins for idle nodes instead of using --nodes")
    parser.add_argument("--jenkins-url", default=DEFAULT_JENKINS_URL, help="Jenkins URL for node query")
    parser.add_argument("--jenkins-user", default=DEFAULT_JENKINS_USER, help="Jenkins username for API access")
    parser.add_argument("--jenkins-token", default=DEFAULT_JENKINS_TOKEN, help="Jenkins API token")
    parser.add_argument("-r", "--reserved-nodes", default=DEFAULT_RESERVED_NODES, help="Comma-separated list of nodes to exclude")
    parser.add_argument("-e", "--exclude", default="", help="Comma-separated list of features to exclude")
    parser.add_argument("-f", "--features", nargs="?", const="", default="", help="Comma-separated list of features to include (if empty or not specified, include all)")
    parser.add_argument("-o", "--output", default="dispatch.json", help="Output dispatch JSON path")
    parser.add_argument(
        "-g",
        "--group-choice",
        nargs="?",
        const="all",
        default="all",
        choices=["all", "crit", "full", "tmp"],
        help="Filter test groups: 'all' (default), 'crit' (only .crit groups), 'full' (only .full groups). Use -g without value for 'all'",
    )

    # MongoDB arguments
    mongo_grp = parser.add_argument_group("MongoDB settings")
    mongo_grp.add_argument("--mongo-uri", default=DEFAULT_MONGO_URI, help="MongoDB connection URI")
    mongo_grp.add_argument("--mongo-db", default=DEFAULT_MONGO_DB, help="MongoDB database name")
    mongo_grp.add_argument("--mongo-collection", default=DEFAULT_MONGO_COLLECTION, help="MongoDB collection name")
    mongo_grp.add_argument("--release", default=None, help="Release version to filter durations (e.g., '7.6.4')")
    mongo_grp.add_argument("--no-mongo", action="store_true", help="Skip MongoDB and use only JSON file for durations")

    # API auth/config args
    parser.add_argument(
        "--api-url",
        default="http://10.96.227.206:8000/features/?page=1&page_size=1000",
        help="DB API endpoint returning a list/paginated list of feature dicts",
    )
    parser.add_argument(
        "--no-api",
        action="store_true",
        help="Disable API and load features from file only",
    )
    parser.add_argument("--api-user", default=os.getenv("FS_API_USER", "admin"), help="API username (env: FS_API_USER)")
    parser.add_argument("--api-pass", default=os.getenv("FS_API_PASS", "ftnt123!"), help="API password (env: FS_API_PASS)")
    parser.add_argument("--api-token", default=os.getenv("FS_API_TOKEN", ""), help="API bearer token (env: FS_API_TOKEN)")

    args = parser.parse_args()

    logging.info("=" * 60)
    logging.info("LOADING AND PROCESSING FEATURE LIST")
    logging.info("=" * 60)

    feature_entries: List[Dict[str, Any]] = []

    if not args.no_api:
        try:
            # Derive API base from api-url for auth endpoints
            parsed = urlparse(args.api_url)
            api_base = f"{parsed.scheme}://{parsed.netloc}/"
            session = authenticate_api(api_base, args.api_user or None, args.api_pass or None, args.api_token or None)

            # Probe with API call; if it fails (401/403/network), we fallback
            feature_entries = load_features_from_api(session, args.api_url)
        except Exception as e:
            logging.warning(f"API load failed, falling back to file '{args.feature_list}': {e}")

    if not feature_entries:
        feature_entries = load_feature_list(args.feature_list)
        logging.info(f"Loaded features from file: {args.feature_list}")
    # Step 2: Generate complete features.json (before filtering)
    merged_features = merge_features(feature_entries)
    write_features_dict(merged_features)
    write_features_dict_to_all_in_one_tools(merged_features)

    # Step 3: Apply feature inclusion filter (if specified)
    if args.features and args.features.strip():
        included_features = {name.strip() for name in args.features.split(",") if name.strip()}
        logging.info(f"Including only features: {', '.join(sorted(included_features))}")
        feature_entries = [entry for entry in feature_entries if entry["FEATURE_NAME"] in included_features]
        logging.info(f"After feature inclusion filter: {len(feature_entries)} features remaining")

    # Step 4: Apply exclusion filter
    static_exclusions = set(EXCLUDE_FEATURES)
    cli_exclusions = {name.strip() for name in args.exclude.split(",") if name.strip()}
    all_exclusions = static_exclusions.union(cli_exclusions)

    if all_exclusions:
        logging.info(f"Excluding features: {', '.join(sorted(all_exclusions))}")

    filtered_entries = [entry for entry in feature_entries if entry["FEATURE_NAME"] not in all_exclusions]

    # Step 5: Filter test groups based on group choice
    if args.group_choice != "all":
        logging.info(f"Filtering test groups by choice: {args.group_choice}")
        entries_with_groups = []

        for entry in filtered_entries:
            original_groups = entry.get("test_groups", [])
            filtered_groups = filter_test_groups_by_choice(original_groups, args.group_choice)

            if filtered_groups:
                # Create a copy of the entry with filtered groups
                filtered_entry = dict(entry)
                filtered_entry["test_groups"] = filtered_groups
                entries_with_groups.append(filtered_entry)
                logging.info(f"Feature '{entry['FEATURE_NAME']}': " f"{len(original_groups)} -> {len(filtered_groups)} groups")
            else:
                logging.info(f"Feature '{entry['FEATURE_NAME']}': " f"excluded (no {args.group_choice} groups)")

        filtered_entries = entries_with_groups
        logging.info(f"After group filtering: {len(filtered_entries)} features remaining")

    logging.info(f"Final feature count after all filtering: {len(filtered_entries)} features")

    # Step 6: Load test durations
    logging.info("=" * 60)
    logging.info("LOADING DURATION DATA")
    logging.info("=" * 60)

    # Initialize MongoDB client
    mongo_client = None
    if not args.no_mongo:
        try:
            mongo_client = MongoDBClient(args.mongo_uri, args.mongo_db, args.mongo_collection)
            logging.info(f"Connected to MongoDB: {args.mongo_uri}/{args.mongo_db}/{args.mongo_collection}")
        except Exception as e:
            logging.error(f"Failed to connect to MongoDB: {e}")
            mongo_client = None

    # Get duration entries from MongoDB or file
    duration_entries = get_duration_entries(mongo_client=mongo_client, release=args.release, fallback_file=args.durations)

    # Step 7: Determine available Jenkins nodes
    logging.info("=" * 60)
    logging.info("DETERMINING AVAILABLE NODES")
    logging.info("=" * 60)

    if args.use_jenkins_nodes:
        all_nodes = get_idle_jenkins_nodes(args.jenkins_url, args.jenkins_user, args.jenkins_token)
    else:
        all_nodes = [node.strip() for node in args.nodes.split(",") if node.strip()]

    # Remove reserved nodes
    reserved_nodes = [node.strip() for node in args.reserved_nodes.split(",") if node.strip()]
    reserved_nodes.sort(key=lambda name: int(name[4:]) if name.startswith("node") and name[4:].isdigit() else float("inf"))
    logging.info(f"Reserved nodes <{len(reserved_nodes)}>: {reserved_nodes}")

    available_nodes = [node for node in all_nodes if node not in reserved_nodes]

    # Remove additionally excluded nodes (applied after initial node selection)
    exclude_nodes = [node.strip() for node in args.exclude_nodes.split(",") if node.strip()]
    if exclude_nodes:
        exclude_nodes.sort(key=lambda name: int(name[4:]) if name.startswith("node") and name[4:].isdigit() else float("inf"))
        logging.info(f"Additional exclude nodes <{len(exclude_nodes)}>: {exclude_nodes}")
        available_nodes = [node for node in available_nodes if node not in exclude_nodes]

    # Calculate all excluded nodes for reporting
    set(all_nodes) - set(available_nodes)
    actually_reserved = set(all_nodes) & set(reserved_nodes)
    actually_excluded_additional = set(all_nodes) & set(exclude_nodes) if exclude_nodes else set()

    logging.info(
        f"Actually excluded reserved nodes <{len(actually_reserved)}>: "
        f"{sorted(list(actually_reserved), key=lambda name: int(name[4:]) if name.startswith('node') and name[4:].isdigit() else float('inf'))}"
    )

    if actually_excluded_additional:
        logging.info(
            f"Actually excluded additional nodes <{len(actually_excluded_additional)}>: "
            f"{sorted(list(actually_excluded_additional), key=lambda name: int(name[4:]) if name.startswith('node') and name[4:].isdigit() else float('inf'))}"
        )

    # Sort nodes numerically by their number portion
    available_nodes.sort(key=lambda name: int(name[4:]) if name.startswith("node") and name[4:].isdigit() else float("inf"))

    logging.info(f"Available nodes for dispatch <{len(available_nodes)}>: {available_nodes}")

    # Step 8: Verify we have features and nodes to work with
    if not filtered_entries:
        logging.error("No entries after filtering; aborting.")
        sys.exit(1)

    if not available_nodes:
        logging.error("No available nodes; aborting.")
        sys.exit(1)

    # Step 9: Calculate feature durations and node allocations
    logging.info("=" * 60)
    logging.info("CALCULATING LOAD DISTRIBUTION")
    logging.info("=" * 60)

    durations_queue = list(duration_entries)
    feature_durations = []

    for entry in filtered_entries:
        feature_name = entry["FEATURE_NAME"]
        duration_map = {}

        # Find matching duration entry
        for i, (name, dur_map) in enumerate(durations_queue):
            if name == feature_name:
                duration_map = dur_map
                durations_queue.pop(i)
                break

        if not duration_map:
            logging.warning(f"No durations for '{feature_name}' -> using 1hr/group default")

        # Sum durations for all test groups (now filtered)
        total_seconds = 0
        for group in entry.get("test_groups", []):
            if group in duration_map:
                total_seconds += parse_duration_to_seconds(duration_map[group])
            else:
                logging.warning(f"Missing duration for {feature_name}.{group} -> using 1hr default")
                total_seconds += 3600  # Default 1 hour

        feature_durations.append(total_seconds)

    # Allocate nodes to features proportionally
    node_allocations = allocate_counts_to_nodes(feature_durations, len(available_nodes), filtered_entries)

    # Step 10: Build dispatch entries
    logging.info("=" * 60)
    logging.info("GENERATING DISPATCH ENTRIES")
    logging.info("=" * 60)

    dispatch_entries = []
    used_nodes = set()  # Track which nodes have been assigned

    # Separate features into static and dynamic allocations
    static_features = []
    dynamic_features = []

    for entry, node_count in zip(filtered_entries, node_allocations):
        feature_name = entry["FEATURE_NAME"]
        if feature_name in FEATURE_NODE_STATIC_BINDING:
            static_features.append((entry, node_count))
        else:
            dynamic_features.append((entry, node_count))

    # Process static bindings first
    for entry, node_count in static_features:
        feature_name = entry["FEATURE_NAME"]
        static_node = FEATURE_NODE_STATIC_BINDING[feature_name]
        used_nodes.add(static_node)  # Mark node as used

        # Get durations and distribute groups across allocated nodes
        duration_map = next((dur_map for name, dur_map in duration_entries if name == feature_name), {})
        group_durations = {group: parse_duration_to_seconds(duration_map.get(group, "1 hr")) for group in entry.get("test_groups", [])}
        distributed_groups = distribute_groups_across_nodes(group_durations, node_count)

        # Create dispatch entry for each node's group set
        for groups, total_seconds in distributed_groups:
            # Skip entries with no test groups
            if not groups:
                logging.warning(f"Skipping empty group assignment for {feature_name} - " f"not enough test groups for allocated nodes")
                continue

            dispatch_entry = create_dispatch_entry(entry, static_node, groups, total_seconds, duration_entries)
            dispatch_entries.append(dispatch_entry)

    # Process dynamic features using remaining available nodes
    remaining_nodes = [node for node in available_nodes if node not in used_nodes]

    if not remaining_nodes and dynamic_features:
        logging.error("Not enough nodes available after static bindings!")
        sys.exit(1)

    node_index = 0

    for entry, node_count in dynamic_features:
        feature_name = entry["FEATURE_NAME"]

        # Get durations and distribute groups (same as for static features)
        duration_map = next((dur_map for name, dur_map in duration_entries if name == feature_name), {})
        group_durations = {group: parse_duration_to_seconds(duration_map.get(group, "1 hr")) for group in entry.get("test_groups", [])}
        distributed_groups = distribute_groups_across_nodes(group_durations, node_count)

        # Create dispatch entry for each node's group set
        for groups, total_seconds in distributed_groups:
            # Check if we have enough nodes left
            if node_index >= len(remaining_nodes):
                logging.error(f"Not enough nodes for all features! " f"Need at least {node_index+1} but only have {len(remaining_nodes)}")
                sys.exit(1)

            # Select the next available node
            chosen_node = remaining_nodes[node_index]
            used_nodes.add(chosen_node)
            node_index += 1

            dispatch_entry = create_dispatch_entry(entry, chosen_node, groups, total_seconds, duration_entries)
            dispatch_entries.append(dispatch_entry)

    # Step 11: Sort and write dispatch JSON
    def node_sort_key(entry):
        """Extract node number for sorting."""
        node_name = entry["NODE_NAME"]
        if node_name.startswith("node"):
            try:
                return int(node_name[4:])
            except ValueError:
                pass
        return node_name

    # Sort the dispatch entries by node name
    dispatch_entries.sort(key=node_sort_key)

    # Write dispatch JSON
    with open(args.output, "w") as f:
        json.dump(dispatch_entries, f, indent=2)

    # Step 12: Generate summary report
    logging.info("=" * 60)
    logging.info("DISPATCH GENERATION COMPLETE")
    logging.info("=" * 60)

    logging.info(f"Generated {len(dispatch_entries)} entries in {args.output}")

    # Log final node usage summary
    all_chosen_nodes = sorted(list(used_nodes), key=lambda name: int(name[4:]) if name.startswith("node") and name[4:].isdigit() else float("inf"))
    unused_nodes = sorted(
        [node for node in available_nodes if node not in used_nodes], key=lambda name: int(name[4:]) if name.startswith("node") and name[4:].isdigit() else float("inf")
    )

    logging.info("=" * 60)
    logging.info("NODE ALLOCATION SUMMARY")
    logging.info("=" * 60)
    logging.info(f"Total available nodes: {len(available_nodes)}")
    logging.info(f"Nodes chosen for dispatch: {len(all_chosen_nodes)}")
    logging.info(f"Nodes remaining unused: {len(unused_nodes)}")
    logging.info("")
    logging.info(f"Chosen nodes <{len(all_chosen_nodes)}>: {all_chosen_nodes}")
    if unused_nodes:
        logging.info(f"Unused nodes <{len(unused_nodes)}>: {unused_nodes}")
    else:
        logging.info("Unused nodes: None (all nodes allocated)")

    # Log node usage breakdown by feature
    logging.info("")
    logging.info("NODE USAGE BY FEATURE:")
    feature_node_map = {}
    for entry in dispatch_entries:
        feature_name = entry["FEATURE_NAME"]
        node_name = entry["NODE_NAME"]
        if feature_name not in feature_node_map:
            feature_node_map[feature_name] = []
        feature_node_map[feature_name].append(node_name)

    for feature_name in sorted(feature_node_map.keys()):
        nodes = feature_node_map[feature_name]
        unique_nodes = sorted(list(set(nodes)), key=lambda name: int(name[4:]) if name.startswith("node") and name[4:].isdigit() else float("inf"))
        node_assignments = len(nodes)
        unique_node_count = len(unique_nodes)

        if feature_name in FEATURE_NODE_STATIC_BINDING:
            binding_info = f" (static: {FEATURE_NODE_STATIC_BINDING[feature_name]})"
        else:
            binding_info = " (dynamic)"

        logging.info(f"  {feature_name}{binding_info}: " f"{node_assignments} assignments across {unique_node_count} nodes: {unique_nodes}")

    logging.info("=" * 60)


if __name__ == "__main__":
    main()
