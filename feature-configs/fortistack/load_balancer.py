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

def format_duration(total_sec: int) -> str:
    """Format seconds into "X hr Y min Z sec" (omitting zero parts)."""
    parts = []
    h, rem = divmod(total_sec, 3600)
    m, s   = divmod(rem, 60)
    if h: parts.append(f"{h} hr")
    if m: parts.append(f"{m} min")
    if s: parts.append(f"{s} sec")
    return " ".join(parts) if parts else "0 sec"

def allocate_nodes(feature_secs: dict, total_nodes: int) -> dict:
    """Proportionally allocate exactly total_nodes among features."""
    total = sum(feature_secs.values())
    raw = {f: feature_secs[f] / total * total_nodes for f in feature_secs}
    frac = {f: raw[f] - floor(raw[f]) for f in raw}
    alloc = {f: max(1, floor(raw[f])) for f in raw}
    s = sum(alloc.values())

    # if too many, shave off from smallest until match
    if s > total_nodes:
        excess = s - total_nodes
        cand = sorted((f for f in alloc if alloc[f] > 1), key=lambda f: raw[f])
        i = 0
        while excess and cand:
            feat = cand[i % len(cand)]
            alloc[feat] -= 1
            excess -= 1
            i += 1

    # if too few, add to largest fractional until match
    elif s < total_nodes:
        deficit = total_nodes - s
        cand = sorted(raw.keys(), key=lambda f: frac[f], reverse=True)
        i = 0
        while deficit:
            feat = cand[i % len(cand)]
            alloc[feat] += 1
            deficit -= 1
            i += 1

    return alloc

def distribute_test_groups(groups: dict, bins: int) -> list:
    """Greedy bin-pack groups (name->secs) into bins buckets."""
    items = sorted(groups.items(), key=lambda kv: kv[1], reverse=True)
    buckets = [{"secs":0, "names":[]} for _ in range(bins)]
    for name, secs in items:
        tgt = min(buckets, key=lambda b: b["secs"])
        tgt["names"].append(name)
        tgt["secs"] += secs
    return [(b["names"], b["secs"]) for b in buckets]

def main():
    p = argparse.ArgumentParser(
        description="Generate a load-balanced dispatch JSON, with optional feature exclusion."
    )
    p.add_argument("-f","--features", default="features.json",
                   help="Path to features.json")
    p.add_argument("-d","--durations", default="test_duration.json",
                   help="Path to test_duration.json")
    p.add_argument("-n","--nodes",
                   default="node1,node2,node3,node4,node6,node7,node9,node10,node16",
                   help="Comma-separated list of node names")
    p.add_argument("-e","--exclude", default="",
                   help="Comma-separated feature names to exclude")
    p.add_argument("-o","--output", default="dispatch.json",
                   help="Path to output dispatch JSON")
    args = p.parse_args()

    features_cfg = json.load(open(args.features))
    raw_durs     = json.load(open(args.durations))
    nodes        = args.nodes.split(',')
    excluded     = {f.strip() for f in args.exclude.split(',') if f.strip()}

    # 1) Build total seconds per feature, skipping excluded
    feature_secs = {}
    for feat, grp_map in raw_durs.items():
        if feat in excluded:
            continue
        feature_secs[feat] = sum(parse_duration(v) for v in grp_map.values())
    if not feature_secs:
        print("No features left after exclusion.", file=sys.stderr)
        sys.exit(1)

    # 2) Allocate nodes among remaining features
    alloc = allocate_nodes(feature_secs, len(nodes))

    dispatch = []
    idx = 0
    for feat, count in alloc.items():
        cfg = features_cfg.get(feat, {})
        # pick first entries
        case_folder = cfg["test_case_folder"][0]
        config_file = cfg["test_config"][0]
        compose     = cfg["docker_compose"][0]
        email_list  = cfg["email"][0]

        # filter test groups by duration data
        groups = {
            g: parse_duration(raw_durs[feat][g])
            for g in cfg["test_groups"]
            if g in raw_durs.get(feat, {})
        }
        if not groups:
            continue

        # 3) distribute test groups across allocated nodes
        bins = distribute_test_groups(groups, count)
        for names, sum_secs in bins:
            if idx >= len(nodes):
                print("ERROR: ran out of nodes!", file=sys.stderr)
                sys.exit(1)

            entry = {
                "NODE_NAME": nodes[idx],
                "FEATURE_NAME": feat,
                "TEST_CASE_FOLDER": case_folder,
                "TEST_CONFIG_CHOICE": config_file,
                "TEST_GROUP_CHOICE": names[0],
                "TEST_GROUPS": names,
                "SUM_DURATION": format_duration(sum_secs),
                "DOCKER_COMPOSE_FILE_CHOICE": compose,
                "SEND_TO": email_list,
                # inject per-feature overrides or use defaults
                "PROVISION_VMPC":   cfg.get("PROVISION_VMPC", False),
                "VMPC_NAMES":       cfg.get("VMPC_NAMES", ""),
                "PROVISION_DOCKER": cfg.get("PROVISION_DOCKER", True),
            }
            dispatch.append(entry)
            idx += 1

    # 4) Write out
    with open(args.output, 'w') as f:
        json.dump(dispatch, f, indent=2)
    print(f"✔ Generated {len(dispatch)} entries in {args.output}")

if __name__=="__main__":
    main()
