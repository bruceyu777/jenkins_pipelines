#!/usr/bin/env python3
import json, re, argparse, sys
from math import floor

def parse_duration(s: str) -> int:
    """Parse "X hr Y min Z sec" into total seconds."""
    h = m = sec = 0
    if hr := re.search(r'(\d+)\s*hr', s):   h = int(hr.group(1))
    if mn := re.search(r'(\d+)\s*min', s):  m = int(mn.group(1))
    if sc := re.search(r'(\d+)\s*sec', s):  sec = int(sc.group(1))
    return h*3600 + m*60 + sec

def allocate_nodes(feature_secs: dict, total_nodes: int) -> dict:
    """
    Return { feature: node_count } summing exactly to total_nodes,
    with at least 1 node each, proportional to feature_secs.
    """
    total = sum(feature_secs.values())
    # raw allocations and fractional parts
    raw = {f: feature_secs[f] / total * total_nodes for f in feature_secs}
    frac = {f: raw[f] - floor(raw[f]) for f in raw}

    # initial: at least 1, floor(raw) otherwise
    alloc = {f: max(1, floor(raw[f])) for f in raw}
    s = sum(alloc.values())

    # If too many nodes, reduce from smallest raw until sum == total_nodes
    if s > total_nodes:
        excess = s - total_nodes
        # candidates with alloc[f] > 1, sorted by raw ascending
        cand = sorted((f for f in alloc if alloc[f] > 1),
                      key=lambda f: raw[f])
        i = 0
        while excess and cand:
            feat = cand[i % len(cand)]
            alloc[feat] -= 1
            excess -= 1
            i += 1

    # If too few nodes, add to highest fractional until sum == total_nodes
    elif s < total_nodes:
        deficit = total_nodes - s
        # candidates sorted by frac descending
        cand = sorted(raw.keys(), key=lambda f: frac[f], reverse=True)
        i = 0
        while deficit:
            feat = cand[i % len(cand)]
            alloc[feat] += 1
            deficit -= 1
            i += 1

    return alloc

def distribute_test_groups(groups: dict, bins: int) -> list:
    """
    Greedy bin-pack groups (name->secs) into `bins` buckets.
    Returns list of list-of-names.
    """
    # sort descending by secs
    items = sorted(groups.items(), key=lambda kv: kv[1], reverse=True)
    buckets = [{"secs":0, "names":[]} for _ in range(bins)]
    for name, secs in items:
        tgt = min(buckets, key=lambda b: b["secs"])
        tgt["names"].append(name)
        tgt["secs"] += secs
    return [b["names"] for b in buckets]

def main():
    p = argparse.ArgumentParser()
    p.add_argument("-f","--features", default="features.json")
    p.add_argument("-d","--durations", default="test_duration.json")
    p.add_argument("-n","--nodes",
                   default="node1,node2,node3,node4,node5,node6,node7,node8,node9,node10")
    p.add_argument("-o","--output", default="dispatch.json")
    args = p.parse_args()

    features_cfg = json.load(open(args.features))
    raw_durs     = json.load(open(args.durations))
    nodes        = args.nodes.split(',')

    # compute total duration per feature in seconds
    feature_secs = {
        feat: sum(parse_duration(v) for v in raw_durs[feat].values())
        for feat in raw_durs
    }

    # allocate exactly len(nodes) among features
    alloc = allocate_nodes(feature_secs, len(nodes))

    dispatch = []
    idx = 0
    for feat, count in alloc.items():
        cfg = features_cfg.get(feat, {})
        # pick first choices
        case_folder = cfg["test_case_folder"][0]
        config_file = cfg["test_config"][0]
        compose     = cfg["docker_compose"][0]
        email_list  = cfg["email"][0]

        # build group->sec map for only available durations
        groups = {
            g: parse_duration(raw_durs[feat][g])
            for g in cfg["test_groups"]
            if g in raw_durs.get(feat, {})
        }
        if not groups:
            continue

        # split into 'count' bins
        bins = distribute_test_groups(groups, count)
        for b in bins:
            if idx >= len(nodes):
                print("ERROR: ran out of nodes!", file=sys.stderr)
                sys.exit(1)
            dispatch.append({
                "NODE_NAME": nodes[idx],
                "FEATURE_NAME": feat,
                "TEST_CASE_FOLDER": case_folder,
                "TEST_CONFIG_CHOICE": config_file,
                "TEST_GROUP_CHOICE": b[0],
                "TEST_GROUPS": b,
                "DOCKER_COMPOSE_FILE_CHOICE": compose,
                "SEND_TO": email_list
            })
            idx += 1

    # write result
    with open(args.output, 'w') as f:
        json.dump(dispatch, f, indent=2)
    print(f"✔ Generated {len(dispatch)} dispatch entries in {args.output}")

if __name__=="__main__":
    main()
