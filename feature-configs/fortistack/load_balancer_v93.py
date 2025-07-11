#!/usr/bin/env python3
"""
Load balancer script for Jenkins test-feature dispatch.

This script:
  - Parses a features JSON (dict or list) into a flat list of feature configs.
  - Parses a durations JSON (dict or list) into a queue of (feature, group durations).
  - Optionally queries Jenkins for available agent nodes, or uses a provided node list.
  - Excludes specified features.
  - Estimates total runtime per feature (defaulting missing groups to 1 hr).
  - Allocates features proportionally across nodes.
  - Splits each feature’s test-groups across its allocated nodes using greedy bin-packing.
  - Emits a dispatch JSON suitable for downstream pipeline triggers.

Usage:
  ./load_balancer.py [-a] [-e webfilter,antivirus] \
      --features features.json --durations test_duration.json \
      --nodes node1,node2,... --output dispatch.json

Author: Automated
"""
import json
import logging
import re
import sys
import argparse
from math import floor
import requests

# -----------------------------------------------------------------------------
# Constants
# -----------------------------------------------------------------------------
ADMIN_EMAILS = {"yzhengfeng@fortinet.com", "wangd@fortinet.com"}
DEFAULT_JENKINS_URL = "http://10.96.227.206:8080"
DEFAULT_JENKINS_USER = "fosqa"
DEFAULT_JENKINS_TOKEN = "110dec5c2d2974a67968074deafccc1414"
DEFAULT_RESERVED_NODES = "Built-In Node,node12,node13,node14,node15,node19,node20"

# -----------------------------------------------------------------------------
# Helper Functions
# -----------------------------------------------------------------------------

def load_feature_configs(path):
    """
    Load features JSON into a list of feature configuration dicts.

    Supports either:
      - A mapping of feature_name -> config
      - A list of single-key dicts [{feature_name: config}, ...]
      - A list of dicts containing a 'FEATURE_NAME' field

    Returns:
        List[dict]: Each dict has a 'FEATURE_NAME' key and other config fields.
    """
    data = json.load(open(path))
    feature_configs = []
    if isinstance(data, dict):
        for feature_name, config in data.items():
            entry = {"FEATURE_NAME": feature_name}
            entry.update(config)
            feature_configs.append(entry)
    elif isinstance(data, list):
        for item in data:
            if not isinstance(item, dict):
                raise ValueError(f"Invalid feature entry: {item}")
            if len(item) == 1 and "FEATURE_NAME" not in item:
                feature_name, config = next(iter(item.items()))
                if not isinstance(config, dict):
                    raise ValueError(f"Config for {feature_name} is not a dict: {config}")
                entry = {"FEATURE_NAME": feature_name}
                entry.update(config)
                feature_configs.append(entry)
            elif "FEATURE_NAME" in item:
                feature_configs.append(dict(item))
            else:
                raise ValueError(f"Cannot decode feature item: {item}")
    else:
        raise ValueError("features.json must be a dict or a list of dicts")
    logging.info(f"Loaded {len(feature_configs)} features from {path}")
    return feature_configs


def load_duration_entries(path):
    """
    Load test_duration JSON into a list of (feature_name, durations_map) pairs.

    Supports either:
      - A dict of feature_name -> {group: duration}
      - A list of single-key dicts [{feature_name: {…}}, ...]
      - A list of dicts with 'feature' or 'FEATURE_NAME' and 'durations' keys

    Returns:
        List[tuple(str, dict)]: Preserves duplicates in input order.
    """
    data = json.load(open(path))
    entries = []
    if isinstance(data, dict):
        for feature_name, durations_map in data.items():
            if isinstance(durations_map, dict):
                entries.append((feature_name, durations_map))
            else:
                logging.warning(f"Skipping non-dict durations for {feature_name}")
    elif isinstance(data, list):
        for item in data:
            if not isinstance(item, dict):
                raise ValueError(f"Invalid duration entry: {item}")
            # single-key map
            if len(item) == 1 and 'durations' not in item:
                feature_name, durations_map = next(iter(item.items()))
                if isinstance(durations_map, dict):
                    entries.append((feature_name, durations_map))
                else:
                    logging.warning(f"Skipping non-dict durations for {feature_name}")
            # explicit fields
            elif ('feature' in item or 'FEATURE_NAME' in item) and 'durations' in item:
                feature_name = item.get('feature') or item.get('FEATURE_NAME')
                durations_map = item['durations']
                if isinstance(durations_map, dict):
                    entries.append((feature_name, durations_map))
                else:
                    logging.warning(f"Skipping non-dict durations for {feature_name}")
            else:
                logging.warning(f"Skipping unrecognized durations entry: {item}")
    else:
        raise ValueError("test_duration.json must be a dict or a list of dicts")
    logging.info(f"Loaded {len(entries)} duration entries from {path}")
    return entries


