#!/usr/bin/env python3
import json, re, argparse, sys
import logging
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
# Helpers
# -----------------------------------------------------------------------------

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
    raw = {f: feature_secs[f]/total*total_nodes for f in feature_secs}
    frac = {f: raw[f]-floor(raw[f]) for f in raw}
    alloc = {f: max(1, floor(raw[f])) for f in raw}
    s = sum(alloc.values())
    if s > total_nodes:
        excess = s-total_nodes
        cand = sorted((f for f in alloc if alloc[f]>1), key=lambda f: raw[f])
        i=0
        while excess and cand:
            feat = cand[i%len(cand)]
            alloc[feat]-=1; excess-=1
            if alloc[feat]==1: cand.remove(feat)
            i+=1
    elif s < total_nodes:
        deficit=total_nodes-s
        cand=sorted(raw.keys(), key=lambda f: frac[f], reverse=True)
        i=0
        while deficit:
            feat=cand[i%len(cand)]
            alloc[feat]+=1; deficit-=1; i+=1
    return alloc


def distribute_test_groups(groups: dict, bins: int) -> list:
    """Greedy bin-pack groups (name->secs) into bins buckets."""
    if bins < 1:
        raise ValueError(f"Cannot distribute into {bins} bins")
    items = sorted(groups.items(), key=lambda kv: kv[1], reverse=True)
    buckets=[{'secs':0,'names':[]} for _ in range(bins)]
    for name,secs in items:
        tgt=min(buckets, key=lambda b: b['secs'])
        tgt['names'].append(name); tgt['secs']+=secs
    return [(b['names'], b['secs']) for b in buckets]


def get_jenkins_nodes(url, user, token) -> list:
    """Return idle nodes not running forbidden jobs."""
    api = f"{url.rstrip('/')}/computer/api/json?tree=computer[displayName,offline,executors[currentExecutable[fullDisplayName]]]"
    try:
        resp = requests.get(api, auth=(user, token))
        resp.raise_for_status()
        data = resp.json()
    except Exception as e:
        logging.error(f"Error querying Jenkins API: {e}")
        sys.exit(1)

    if not isinstance(data, dict) or 'computer' not in data:
        logging.error(f"Unexpected Jenkins API response structure: {data}")
        sys.exit(1)

    comps = data.get('computer') or []
    available = []
    unavailable = []
    for c in comps:
        name = c.get('displayName')
        off = c.get('offline', True)
        if not name or name == 'master' or off:
            continue
        busy = False
        # for ex in c.get('executors', []):
        #     job = ex.get('currentExecutable', {}).get('fullDisplayName', '')
        #     if job.startswith('fortistack_runtest') or job.startswith('fortistack_provision_fgts'):
        #         busy = True
        #         break
        
        for ex in c.get('executors', []) or []:
            # skip if the executor slot is empty
            if not ex or not isinstance(ex, dict):
                continue
            # safely pull the nested dict
            curr = ex.get('currentExecutable')
            if isinstance(curr, dict):
                job = curr.get('fullDisplayName', '')
            else:
                job = ''
            if job.startswith('fortistack_runtest') or job.startswith('fortistack_provision_fgts'):
                busy = True
                break

        if busy:
            unavailable.append(name)
        else:
            available.append(name)
    logging.info(f"Jenkins available nodes: {available}")
    logging.info(f"Jenkins unavailable nodes: {unavailable}")
    return available

# -----------------------------------------------------------------------------
# Main
# -----------------------------------------------------------------------------

