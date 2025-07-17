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
  - Splits each entry’s test-groups across its allocated nodes using greedy bin-packing.
  - Emits a dispatch JSON suitable for downstream pipeline triggers.

Usage:
  ./load_balancer.py [-a] [-e webfilter,antivirus] \
      -l feature_list.py -d test_duration.json \
      -n node1,node2,... -o dispatch.json

Author: Automated
"""
import os
import sys

import json
import logging
import re
import argparse
from math import floor
import importlib.util
import requests

# -----------------------------------------------------------------------------
# Constants
# -----------------------------------------------------------------------------
ADMIN_EMAILS = {"yzhengfeng@fortinet.com", "wangd@fortinet.com","rainxiao@fortinet.com"}
DEFAULT_JENKINS_URL = "http://10.96.227.206:8080"
DEFAULT_JENKINS_USER = "fosqa"
DEFAULT_JENKINS_TOKEN = "110dec5c2d2974a67968074deafccc1414"
DEFAULT_RESERVED_NODES = "Built-In Node,node12,node13,node14,node15,node19,node20,node28,node29,node30"

# -----------------------------------------------------------------------------
# Helper Functions
# -----------------------------------------------------------------------------


def load_feature_list(path):
    """
    Load feature list from JSON (.json) or Python (.py) file.
    Python file must define FEATURE_LIST or feature_list.
    Returns: list of dict entries each containing 'FEATURE_NAME'.
    """
    name, ext = os.path.splitext(path)
    entries = []
    if ext == '.py':
        raw_text = open(path).read()
        # normalize JSON-style booleans to Python booleans
        normalized = re.sub(r'\btrue\b', 'True', raw_text, flags=re.IGNORECASE)
        normalized = re.sub(r'\bfalse\b', 'False', normalized, flags=re.IGNORECASE)
        spec = importlib.util.spec_from_loader('feature_list', loader=None)
        mod = importlib.util.module_from_spec(spec)
        exec(normalized, mod.__dict__)
        if hasattr(mod, 'FEATURE_LIST'):
            entries = getattr(mod, 'FEATURE_LIST')
        elif hasattr(mod, 'feature_list'):
            entries = getattr(mod, 'feature_list')
        else:
            raise ValueError(f"Python feature-list must define FEATURE_LIST or feature_list: {path}")
    else:
        raw = json.load(open(path))
        if isinstance(raw, dict):
            for fname, cfg in raw.items():
                entry = {'FEATURE_NAME': fname}
                entry.update(cfg)
                entries.append(entry)
        elif isinstance(raw, list):
            for item in raw:
                if not isinstance(item, dict):
                    raise ValueError(f"Invalid entry in {path}: {item}")
                if 'FEATURE_NAME' in item:
                    entries.append(dict(item))
                elif len(item) == 1:
                    fname, cfg = next(iter(item.items()))
                    if not isinstance(cfg, dict):
                        raise ValueError(f"Config for {fname} not dict: {cfg}")
                    entry = {'FEATURE_NAME': fname}
                    entry.update(cfg)
                    entries.append(entry)
                else:
                    raise ValueError(f"Cannot decode entry: {item}")
        else:
            raise ValueError(f"Feature list {path} must be .py or JSON dict/list")
    logging.info(f"Loaded {len(entries)} entries from {path}")
    return entries

def merge_features(entries):
    merged = {}
    for entry in entries:
        name = entry['FEATURE_NAME']
        cfg = {k: v for k, v in entry.items() if k != 'FEATURE_NAME'}
        if name not in merged:
            merged[name] = dict(cfg)
        # else:
        base = merged[name]
        for key in ['test_case_folder','test_config','test_groups','docker_compose','email']:
            if key in cfg:
                combined = base.get(key, []) + cfg[key]
                seen = set(); 
                deduped = []
                for x in combined:
                    if x not in seen:
                        deduped.append(x); 
                        seen.add(x)
                base[key] = deduped
                if key == 'email':
                    rec = base.get('email',[''])[0]
                    # logging.info(f"Combining emails for {name}: {rec} ")
                    em = ','.join(sorted({x.strip() for x in rec.split(',') if x.strip()} | ADMIN_EMAILS))
                    base['email'] = em
                    # logging.info(f"Final email for {name}: {em}")

        for flag in ['PROVISION_VMPC','PROVISION_DOCKER','VMPC_NAMES','ORIOLE_SUBMIT_FLAG']:
            if flag in cfg:
                base[flag] = cfg[flag]
    return merged


def write_features_dict(merged, output_path='features.json'):
    with open(output_path, 'w') as f:
        json.dump(merged, f, indent=2)
    logging.info(f"Wrote {len(merged)} merged features to {output_path}")

def write_features_dict_to_all_in_one_tools(merged, output_path='/home/fosqa/resources/tools/features.json'):
    with open(output_path, 'w') as f:
        json.dump(merged, f, indent=2)
    logging.info(f"Wrote {len(merged)} merged features to {output_path}")

def load_duration_entries(path):
    raw = json.load(open(path))
    entries = []
    if isinstance(raw, dict):
        for name, dmap in raw.items():
            if isinstance(dmap, dict):
                entries.append((name, dmap))
            else:
                logging.warning(f"Skipping durations for {name}")
    elif isinstance(raw, list):
        for item in raw:
            if not isinstance(item, dict):
                raise ValueError(f"Invalid duration entry: {item}")
            if len(item)==1 and 'durations' not in item:
                fname, dmap = next(iter(item.items()))
                if isinstance(dmap, dict): entries.append((fname, dmap))
                else: logging.warning(f"Skipping durations for {fname}")
            elif 'durations' in item:
                fname = item.get('feature') or item.get('FEATURE_NAME')
                dmap = item['durations']
                if isinstance(dmap, dict): entries.append((fname, dmap))
                else: logging.warning(f"Skipping durations for {fname}")
            else:
                logging.warning(f"Skipping unrecognized duration entry: {item}")
    else:
        raise ValueError(f"Durations {path} must be dict or list")
    logging.info(f"Loaded {len(entries)} duration entries from {path}")
    return entries


def parse_duration_to_seconds(text):
    h=m=s=0
    if m1 := re.search(r"(\d+)\s*hr", text): h=int(m1.group(1))
    if m2 := re.search(r"(\d+)\s*min", text): m=int(m2.group(1))
    if m3 := re.search(r"(\d+)\s*sec", text): s=int(m3.group(1))
    return h*3600 + m*60 + s


def format_seconds_to_duration(sec):
    parts=[]
    h,rem = divmod(sec,3600)
    m,s  = divmod(rem,60)
    if h: parts.append(f"{h} hr")
    if m: parts.append(f"{m} min")
    if s: parts.append(f"{s} sec")
    return ' '.join(parts) or '0 sec'


def allocate_counts_to_nodes(durations, node_count):
    total = sum(durations)
    raw   = [d/total*node_count for d in durations]
    frac  = [r - floor(r) for r in raw]
    cnt   = [max(1, floor(r)) for r in raw]
    ssum  = sum(cnt)
    if ssum>node_count:
        excess = ssum-node_count
        cand   = sorted([i for i,c in enumerate(cnt) if c>1], key=lambda i: raw[i])
        idx=0
        while excess and cand:
            j=cand[idx%len(cand)]; cnt[j]-=1; excess-=1
            if cnt[j]==1: cand.remove(j)
            idx+=1
    elif ssum<node_count:
        deficit=node_count-ssum
        cand   = sorted(range(len(raw)), key=lambda i: frac[i], reverse=True)
        idx=0
        while deficit:
            j=cand[idx%len(cand)]; cnt[j]+=1; deficit-=1; idx+=1
    return cnt


def distribute_groups_across_nodes(group_map, bins):
    items = sorted(group_map.items(), key=lambda kv: kv[1], reverse=True)
    buckets=[{'total':0,'groups':[]} for _ in range(bins)]
    for name,dur in items:
        tgt=min(buckets, key=lambda b: b['total'])
        tgt['groups'].append(name); tgt['total']+=dur
    return [(b['groups'],b['total']) for b in buckets]


def get_idle_jenkins_nodes(url,user,token):
    api=f"{url.rstrip('/')}/computer/api/json?tree=computer[displayName,offline,executors[currentExecutable[fullDisplayName]]]"
    try:
        r=requests.get(api,auth=(user,token)); r.raise_for_status(); data=r.json()
    except Exception as e:
        logging.error(f"Error querying Jenkins: {e}"); sys.exit(1)
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
    logging.basicConfig(level=logging.INFO,format='[%(asctime)s] %(levelname)s [%(lineno)d]: %(message)s')
    parser=argparse.ArgumentParser(description="Generate dispatch JSON and update features.json")
    parser.add_argument('-l','--feature-list',default='feature_list.py',help='Python or JSON feature list')
    parser.add_argument('-d','--durations',   default='test_duration.json')
    parser.add_argument('-n','--nodes',       default='node1,node2,node3')
    parser.add_argument('-a','--use-jenkins-nodes',action='store_true')
    parser.add_argument('--jenkins-url',      default=DEFAULT_JENKINS_URL)
    parser.add_argument('--jenkins-user',     default=DEFAULT_JENKINS_USER)
    parser.add_argument('--jenkins-token',    default=DEFAULT_JENKINS_TOKEN)
    parser.add_argument('-r','--reserved-nodes',default=DEFAULT_RESERVED_NODES)
    parser.add_argument('-e','--exclude',     default='')
    parser.add_argument('-o','--output',      default='dispatch.json')
    args=parser.parse_args()

    # Step 1: Load and merge feature-list
    feature_entries=load_feature_list(args.feature_list)
    logging.info(f"Loaded {len(feature_entries)} feature entries from {args.feature_list}")
    merged=merge_features(feature_entries)
    
    
    
    write_features_dict(merged)
    write_features_dict_to_all_in_one_tools(merged)

    # Step 2: Load durations
    duration_entries=load_duration_entries(args.durations)

    # Step 3: Select nodes
    if args.use_jenkins_nodes:
        nodes=get_idle_jenkins_nodes(args.jenkins_url,args.jenkins_user,args.jenkins_token)
    else:
        nodes=[n.strip() for n in args.nodes.split(',') if n.strip()]
    reserved={n.strip() for n in args.reserved_nodes.split(',') if n.strip()}
    nodes=[n for n in nodes if n not in reserved]
    nodes.sort()
    logging.info(f"Dispatch across <{len(nodes)}> nodes: {nodes}")

    # Step 4: Filter entries
    exclude_set={x.strip() for x in args.exclude.split(',') if x.strip()}
    filtered=[e for e in feature_entries if e['FEATURE_NAME'] not in exclude_set]
    if not filtered:
        logging.error("No entries after exclusion; aborting."); sys.exit(1)

    # Step 5: Compute total durations
    dq=list(duration_entries)
    total_secs=[]
    for e in filtered:
        name=e['FEATURE_NAME']
        dmap={}
        for i,(fn,dm) in enumerate(dq):
            if fn==name: dmap=dm; dq.pop(i); break
        if not dmap:
            logging.warning(f"No durations for '{name}' -> 1hr/group")
        ssum=0
        for grp in e.get('test_groups',[]):
            if grp in dmap: ssum+=parse_duration_to_seconds(dmap[grp])
            else:
                logging.warning(f"Missing {name}.{grp} -> 1hr"); ssum+=3600
        total_secs.append(ssum)

    # Step 6: Allocate counts
    counts=allocate_counts_to_nodes(total_secs,len(nodes))

    # Step 7: Build dispatch
    dispatch=[]; idx=0
    for e,cnt in zip(filtered,counts):
        name=e['FEATURE_NAME']
        folder=e.get('test_case_folder',[None])[0]
        cfg=e.get('test_config',[None])[0]
        comp=e.get('docker_compose',[None])[0]
        rec=e.get('email',[''])[0]
        em=','.join(sorted({x.strip() for x in rec.split(',') if x.strip()}|ADMIN_EMAILS))
        # rebuild dmap
        dmap=next((dm for fn,dm in duration_entries if fn==name),{})
        gm={grp:parse_duration_to_seconds(dmap.get(grp,'1 hr')) for grp in e.get('test_groups',[])}
        buckets=distribute_groups_across_nodes(gm,cnt)
        for groups,secs in buckets:
            dispatch.append({
                'NODE_NAME':nodes[idx%len(nodes)],
                'FEATURE_NAME':name,
                'TEST_CASE_FOLDER':folder,
                'TEST_CONFIG_CHOICE':cfg,
                'TEST_GROUP_CHOICE':groups[0] if groups else '',
                'TEST_GROUPS':groups,
                'SUM_DURATION':format_seconds_to_duration(secs),
                'DOCKER_COMPOSE_FILE_CHOICE':comp,
                'SEND_TO':em,
                'PROVISION_VMPC':e.get('PROVISION_VMPC',False),
                'VMPC_NAMES':e.get('VMPC_NAMES',''),
                'PROVISION_DOCKER':e.get('PROVISION_DOCKER',True),
                'ORIOLE_SUBMIT_FLAG':e.get('ORIOLE_SUBMIT_FLAG','all')
            })
            idx+=1

    # Step 8: Write dispatch JSON
    with open(args.output,'w') as f:
        json.dump(dispatch,f,indent=2)
    logging.info(f"Generated {len(dispatch)} entries in {args.output}")

if __name__=='__main__':
    main()
