#!/usr/bin/env python3
import json
import argparse
import sys

def load_json(path):
    with open(path, 'r') as f:
        return json.load(f)

def main():
    parser = argparse.ArgumentParser(
        description="Generate a load-balanced dispatch JSON from feature configs and test durations."
    )
    parser.add_argument(
        "--features", "-f", default="features.json",
        help="Path to features.json"
    )
    parser.add_argument(
        "--durations", "-d", default="test_duration.json",
        help="Path to test_duration.json"
    )
    parser.add_argument(
        "--nodes", "-n", default="node1,node2,node3,node4,node5,node6,node7,node8,node9,node10",
        help="Comma-separated list of node names"
    )
    parser.add_argument(
        "--output", "-o", default="dispatch.json",
        help="Path to output dispatch JSON"
    )
    args = parser.parse_args()

    # Load inputs
    features_cfg = load_json(args.features)
    durations     = load_json(args.durations)
    node_list     = args.nodes.split(',')

    # Only dispatch features that have duration data
    features = [f for f in features_cfg.keys() if f in durations]
    if not features:
        print("No features found with duration data.", file=sys.stderr)
        sys.exit(1)
    if len(node_list) < len(features):
        print(f"Not enough nodes ({len(node_list)}) for {len(features)} features", file=sys.stderr)
        sys.exit(1)

    dispatch = []
    for idx, feature in enumerate(features):
        node = node_list[idx]
        cfg  = features_cfg[feature]

        # pick the first entry from each array
        test_case_folder = cfg["test_case_folder"][0]
        test_config      = cfg["test_config"][0]
        docker_compose   = cfg["docker_compose"][0]
        email_list       = cfg["email"][0]

        # only include test groups that have a recorded duration
        all_groups = cfg["test_groups"]
        groups = [g for g in all_groups if g in durations.get(feature, {})]
        if not groups:
            print(f"  → skipping feature '{feature}' (no matching durations)", file=sys.stderr)
            continue

        dispatch.append({
            "NODE_NAME": node,
            "FEATURE_NAME": feature,
            "TEST_CASE_FOLDER": test_case_folder,
            "TEST_CONFIG_CHOICE": test_config,
            "TEST_GROUP_CHOICE": groups[0],
            "TEST_GROUPS": groups,
            "DOCKER_COMPOSE_FILE_CHOICE": docker_compose,
            "SEND_TO": email_list
        })

    # Write out the dispatch array
    with open(args.output, 'w') as f:
        json.dump(dispatch, f, indent=2)
    print(f"✔ Dispatch JSON written to {args.output}")

if __name__ == "__main__":
    main()
