#!/usr/bin/env python3
"""
Load balancer script for Jenkins test-feature dispatch.

This script:
  - Reads a master feature-list JSON (dict or list) from `feature_list.json`.
  - Merges entries sharing the same FEATURE_NAME into a dict-based `features.json`.
  - Parses durations JSON (dict or list) into a queue of (feature, group durations) pairs.
  - Optionally queries Jenkins for idle agent nodes, or uses a provided node list.
  - Excludes specified features from dispatch.
  - Estimates total runtime per feature (defaulting missing groups to 1 hr).
  - Allocates features proportionally across nodes.
  - Splits each feature’s test-groups across its allocated nodes using greedy bin-packing.
  - Emits a dispatch JSON suitable for downstream pipeline triggers.

Usage:
  ./load_balancer.py [-a] [-e webfilter,antivirus] \
      -l feature_list.json -d test_duration.json \
      -n node1,node2,... -o dispatch.json

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

def load_feature_list(path):
    """
    Load feature-list JSON (dict or list) into a flat list of entries each
    containing a 'FEATURE_NAME' key.
    """
    raw = json.load(open(path))
    entries = []
    if isinstance(raw, dict):
        for name, cfg in raw.items():
            entry = {'FEATURE_NAME': name}
            entry.update(cfg)
            entries.append(entry)
    elif isinstance(raw, list):
        for item in raw:
            if not isinstance(item, dict):
                raise ValueError(f"Invalid feature-list entry: {item}")
            if 'FEATURE_NAME' in item:
                entries.append(dict(item))
            elif len(item) == 1:
                name, cfg = next(iter(item.items()))
                if not isinstance(cfg, dict):
                    raise ValueError(f"Config for {name} is not a dict: {cfg}")
                entry = {'FEATURE_NAME': name}
                entry.update(cfg)
                entries.append(entry)
            else:
                raise ValueError(f"Cannot decode feature-list item: {item}")
    else:
        raise ValueError("feature-list must be a dict or list of dicts")
    logging.info(f"Loaded {len(entries)} entries from {path}")
    return entries


def merge_features(entries):
    """
    Merge list-of-entries by FEATURE_NAME into a dict-based config.
    Concatenates list fields (deduped) and overwrites scalars by later entries.
    """
    merged = {}
    for entry in entries:
        name = entry['FEATURE_NAME']
        cfg = {k: v for k, v in entry.items() if k != 'FEATURE_NAME'}
        if name not in merged:
            merged[name] = dict(cfg)
        else:
            base = merged[name]
            # merge list-type fields
            for key in ['test_case_folder','test_config','test_groups','docker_compose','email']:
                if key in cfg:
                    combined = base.get(key, []) + cfg[key]
                    seen = set(); dedup = []
                    for x in combined:
                        if x not in seen:
                            dedup.append(x); seen.add(x)
                    base[key] = dedup
            # override scalar flags
            for flag in ['PROVISION_VMPC','PROVISION_DOCKER','VMPC_NAMES','ORIOLE_SUBMIT_FLAG']:
                if flag in cfg:
                    base[flag] = cfg[flag]
    return merged


def write_features_dict(merged, output_path='features.json'):
    with open(output_path, 'w') as f:
        json.dump(merged, f, indent=2)
    logging.info(f"Wrote {len(merged)} merged features to {output_path}")


def load_duration_entries(path):
    """
    Load durations JSON into a list of (feature_name, durations_map) pairs.
    Preserves duplicate features and input order.
    """
    raw = json.load(open(path))
    entries = []
    if isinstance(raw, dict):
        for name, dmap in raw.items():
            if isinstance(dmap, dict):
                entries.append((name, dmap))
            else:
                logging.warning(f"Skipping non-dict durations for {name}")
    elif isinstance(raw, list):
        for item in raw:
            if not isinstance(item, dict):
                raise ValueError(f"Invalid duration entry: {item}")
            if len(item) == 1:
                name, dmap = next(iter(item.items()))
                if isinstance(dmap, dict): entries.append((name, dmap))
                else: logging.warning(f"Skipping non-dict durations for {name}")
            elif 'durations' in item:
                name = item.get('feature') or item.get('FEATURE_NAME')
                dmap = item['durations']
                if isinstance(dmap, dict): entries.append((name, dmap))
                else: logging.warning(f"Skipping non-dict durations for {name}")
            else:
                logging.warning(f"Skipping unrecognized duration entry: {item}")
    else:
        raise ValueError("test_duration.json must be a dict or list of dicts")
    logging.info(f"Loaded {len(entries)} duration entries from {path}")
    return entries


def parse_duration_to_seconds(text):
    h=m=s=0
    if m1:=re.search(r"(\d+)\s*hr", text): h=int(m1.group(1))
    if m2:=re.search(r"(\d+)\s*min", text): m=int(m2.group(1))
    if m3:=re.search(r"(\d+)\s*sec", text): s=int(m3.group(1))
    return h*3600 + m*60 + s


def format_seconds_to_duration(sec):
    parts=[]
    h,rem=divmod(sec,3600)
    m,s=divmod(rem,60)
    if h: parts.append(f"{h} hr")
    if m: parts.append(f"{m} min")
    if s: parts.append(f"{s} sec")
    return ' '.join(parts) or '0 sec'


def allocate_counts_to_nodes(durations, node_count):
    total=sum(durations)
    raw=[d/total*node_count for d in durations]
    frac=[r-floor(r) for r in raw]
    cnt=[max(1,floor(r)) for r in raw]
    s=sum(cnt)
    if s>node_count:
        excess=s-node_count
        cand=sorted([i for i,c in enumerate(cnt) if c>1], key=lambda i:raw[i])
        idx=0
        while excess and cand:
            j=cand[idx%len(cand)]; cnt[j]-=1; excess-=1
            if cnt[j]==1: cand.remove(j)
            idx+=1
    elif s<node_count:
        deficit=node_count-s
        cand=sorted(range(len(raw)), key=lambda i:frac[i], reverse=True)
        idx=0
        while deficit:
            j=cand[idx%len(cand)]; cnt[j]+=1; deficit-=1; idx+=1
    return cnt


def distribute_groups_across_nodes(group_map, bins):
    items=sorted(group_map.items(), key=lambda kv:kv[1], reverse=True)
    buckets=[{'total':0,'groups':[]} for _ in range(bins)]
    for name,dur in items:
        tgt=min(buckets, key=lambda b:b['total'])
        tgt['groups'].append(name); tgt['total']+=dur
    return [(b['groups'], b['total']) for b in buckets]


def get_idle_jenkins_nodes(url, user, token):
    api=f"{url.rstrip('/')}/computer/api/json?tree=computer[displayName,offline,executors[currentExecutable[fullDisplayName]]]"
    try:
        r=requests.get(api, auth=(user,token)); r.raise_for_status(); data=r.json()
    except Exception as e:
        logging.error(f"Error querying Jenkins API: {e}")
        sys.exit(1)
    avail=[]
    for comp in data.get('computer',[]) or []:
        name,off=comp.get('displayName'),comp.get('offline',True)
        if not name or name=='master' or off: continue
        busy=False
        for ex in comp.get('executors',[]) or []:
            curr=ex.get('currentExecutable'); job=curr.get('fullDisplayName','') if isinstance(curr,dict) else ''
            if job.startswith('fortistack_runtest') or job.startswith('fortistack_provision_fgts'):
                busy=True; break
        if not busy: avail.append(name)
    logging.info(f"Found {len(avail)} idle Jenkins nodes")
    return avail


def main():
    logging.basicConfig(level=logging.INFO, format='[%(asctime)s] %(levelname)s [%(lineno)d]: %(message)s')
    parser=argparse.ArgumentParser(description="Generate dispatch JSON and update features.json")
    parser.add_argument('-l','--feature-list', default='feature_list.json', help='Path to feature_list.json')
    parser.add_argument('-d','--durations', default='test_duration.json', help='Path to durations JSON')
    parser.add_argument('-n','--nodes', default='node1,node2,node3', help='Comma-separated nodes')
    parser.add_argument('-a','--use-jenkins-nodes', action='store_true', help='Fetch nodes from Jenkins API')
    parser.add_argument('--jenkins-url', default=DEFAULT_JENKINS_URL, help='Jenkins URL')
    parser.add_argument('--jenkins-user', default=DEFAULT_JENKINS_USER, help='Jenkins user')
    parser.add_argument('--jenkins-token', default=DEFAULT_JENKINS_TOKEN, help='Jenkins token')
    parser.add_argument('-r','--reserved-nodes', default=DEFAULT_RESERVED_NODES, help='Comma-separated reserved nodes')
    parser.add_argument('-e','--exclude', default='', help='Comma-separated features to exclude')
    parser.add_argument('-o','--output', default='dispatch.json', help='Dispatch JSON output')
    args=parser.parse_args()

    # Step 1: merge feature-list → features.json
    feature_entries=load_feature_list(args.feature_list)
    logging.info(f"Loaded {len(feature_entries)} feature entries from {args.feature_list}")
    merged_features=merge_features(feature_entries)
    write_features_dict(merged_features, 'features.json')

    # Step 2: load durations
    duration_entries=load_duration_entries(args.durations)
    # Step 3: select nodes
    if args.use_jenkins_nodes:
        nodes=get_idle_jenkins_nodes(args.jenkins_url, args.jenkins_user, args.jenkins_token)
    else:
        nodes=[n.strip() for n in args.nodes.split(',') if n.strip()]
    reserved={n.strip() for n in args.reserved_nodes.split(',') if n.strip()}
    nodes=[n for n in nodes if n not in reserved]
    logging.info(f"Dispatch across <{len(nodes)}> nodes: {nodes}")

    # Step 4: exclude
    exclude={x.strip() for x in args.exclude.split(',') if x.strip()}
    features=[f for f in merged_features.keys() if f not in exclude]

    # Step 5: compute total durations
    dur_queue=list(duration_entries)
    totals=[]
    for feat in features:
        dmap={}
        for i,(n,d) in enumerate(dur_queue):
            if n==feat:
                dmap=d; dur_queue.pop(i); break
        if not dmap:
            logging.warning(f"No durations entry for '{feat}', defaulting 1 hr/group")
        sum_sec=0
        for grp in merged_features[feat].get('test_groups', []):
            if grp in dmap:
                sum_sec+=parse_duration_to_seconds(dmap[grp])
            else:
                logging.warning(f"Missing duration for {feat}.{grp}, defaulting to 1 hr")
                sum_sec+=3600
        totals.append(sum_sec)

    # Step 6: allocate counts
    counts=allocate_counts_to_nodes(totals, len(nodes))

    # Step 7: build dispatch
    dispatch=[]; idx=0
    for feat,cnt in zip(features, counts):
        cfg=merged_features[feat]
        # rebuild group durations for packing
        dmap=next((d for n,d in duration_entries if n==feat), {})
        group_map={g: parse_duration_to_seconds(dmap.get(g,'1 hr')) for g in cfg.get('test_groups', [])}
        buckets=distribute_groups_across_nodes(group_map, cnt)
        for groups,sec in buckets:
            dispatch.append({
                'NODE_NAME': nodes[idx%len(nodes)],
                'FEATURE_NAME': feat,
                'TEST_CASE_FOLDER': cfg.get('test_case_folder',[None])[0],
                'TEST_CONFIG_CHOICE': cfg.get('test_config',[None])[0],
                'TEST_GROUP_CHOICE': groups[0] if groups else '',
                'TEST_GROUPS': groups,
                'SUM_DURATION': format_seconds_to_duration(sec),
                'DOCKER_COMPOSE_FILE_CHOICE': cfg.get('docker_compose',[None])[0],
                'SEND_TO': ','.join(sorted(set(cfg.get('email',[''])[0].split(','))|ADMIN_EMAILS)),
                'PROVISION_VMPC': cfg.get('PROVISION_VMPC', False),
                'VMPC_NAMES': cfg.get('VMPC_NAMES',''),
                'PROVISION_DOCKER': cfg.get('PROVISION_DOCKER', True),
                'ORIOLE_SUBMIT_FLAG': cfg.get('ORIOLE_SUBMIT_FLAG','all')
            })
            idx+=1

    # Step 8: write dispatch
    with open(args.output,'w') as of:
        json.dump(dispatch, of, indent=2)
    logging.info(f"Generated {len(dispatch)} entries in {args.output}")

if __name__ == '__main__':
    main()