def main():
    # setup logging with line number
    logging.basicConfig(
        level=logging.INFO,
        format='[%(asctime)s] %(levelname)s [%(lineno)d]: %(message)s'
    )

    parser = argparse.ArgumentParser(description="Generate a load-balanced dispatch JSON.")
    parser.add_argument('-f','--features',default='features.json')
    parser.add_argument('-d','--durations',default='test_duration.json')
    parser.add_argument('-n','--nodes',default='node1,node2,node3,node4,node5,node6,node7,node8,node9,node10,node16,node17,node18')
    parser.add_argument('-a','--use-jenkins-nodes',action='store_true')
    parser.add_argument('--jenkins-url',default=DEFAULT_JENKINS_URL)
    parser.add_argument('--jenkins-user',default=DEFAULT_JENKINS_USER)
    parser.add_argument('--jenkins-token',default=DEFAULT_JENKINS_TOKEN)
    parser.add_argument('-r','--reserved-nodes',default=DEFAULT_RESERVED_NODES)
    parser.add_argument('-e','--exclude',default='')
    parser.add_argument('-o','--output',default='dispatch.json')
    args=parser.parse_args()

    if args.use_jenkins_nodes:
        try:
            nodes=get_jenkins_nodes(args.jenkins_url,args.jenkins_user,args.jenkins_token)
        except Exception as e:
            logging.exception(f"Failed to fetch Jenkins nodes:\n")
            sys.exit(1)
    else:
        nodes=[n.strip() for n in args.nodes.split(',') if n.strip()]

    reserved={n.strip() for n in args.reserved_nodes.split(',') if n.strip()}
    logging.info(f"Reserved nodes: [{reserved}]")
    nodes=[n for n in nodes if n not in reserved]
    logging.info(f"Using nodes: {nodes}")

    features_cfg=json.load(open(args.features))
    raw_durs=json.load(open(args.durations))
    exclude={f.strip() for f in args.exclude.split(',') if f.strip()}

    feature_secs={}
    for feat,gm in raw_durs.items():
        if feat in exclude: continue
        feature_secs[feat]=sum(parse_duration(v) for v in gm.values())
    if not feature_secs:
        logging.error("No features left after exclusion")
        sys.exit(1)

    if len(feature_secs)>len(nodes):
        logging.error(f"{len(feature_secs)} features vs {len(nodes)} nodes")
        sys.exit(1)

    alloc=allocate_nodes(feature_secs,len(nodes))
    dispatch=[]; idx=0
    for feat,count in alloc.items():
        cfg=features_cfg.get(feat,{})
        cf=cfg.get('test_case_folder',[None])[0]
        tf=cfg.get('test_config',[None])[0]
        dc=cfg.get('docker_compose',[None])[0]
        raw=cfg.get('email',[''])[0]
        existing={a.strip() for a in raw.split(',') if a.strip()}
        email_list=','.join(sorted(existing|ADMIN_EMAILS))
        groups={g:parse_duration(raw_durs[feat].get(g,'0 sec')) for g in cfg.get('test_groups',[]) if g in raw_durs[feat]}
        if not groups: continue
        bins=distribute_test_groups(groups,count)
        for names,sum_secs in bins:
            if not names: continue
            if idx>=len(nodes):
                logging.error("Ran out of nodes")
                sys.exit(1)
            entry={
                'NODE_NAME':nodes[idx],'FEATURE_NAME':feat,
                'TEST_CASE_FOLDER':cf,'TEST_CONFIG_CHOICE':tf,
                'TEST_GROUP_CHOICE':names[0],'TEST_GROUPS':names,
                'SUM_DURATION':format_duration(sum_secs),
                'DOCKER_COMPOSE_FILE_CHOICE':dc,'SEND_TO':email_list,
                'PROVISION_VMPC':cfg.get('PROVISION_VMPC',False),'VMPC_NAMES':cfg.get('VMPC_NAMES',''),
                'PROVISION_DOCKER':cfg.get('PROVISION_DOCKER',True),'ORIOLE_SUBMIT_FLAG':cfg.get('ORIOLE_SUBMIT_FLAG','all')
            }
            dispatch.append(entry)
            idx+=1

    with open(args.output,'w') as f:
        json.dump(dispatch,f,indent=2)
    logging.info(f"Generated {len(dispatch)} entries in {args.output}")

if __name__=='__main__':
    main()