def parse_duration_to_seconds(duration_str: str) -> int:
    """
    Convert a string like 'X hr Y min Z sec' into total seconds.
    """
    hours = minutes = seconds = 0
    if hr := re.search(r'(\d+)\s*hr', duration_str):
        hours = int(hr.group(1))
    if mn := re.search(r'(\d+)\s*min', duration_str):
        minutes = int(mn.group(1))
    if sc := re.search(r'(\d+)\s*sec', duration_str):
        seconds = int(sc.group(1))
    return hours * 3600 + minutes * 60 + seconds


def format_seconds_to_duration(total_seconds: int) -> str:
    """
    Convert total seconds back into a human-readable string.
    """
    parts = []
    hours, remainder = divmod(total_seconds, 3600)
    minutes, seconds = divmod(remainder, 60)
    if hours:
        parts.append(f"{hours} hr")
    if minutes:
        parts.append(f"{minutes} min")
    if seconds:
        parts.append(f"{seconds} sec")
    return " ".join(parts) if parts else "0 sec"


def allocate_counts_to_nodes(durations: list, node_count: int) -> list:
    """
    Proportionally allocate a list of durations across node_count buckets.

    Each entry should get at least 1 node, then we adjust up or down to match sum.
    Returns a list of integer counts for each duration entry.
    """
    total_duration = sum(durations)
    raw_allocations = [d / total_duration * node_count for d in durations]
    fractional_parts = [alloc - floor(alloc) for alloc in raw_allocations]
    counts = [max(1, floor(alloc)) for alloc in raw_allocations]
    current_sum = sum(counts)

    # If we've over-allocated, remove from smallest raw allocations first
    if current_sum > node_count:
        excess = current_sum - node_count
        candidates = sorted(
            [i for i, c in enumerate(counts) if c > 1],
            key=lambda i: raw_allocations[i]
        )
        idx = 0
        while excess and candidates:
            target = candidates[idx % len(candidates)]
            counts[target] -= 1
            excess -= 1
            if counts[target] == 1:
                candidates.remove(target)
            idx += 1

    # If we've under-allocated, add to highest fractional parts
    elif current_sum < node_count:
        deficit = node_count - current_sum
        candidates = sorted(
            range(len(raw_allocations)),
            key=lambda i: fractional_parts[i],
            reverse=True
        )
        idx = 0
        while deficit:
            target = candidates[idx % len(candidates)]
            counts[target] += 1
            deficit -= 1
            idx += 1

    return counts


def distribute_groups_across_nodes(group_durations: dict, bucket_count: int) -> list:
    """
    Greedy bin-packing of test-groups into bucket_count buckets based on duration.
    Returns a list of (group_list, total_seconds) for each bucket.
    """
    items = sorted(group_durations.items(), key=lambda kv: kv[1], reverse=True)
    buckets = [{'total': 0, 'groups': []} for _ in range(bucket_count)]
    for group_name, duration in items:
        smallest = min(buckets, key=lambda b: b['total'])
        smallest['groups'].append(group_name)
        smallest['total'] += duration
    return [(b['groups'], b['total']) for b in buckets]


def get_idle_jenkins_nodes(url, user, token) -> list:
    """
    Query Jenkins /computer API and return a list of idle nodes (excluding master).
    Skips nodes with busy executors running fortistack jobs.
    """
    api = f"{url.rstrip('/')}/computer/api/json?tree=computer[displayName,offline,executors[currentExecutable[fullDisplayName]]]"
    try:
        response = requests.get(api, auth=(user, token))
        response.raise_for_status()
        data = response.json()
    except Exception as e:
        logging.error(f"Failed to query Jenkins API: {e}")
        sys.exit(1)

    idle_nodes = []
    for comp in data.get('computer', []) or []:
        name = comp.get('displayName')
        offline = comp.get('offline', True)
        if not name or name == 'master' or offline:
            continue
        busy = False
        for executor in comp.get('executors', []) or []:
            if not executor or not isinstance(executor, dict):
                continue
            current_exec = executor.get('currentExecutable')
            job_name = current_exec.get('fullDisplayName', '') if isinstance(current_exec, dict) else ''
            if job_name.startswith('fortistack_runtest') or job_name.startswith('fortistack_provision_fgts'):
                busy = True
                break
        if not busy:
            idle_nodes.append(name)
    logging.info(f"Found {len(idle_nodes)} idle Jenkins nodes")
    return idle_nodes


# -----------------------------------------------------------------------------
# Main Execution
# -----------------------------------------------------------------------------

