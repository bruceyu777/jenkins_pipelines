#!/usr/bin/env python3
"""
Load balancer script for Jenkins test-feature dispatch.

This script:
  - Reads a master feature-list from either Python (.py) or JSON (.json) file.
    The Python file must define a top-level `FEATURE_LIST` or `feature_list` variable.
  - Merges entries sharing the same FEATURE_NAME into a dict-based `features.json`.
  - Parses durations JSON (dict or list) into a queue of (feature, group durations) pairs.
  - Optionally queries Jenkins for idle agent nodes, or uses a provided node list.
  - Excludes specified entries from dispatch (by FEATURE_NAME).
  - Estimates total runtime per feature-entry (defaulting missing groups to 1 hr).
  - Allocates each entry proportionally across nodes.
  - Splits each entry's test-groups across its allocated nodes using greedy bin-packing.
  - Emits a dispatch JSON suitable for downstream pipeline triggers.

Usage:
  ./load_balancer.py [-a] [-e webfilter,antivirus] \
      -l feature_list.py -d test_duration.json \
      -n node1,node2,... -o dispatch.json
"""
import argparse
import importlib.util
import json
import logging
import os
import re
import sys
from math import floor
from typing import Any, Dict, List, Set, Tuple

import requests

# -----------------------------------------------------------------------------
# Constants
# -----------------------------------------------------------------------------
ADMIN_EMAILS: Set[str] = {"yzhengfeng@fortinet.com", "wangd@fortinet.com", "rainxiao@fortinet.com"}
DEFAULT_JENKINS_URL: str = "http://10.96.227.206:8080"
DEFAULT_JENKINS_USER: str = "fosqa"
DEFAULT_JENKINS_TOKEN: str = "110dec5c2d2974a67968074deafccc1414"
DEFAULT_RESERVED_NODES: str = "Built-In Node,node1,node5,node11,node12,node13,node14,node15,node19,node20,node27,node33"

# Feature names mapped to dedicated Jenkins nodes
FEATURE_NODE_STATIC_BINDING: Dict[str, str] = {
    "foc": "node28",
    "waf": "node40",
}

# Features to exclude from processing
EXCLUDE_FEATURES: List[str] = []

# ORIOLE test result submission strategy by feature
# Options: 'all' (default), 'succeeded', 'none'
ORIOLE_SUBMIT_STRATEGY: Dict[str, str] = {
    "dlp": "succeeded",
}


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
        raw_text = open(path).read()
        # Normalize JSON-style booleans to Python booleans
        normalized_text = re.sub(r"\btrue\b", "True", raw_text, flags=re.IGNORECASE)
        normalized_text = re.sub(r"\bfalse\b", "False", normalized_text, flags=re.IGNORECASE)

        # Load as a module
        module_spec = importlib.util.spec_from_loader("feature_list", loader=None)
        feature_module = importlib.util.module_from_spec(module_spec)
        exec(normalized_text, feature_module.__dict__)

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
    return feature_entries


def merge_features(entries: List[Dict[str, Any]]) -> Dict[str, Dict[str, Any]]:
    """
    Merge entries with the same FEATURE_NAME into a single configuration.

    Args:
        entries: List of feature entries to merge

    Returns:
        Dictionary mapping feature names to merged configurations
    """
    merged_features = {}

    for entry in entries:
        feature_name = entry["FEATURE_NAME"]
        config = {k: v for k, v in entry.items() if k != "FEATURE_NAME"}

        if feature_name not in merged_features:
            merged_features[feature_name] = dict(config)

        base_config = merged_features[feature_name]

        # Merge list fields with deduplication
        for list_field in ["test_case_folder", "test_config", "test_groups", "docker_compose", "email"]:
            if list_field in config:
                # Ensure both are lists
                current_values = base_config.get(list_field, [])
                if not isinstance(current_values, list):
                    current_values = [current_values]

                new_values = config[list_field]
                if not isinstance(new_values, list):
                    new_values = [new_values]

                # Combine and deduplicate while preserving order
                combined_values = current_values + new_values
                unique_values = []
                seen_values = set()

                for value in combined_values:
                    if value not in seen_values:
                        unique_values.append(value)
                        seen_values.add(value)

                base_config[list_field] = unique_values

                # Special handling for email: convert to comma-separated string
                if list_field == "email":
                    email_addresses = set()

                    for email_entry in unique_values:
                        if email_entry and isinstance(email_entry, str):
                            email_addresses.update(addr.strip() for addr in email_entry.split(",") if addr.strip())

                    # Add admin emails and convert to sorted string
                    all_emails = sorted(email_addresses | ADMIN_EMAILS)
                    base_config["email"] = ",".join(all_emails)

        # Copy scalar fields directly
        for flag_field in ["PROVISION_VMPC", "PROVISION_DOCKER", "VMPC_NAMES", "ORIOLE_SUBMIT_FLAG"]:
            if flag_field in config:
                base_config[flag_field] = config[flag_field]

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


def load_duration_entries(path: str) -> List[Tuple[str, Dict[str, str]]]:
    """
    Load test duration data from JSON file.

    Returns:
        List of (feature_name, duration_map) pairs
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


def parse_duration_to_seconds(duration_text: str) -> int:
    """Convert a duration string like '3 hr 45 min 20 sec' to seconds."""
    hours = minutes = seconds = 0

    if hour_match := re.search(r"(\d+)\s*hr", duration_text):
        hours = int(hour_match.group(1))

    if minute_match := re.search(r"(\d+)\s*min", duration_text):
        minutes = int(minute_match.group(1))

    if second_match := re.search(r"(\d+)\s*sec", duration_text):
        seconds = int(second_match.group(1))

    return hours * 3600 + minutes * 60 + seconds


def format_seconds_to_duration(seconds: int) -> str:
    """Convert seconds to a human-readable duration string."""
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


def allocate_counts_to_nodes(durations: List[int], node_count: int, feature_entries: List[Dict]) -> List[int]:
    """
    Allocate each feature to a proportional number of nodes based on its duration.

    Args:
        durations: List of feature durations in seconds
        node_count: Total number of available nodes

    Returns:
        List of node counts for each feature (minimum 1 per feature)
    """
    total_duration = sum(durations)
    raw_allocation = [duration / total_duration * node_count for duration in durations]
    fractional_parts = [raw - floor(raw) for raw in raw_allocation]
    node_counts = [max(1, floor(raw)) for raw in raw_allocation]

    sum_allocated = sum(node_counts)

    # Handle over-allocation
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

    # Handle under-allocation
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
    for i, (duration, entry) in enumerate(zip(durations, feature_entries)):
        max_groups = len(entry.get("test_groups", []))
        if max_groups > 0:  # Only constrain if there are actual groups
            node_counts[i] = min(node_counts[i], max_groups)

    return node_counts


def distribute_groups_across_nodes(group_duration_map: Dict[str, int], bin_count: int) -> List[Tuple[List[str], int]]:
    """
    Distribute test groups across nodes using a greedy bin-packing algorithm.

    Args:
        group_duration_map: Map of group names to durations in seconds
        bin_count: Number of nodes/bins to distribute across

    Returns:
        List of (groups, total_duration) tuples for each node
    """
    # Sort groups by duration (descending)
    sorted_groups = sorted(group_duration_map.items(), key=lambda item: item[1], reverse=True)

    # Initialize bins
    bins = [{"total_duration": 0, "groups": []} for _ in range(bin_count)]

    # Greedy bin packing: assign each group to the bin with least load
    for group_name, duration in sorted_groups:
        target_bin = min(bins, key=lambda bin: bin["total_duration"])
        target_bin["groups"].append(group_name)
        target_bin["total_duration"] += duration

    # Convert to return format
    return [(bin["groups"], bin["total_duration"]) for bin in bins]


def get_idle_jenkins_nodes(url: str, user: str, token: str) -> List[str]:
    """
    Query Jenkins API for idle nodes.

    Args:
        url: Jenkins base URL
        user: Jenkins username
        token: Jenkins API token

    Returns:
        List of idle node names

    Raises:
        SystemExit: If the Jenkins API request fails
    """
    api_url = f"{url.rstrip('/')}/computer/api/json?tree=computer[displayName,offline,executors[currentExecutable[fullDisplayName]]]"

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

        if not node_name or node_name == "master" or is_offline:
            continue

        # Check if any executor is running a fortistack job
        node_is_busy = False
        for executor in computer.get("executors", []) or []:
            current_executable = executor.get("currentExecutable")
            job_name = current_executable.get("fullDisplayName", "") if isinstance(current_executable, dict) else ""

            if job_name.startswith("fortistack_runtest") or job_name.startswith("fortistack_provision_fgts"):
                node_is_busy = True
                break

        if not node_is_busy:
            available_nodes.append(node_name)

    # Sort nodes numerically
    available_nodes.sort(key=lambda name: int(name[4:]) if name.startswith("node") and name[4:].isdigit() else float("inf"))

    logging.info(f"Found idle Jenkins nodes <{len(available_nodes)}>: {available_nodes}")
    return available_nodes


def main():
    """Main entry point for the load balancer script."""
    logging.basicConfig(level=logging.INFO, format="[%(asctime)s] %(levelname)s [%(filename)s:%(lineno)d]: %(message)s")

    parser = argparse.ArgumentParser(description="Generate dispatch JSON and update features.json")
    parser.add_argument("-l", "--feature-list", default="feature_list.py", help="Python or JSON feature list")
    parser.add_argument("-d", "--durations", default="test_duration.json", help="JSON file with test durations")
    parser.add_argument("-n", "--nodes", default="node1,node2,node3", help="Comma-separated list of nodes to use")
    parser.add_argument("-a", "--use-jenkins-nodes", action="store_true", help="Query Jenkins for idle nodes instead of using --nodes")
    parser.add_argument("--jenkins-url", default=DEFAULT_JENKINS_URL, help="Jenkins URL for node query")
    parser.add_argument("--jenkins-user", default=DEFAULT_JENKINS_USER, help="Jenkins username for API access")
    parser.add_argument("--jenkins-token", default=DEFAULT_JENKINS_TOKEN, help="Jenkins API token")
    parser.add_argument("-r", "--reserved-nodes", default=DEFAULT_RESERVED_NODES, help="Comma-separated list of nodes to exclude")
    parser.add_argument("-e", "--exclude", default="", help="Comma-separated list of features to exclude")
    parser.add_argument("-o", "--output", default="dispatch.json", help="Output dispatch JSON path")
    args = parser.parse_args()

    # Step 1: Load feature list and filter excluded features
    feature_entries = load_feature_list(args.feature_list)

    # Combine static and CLI exclusions
    static_exclusions = set(EXCLUDE_FEATURES)
    cli_exclusions = {name.strip() for name in args.exclude.split(",") if name.strip()}
    all_exclusions = static_exclusions.union(cli_exclusions)

    if all_exclusions:
        logging.info(f"Excluding features: {', '.join(all_exclusions)}")

    filtered_entries = [entry for entry in feature_entries if entry["FEATURE_NAME"] not in all_exclusions]

    logging.info(f"Loaded {len(filtered_entries)} feature entries from {args.feature_list} after exclusion")

    # Step 2: Merge features with the same name and write to features.json
    merged_features = merge_features(filtered_entries)
    write_features_dict(merged_features)
    write_features_dict_to_all_in_one_tools(merged_features)

    # Step 3: Load test durations
    duration_entries = load_duration_entries(args.durations)

    # Step 4: Select Jenkins nodes
    if args.use_jenkins_nodes:
        all_nodes = get_idle_jenkins_nodes(args.jenkins_url, args.jenkins_user, args.jenkins_token)
    else:
        all_nodes = [node.strip() for node in args.nodes.split(",") if node.strip()]

    # Remove reserved nodes
    reserved_nodes = [node.strip() for node in args.reserved_nodes.split(",") if node.strip()]
    reserved_nodes.sort(key=lambda name: int(name[4:]) if name.startswith("node") and name[4:].isdigit() else float("inf"))
    logging.info(f"Reserved nodes <{len(reserved_nodes)}> : {reserved_nodes}")

    available_nodes = [node for node in all_nodes if node not in reserved_nodes]

    actually_reserved = set(all_nodes) & set(reserved_nodes)
    logging.info(
        f"Actually excluded nodes <{len(actually_reserved)}>: {sorted(list(actually_reserved), key=lambda name: int(name[4:]) if name.startswith('node') and name[4:].isdigit() else float('inf'))}"
    )

    # Sort nodes numerically by their number portion
    available_nodes.sort(key=lambda name: int(name[4:]) if name.startswith("node") and name[4:].isdigit() else float("inf"))

    logging.info(f"Dispatch across <{len(available_nodes)}> nodes: {available_nodes}")

    # Step 5: Verify we have features to process
    if not filtered_entries:
        logging.error("No entries after exclusion; aborting.")
        sys.exit(1)

    # Step 6: Compute total durations for each feature
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

        # Sum durations for all test groups
        total_seconds = 0
        for group in entry.get("test_groups", []):
            if group in duration_map:
                total_seconds += parse_duration_to_seconds(duration_map[group])
            else:
                logging.warning(f"Missing duration for {feature_name}.{group} -> using 1hr default")
                total_seconds += 3600  # Default 1 hour

        feature_durations.append(total_seconds)

    # Step 7: Allocate nodes to features proportionally
    node_allocations = allocate_counts_to_nodes(feature_durations, len(available_nodes), filtered_entries)

    # Step 8: Build dispatch with proper node assignment
    dispatch_entries = []
    used_nodes = set()  # Track which nodes have been assigned

    # First, handle features with static node bindings
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

        # Extract feature configuration
        test_folder = entry.get("test_case_folder", [None])[0]
        test_config = entry.get("test_config", [None])[0]
        docker_compose = entry.get("docker_compose", [None])[0]
        email_recipients = entry.get("email", "")

        if isinstance(email_recipients, list) and email_recipients:
            email_str = email_recipients[0]
        else:
            email_str = email_recipients

        # Create email list with admin addresses
        if email_str:
            email_addresses = {addr.strip() for addr in email_str.split(",") if addr.strip()}
            all_emails = ",".join(sorted(email_addresses | ADMIN_EMAILS))
        else:
            all_emails = ",".join(sorted(ADMIN_EMAILS))

        # Get durations for test groups
        duration_map = next((dur_map for name, dur_map in duration_entries if name == feature_name), {})

        # Create group-to-duration mapping
        group_durations = {group: parse_duration_to_seconds(duration_map.get(group, "1 hr")) for group in entry.get("test_groups", [])}

        # Distribute groups across allocated nodes
        distributed_groups = distribute_groups_across_nodes(group_durations, node_count)

        # Create dispatch entry for each node's group set
        for groups, total_seconds in distributed_groups:
            # Get submission strategy: first check ORIOLE_SUBMIT_STRATEGY, then entry, default to 'all'
            submit_flag = ORIOLE_SUBMIT_STRATEGY.get(feature_name, entry.get("ORIOLE_SUBMIT_FLAG", "all"))

            dispatch_entries.append(
                {
                    "NODE_NAME": static_node,
                    "FEATURE_NAME": feature_name,
                    "TEST_CASE_FOLDER": test_folder,
                    "TEST_CONFIG_CHOICE": test_config,
                    "TEST_GROUP_CHOICE": groups[0] if groups else "",
                    "TEST_GROUPS": groups,
                    "SUM_DURATION": format_seconds_to_duration(total_seconds),
                    "DOCKER_COMPOSE_FILE_CHOICE": docker_compose,
                    "SEND_TO": all_emails,
                    "PROVISION_VMPC": entry.get("PROVISION_VMPC", False),
                    "VMPC_NAMES": entry.get("VMPC_NAMES", ""),
                    "PROVISION_DOCKER": entry.get("PROVISION_DOCKER", True),
                    "ORIOLE_SUBMIT_FLAG": submit_flag,
                }
            )

    # Process dynamic features, using remaining available nodes
    remaining_nodes = [node for node in available_nodes if node not in used_nodes]

    if not remaining_nodes and dynamic_features:
        logging.error("Not enough nodes available after static bindings!")
        sys.exit(1)

    node_index = 0

    for entry, node_count in dynamic_features:
        feature_name = entry["FEATURE_NAME"]

        # Extract feature configuration (same as for static features)
        test_folder = entry.get("test_case_folder", [None])[0]
        test_config = entry.get("test_config", [None])[0]
        docker_compose = entry.get("docker_compose", [None])[0]
        email_recipients = entry.get("email", "")

        if isinstance(email_recipients, list) and email_recipients:
            email_str = email_recipients[0]
        else:
            email_str = email_recipients

        if email_str:
            email_addresses = {addr.strip() for addr in email_str.split(",") if addr.strip()}
            all_emails = ",".join(sorted(email_addresses | ADMIN_EMAILS))
        else:
            all_emails = ",".join(sorted(ADMIN_EMAILS))

        # Get durations and distribute groups (same as for static features)
        duration_map = next((dur_map for name, dur_map in duration_entries if name == feature_name), {})
        group_durations = {group: parse_duration_to_seconds(duration_map.get(group, "1 hr")) for group in entry.get("test_groups", [])}
        distributed_groups = distribute_groups_across_nodes(group_durations, node_count)

        # Create dispatch entry for each node's group set
        for groups, total_seconds in distributed_groups:
            # Check if we have enough nodes left
            if node_index >= len(remaining_nodes):
                logging.error(f"Not enough nodes for all features! Need at least {node_index+1} but only have {len(remaining_nodes)}")
                sys.exit(1)

            # Select the next available node
            chosen_node = remaining_nodes[node_index]
            used_nodes.add(chosen_node)
            node_index += 1

            # Get submission strategy
            submit_flag = ORIOLE_SUBMIT_STRATEGY.get(feature_name, entry.get("ORIOLE_SUBMIT_FLAG", "all"))

            dispatch_entries.append(
                {
                    "NODE_NAME": chosen_node,
                    "FEATURE_NAME": feature_name,
                    "TEST_CASE_FOLDER": test_folder,
                    "TEST_CONFIG_CHOICE": test_config,
                    "TEST_GROUP_CHOICE": groups[0] if groups else "",
                    "TEST_GROUPS": groups,
                    "SUM_DURATION": format_seconds_to_duration(total_seconds),
                    "DOCKER_COMPOSE_FILE_CHOICE": docker_compose,
                    "SEND_TO": all_emails,
                    "PROVISION_VMPC": entry.get("PROVISION_VMPC", False),
                    "VMPC_NAMES": entry.get("VMPC_NAMES", ""),
                    "PROVISION_DOCKER": entry.get("PROVISION_DOCKER", True),
                    "ORIOLE_SUBMIT_FLAG": submit_flag,
                }
            )

    # Step 9: Sort dispatch entries by node name (numerically)
    def node_sort_key(entry):
        """Extract node number for sorting"""
        node_name = entry["NODE_NAME"]
        # Extract numeric part from node name (e.g., 'node25' -> 25)
        if node_name.startswith("node"):
            try:
                return int(node_name[4:])
            except ValueError:
                pass
        # For non-numeric or unparseable nodes, return the original name
        return node_name

    # Sort the dispatch entries by node name
    dispatch_entries.sort(key=node_sort_key)

    # Step 10: Write dispatch JSON
    with open(args.output, "w") as f:
        json.dump(dispatch_entries, f, indent=2)
    logging.info(f"Generated {len(dispatch_entries)} entries in {args.output}")


if __name__ == "__main__":
    main()