def main():
    # Setup CLI and logging
    logging.basicConfig(
        level=logging.INFO,
        format='[%(asctime)s] %(levelname)s [%(lineno)d]: %(message)s'
    )
    parser = argparse.ArgumentParser(description="Generate Jenkins test-dispatch JSON.")
    parser.add_argument('-f', '--features', default='features.json', help="Path to features JSON")
    parser.add_argument('-d', '--durations', default='test_duration.json', help="Path to durations JSON")
    parser.add_argument('-n', '--nodes', default='node1,node2,node3,...', help="Comma-separated node list")
    parser.add_argument('-a', '--use-jenkins-nodes', action='store_true', help="Fetch nodes from Jenkins API")
    parser.add_argument('--jenkins-url', default=DEFAULT_JENKINS_URL, help="Jenkins base URL")
    parser.add_argument('--jenkins-user', default=DEFAULT_JENKINS_USER, help="Jenkins user")
    parser.add_argument('--jenkins-token', default=DEFAULT_JENKINS_TOKEN, help="Jenkins token")
    parser.add_argument('-r', '--reserved-nodes', default=DEFAULT_RESERVED_NODES, help="Comma-separated nodes to skip")
    parser.add_argument('-e', '--exclude', default='', help="Comma-separated features to exclude")
    parser.add_argument('-o', '--output', default='dispatch.json', help="Output dispatch JSON path")
    args = parser.parse_args()

    # Load configs
    feature_configs = load_feature_configs(args.features)
    duration_entries = load_duration_entries(args.durations)

    # Determine node pool
    if args.use_jenkins_nodes:
        node_list = get_idle_jenkins_nodes(args.jenkins_url, args.jenkins_user, args.jenkins_token)
    else:
        node_list = [n.strip() for n in args.nodes.split(',') if n.strip()]
    reserved = [n.strip() for n in args.reserved_nodes.split(',') if n.strip()]
    node_list = [n for n in node_list if n not in reserved]
    logging.info(f"Dispatching across <{len(node_list)}> nodes: {node_list}")

    # Exclude unwanted features
    exclude_set = [x.strip() for x in args.exclude.split(',') if x.strip()]
    filtered_features = [fc for fc in feature_configs if fc['FEATURE_NAME'] not in exclude_set]
    if not filtered_features:
        logging.error("No features remain after exclusion; aborting.")
        sys.exit(1)

    # Build a queue for durations matching
    duration_queue = list(duration_entries)

    # Compute total durations per feature
    total_durations = []
    for feature_config in filtered_features:
        name = feature_config['FEATURE_NAME']
        # Pop the first matching durations entry
        durations_map = {}
        for idx, (fname, dmap) in enumerate(duration_queue):
            if fname == name:
                durations_map = dmap
                duration_queue.pop(idx)
                break
        if not durations_map:
            logging.warning(f"No durations entry for '{name}', defaulting 1 hr/group")

        # Sum group durations (default 1hr if missing)
        sum_seconds = 0
        for group in feature_config.get('test_groups', []):
            if group in durations_map:
                sum_seconds += parse_duration_to_seconds(durations_map[group])
            else:
                logging.warning(f"Missing duration for {name}.{group}, defaulting to 1 hr")
                sum_seconds += 3600
        total_durations.append(sum_seconds)

    # Allocate feature-counts to nodes
    allocation_counts = allocate_counts_to_nodes(total_durations, len(node_list))

    # Build dispatch entries
    dispatch_entries = []
    feature_index = 0
    for feature_config, count in zip(filtered_features, allocation_counts):
        name = feature_config['FEATURE_NAME']
        # Pull common fields from config
        test_folder      = feature_config.get('test_case_folder', [None])[0]
        config_choice    = feature_config.get('test_config',    [None])[0]
        compose_file     = feature_config.get('docker_compose', [None])[0]
        recipients       = feature_config.get('email', [''])[0]
        email_set        = {e.strip() for e in recipients.split(',') if e.strip()}
        email_list       = ','.join(sorted(email_set | ADMIN_EMAILS))

        # Build per-group durations map again for bin-packing
        durations_map = next((dmap for fname, dmap in duration_entries if fname == name), {})
        group_seconds = {
            grp: parse_duration_to_seconds(durations_map.get(grp, '1 hr'))
            for grp in feature_config.get('test_groups', [])
        }

        # Distribute groups onto count buckets
        buckets = distribute_groups_across_nodes(group_seconds, count)
        for groups, seconds in buckets:
            node = node_list[feature_index % len(node_list)]
            dispatch_entries.append({
                'NODE_NAME':               node,
                'FEATURE_NAME':            name,
                'TEST_CASE_FOLDER':        test_folder,
                'TEST_CONFIG_CHOICE':      config_choice,
                'TEST_GROUP_CHOICE':       groups[0] if groups else '',
                'TEST_GROUPS':             groups,
                'SUM_DURATION':            format_seconds_to_duration(seconds),
                'DOCKER_COMPOSE_FILE_CHOICE': compose_file,
                'SEND_TO':                 email_list,
                'PROVISION_VMPC':          feature_config.get('PROVISION_VMPC', False),
                'VMPC_NAMES':              feature_config.get('VMPC_NAMES', ''),
                'PROVISION_DOCKER':        feature_config.get('PROVISION_DOCKER', True),
                'ORIOLE_SUBMIT_FLAG':      feature_config.get('ORIOLE_SUBMIT_FLAG', 'all')
            })
            feature_index += 1

    # Write out JSON
    with open(args.output, 'w') as out_file:
        json.dump(dispatch_entries, out_file, indent=2)
    logging.info(f"Generated {len(dispatch_entries)} dispatch entries in {args.output}")

if __name__ == '__main__':
    main()
