# FortiStack Load Balancer Documentation

## Overview

The FortiStack Load Balancer is a Python script that generates dispatch configurations for distributing test execution across multiple Jenkins nodes. It supports intelligent load balancing, static node bindings for specific features, and flexible node selection strategies.

## Table of Contents

- [Workflow](#workflow)
- [Node Selection Strategies](#node-selection-strategies)
- [Feature Selection](#feature-selection)
- [Group Filtering](#group-filtering)
- [Static Node Bindings](#static-node-bindings)
- [Duration Data Sources](#duration-data-sources)
- [Command Line Arguments](#command-line-arguments)
- [Output Files](#output-files)
- [Examples](#examples)
- [Exit Codes](#exit-codes)

---

## Workflow

The load balancer follows a 12-step process:

1. **Load feature list** from API or file (`feature_list.py` / `features.json`)
2. **Generate complete features.json** (merged feature definitions)
3. **Apply feature inclusion filters** (`--features`)
4. **Apply feature exclusion filters** (`--exclude`)
5. **Filter test groups by type** (`--group-choice`: all/crit/full/tmp)
6. **Load test duration data** from MongoDB or JSON fallback
7. **Determine available nodes** using `--nodes` and/or `--use-jenkins-nodes`
8. **Calculate load distribution** across nodes
9. **Process static node bindings** (from `FEATURE_NODE_STATIC_BINDING`)
10. **Process dynamic node allocations**
11. **Generate dispatch.json** with sorted entries
12. **Display summary report**

---

## Node Selection Strategies

### Selection Logic

The final node pool is determined based on the combination of `--nodes` and `--use-jenkins-nodes`:

| `--nodes` | `--use-jenkins-nodes` | Result |
|-----------|----------------------|---------|
| Empty | False | ❌ Error: No node pool specified |
| Empty | True | ✅ Use Jenkins idle nodes only |
| Specified | False | ✅ Use only the defined node pool |
| Specified | True | ✅ **INTERSECT** both pools |

### Node Specification Format

The `--nodes` parameter supports flexible notation:

- **Single nodes**: `"node2,node3,node10"`
- **Range notation**: `"node10-node20"` (expands to node10, node11, ..., node20)
- **Mixed**: `"node2,node3,node10-node20"`

### Strategy 1: Jenkins-only

```bash
python3 load_balancer.py --use-jenkins-nodes
```

**Result**: Uses all currently idle Jenkins nodes

**Use case**: Quick ad-hoc testing when you want to use whatever is available

### Strategy 2: Defined Pool Only

```bash
python3 load_balancer.py --nodes "node10-node20,node30"
```

**Result**: Uses only the specified nodes (supports range notation)

**Use case**: When you need specific nodes (e.g., specific hardware configuration)

### Strategy 3: Intersection (Recommended for Production)

```bash
python3 load_balancer.py --nodes "node10-node50" --use-jenkins-nodes
```

**Result**: Uses only nodes that are **BOTH** in the defined range **AND** idle in Jenkins

**Benefit**: Ensures nodes are available while respecting defined constraints

**Use case**: Production runs where you want safety (only idle nodes) but also control (specific range)

### Filtering

After determining the base pool, filters are applied in this order:

1. **Reserved nodes** are excluded (from `FEATURE_NODE_STATIC_BINDING` or `--reserved-nodes`)
2. **Additional exclude nodes** are removed (from `--exclude-nodes`)

---

## Feature Selection

### Include Specific Features

```bash
python3 load_balancer.py --features "foc,ztna,waf"
```

Runs only the specified features. If omitted or empty, all features are included.

### Exclude Features

```bash
python3 load_balancer.py --exclude "avfortisandbox,avfortindr"
```

Skips the specified features. Can be combined with `--features`.

### Combine Both

```bash
python3 load_balancer.py --features "foc,ztna" --exclude "foc"
```

**Note**: Exclusion takes precedence (foc will be excluded in this example)

---

## Group Filtering

Filter test groups by type using `--group-choice` or `-g`:

| Option | Description |
|--------|-------------|
| `all` | Include all test groups (default) |
| `crit` | Only `.crit` groups (critical tests) |
| `full` | Only `.full` groups (comprehensive tests) |
| `tmp` | Only `.tmp` groups (temporary/debug tests) |

### Examples

```bash
# Run only critical tests
python3 load_balancer.py -g crit --use-jenkins-nodes

# Run only full tests on specific node range
python3 load_balancer.py -g full --nodes "node40-node90"
```

---

## Static Node Bindings

Some features require specific hardware/software configurations and are statically bound to dedicated nodes. These bindings are defined in the `FEATURE_NODE_STATIC_BINDING` dictionary.

### Configuration Example

```python
FEATURE_NODE_STATIC_BINDING: Dict[str, str] = {
    "avfortisandbox": "node2",       # Single node
    "ztna": "node15",
    "foc": "node28,node29",          # Multiple nodes (pool)
    "waf": "node40",
    "avfortindr": "node99",
}
```

### How It Works

1. **Single node binding** (e.g., `"waf": "node40"`):
   - All WAF tests run on node40 only
   - If node40 is not available, WAF feature is skipped

2. **Multiple node binding** (e.g., `"foc": "node28,node29"`):
   - FOC test groups are distributed across node28 and node29
   - Uses bin-packing algorithm to balance load
   - If either node is unavailable, FOC feature is skipped

3. **Validation**:
   - Static nodes must be in the final available pool
   - If any static node is unavailable, the entire feature is skipped with a warning
   - Prevents resource conflicts

### Example Scenario

```bash
python3 load_balancer.py --nodes "node40-node90" --use-jenkins-nodes
```

**Result**:
- `foc` (bound to node28,node29): ❌ Skipped (nodes not in range)
- `waf` (bound to node40): ✅ Included (node40 is in range and idle)
- `ztna` (bound to node15): ❌ Skipped (node15 not in range)
- Other features: ✅ Distributed across node40-node90 (dynamic allocation)

---

## Duration Data Sources

Test durations are used to balance load across nodes. The script tries multiple sources in priority order:

### 1. MongoDB (Default)

```bash
python3 load_balancer.py --use-jenkins-nodes --release "7.6.5"
```

- Fetches historical test durations from MongoDB
- Filters by `--release` if specified
- Falls back to JSON if connection fails

**MongoDB Settings**:
- `--mongo-uri`: Connection URI (default: from environment or hardcoded)
- `--mongo-db`: Database name (default: `fortistack_qa`)
- `--mongo-collection`: Collection name (default: `results`)

### 2. JSON File (Fallback)

```bash
python3 load_balancer.py --durations "durations.json" --no-mongo
```

- Uses a JSON file with duration data
- Format:
  ```json
  {
    "feature_name": {
      "group_name": "1 hr 30 min",
      "another_group": "45 min"
    }
  }
  ```

### 3. Default (Last Resort)

If no duration data is found for a feature/group:
- **Default duration**: 1 hour per group
- A warning is logged

---

## Command Line Arguments

### Core Arguments

| Argument | Short | Default | Description |
|----------|-------|---------|-------------|
| `--feature-list` | `-l` | `feature_list.py` | Python or JSON feature list file |
| `--durations` | `-d` | `None` | JSON file with test durations (fallback) |
| `--nodes` | `-n` | `""` | Comma-separated list with range notation |
| `--exclude-nodes` | `-x` | `""` | Additional nodes to exclude |
| `--use-jenkins-nodes` | `-a` | `False` | Query Jenkins for idle nodes |
| `--reserved-nodes` | `-r` | (static) | Comma-separated list to exclude |
| `--exclude` | `-e` | `""` | Features to exclude |
| `--features` | `-f` | `""` | Features to include (empty = all) |
| `--output` | `-o` | `dispatch.json` | Output file path |
| `--group-choice` | `-g` | `all` | Filter groups: all/crit/full/tmp |

### Jenkins Settings

| Argument | Default | Description |
|----------|---------|-------------|
| `--jenkins-url` | (from config) | Jenkins URL for node query |
| `--jenkins-user` | (from config) | Jenkins username |
| `--jenkins-token` | (from config) | Jenkins API token |

### MongoDB Settings

| Argument | Default | Description |
|----------|---------|-------------|
| `--mongo-uri` | (from config) | MongoDB connection URI |
| `--mongo-db` | `fortistack_qa` | Database name |
| `--mongo-collection` | `results` | Collection name |
| `--release` | `None` | Release version filter |
| `--no-mongo` | `False` | Skip MongoDB, use JSON only |

### API Settings

| Argument | Default | Description |
|----------|---------|-------------|
| `--api-url` | (from config) | DB API endpoint for features |
| `--no-api` | `False` | Disable API, load from file only |
| `--api-user` | `admin` | API username (env: `FS_API_USER`) |
| `--api-pass` | `ftnt123!` | API password (env: `FS_API_PASS`) |
| `--api-token` | `""` | API bearer token (env: `FS_API_TOKEN`) |

---

## Output Files

### 1. `dispatch.json`

Complete dispatch configuration for Jenkins. Each entry represents one node assignment.

**Structure**:
```json
[
  {
    "PARAMS_JSON": {
      "build_name": "fortistack-",
      "send_to": "admin@example.com",
      "FGT_TYPE": "ALL",
      "LOCAL_LIB_DIR": "autolibv3",
      "SVN_BRANCH": "v760"
    },
    "FORCE_UPDATE_DOCKER_FILE": true,
    "SKIP_PROVISION": false,
    "SKIP_TEST": false,
    "NODE_NAME": "node40",
    "FEATURE_NAME": "waf",
    "TEST_CASE_FOLDER": "testcase_v1",
    "TEST_CONFIG_CHOICE": "env.waf.conf",
    "TEST_GROUP_CHOICE": "grp.waf.full",
    "TEST_GROUPS": "[\"grp.waf.full\"]",
    "SUM_DURATION": "2 hr 30 min",
    "DOCKER_COMPOSE_FILE_CHOICE": "docker.waf.yml",
    "SEND_TO": "admin@example.com",
    "PROVISION_VMPC": false,
    "VMPC_NAMES": "",
    "PROVISION_DOCKER": true,
    "ORIOLE_SUBMIT_FLAG": "succeeded",
    "BUILD_NUMBER": "3633",
    "RELEASE": "7.6.5",
    "AUTOLIB_BRANCH": "v3r10build0007"
  }
]
```

**Properties**:
- Sorted by node name (numerically)
- One entry per node assignment
- Contains all parameters for test execution

### 2. `features.json`

Merged feature definitions (updated in-place). Used by other scripts for feature configuration lookup.

### 3. Console Logs

Detailed summary including:
- Feature count and filtering results
- Node allocation summary
- Duration data sources
- Node usage by feature
- Warnings for skipped features

---

## Examples

### Example 1: Run All Features on Jenkins Idle Nodes

```bash
python3 load_balancer.py --use-jenkins-nodes
```

**What happens**:
- Queries Jenkins for all idle nodes
- Loads all features from API or file
- Distributes tests across available idle nodes
- Generates `dispatch.json`

---

### Example 2: Run Specific Features on Defined Node Range

```bash
python3 load_balancer.py --features "foc,ztna" --nodes "node10-node30"
```

**What happens**:
- Only FOC and ZTNA features are processed
- Uses only nodes 10-30 (21 nodes total)
- Does NOT check Jenkins idle status
- Static bindings validated (node15 for ztna, node28 for foc must be in range)

---

### Example 3: Intersection Strategy with Group Filtering

```bash
python3 load_balancer.py -n "node40-node90" -a -g full
```

**What happens**:
- Defines node pool: node40-node90 (51 nodes)
- Queries Jenkins for idle nodes
- Uses intersection: only nodes that are both in range AND idle
- Filters to only `.full` test groups
- Example result: 44 nodes available from intersection

---

### Example 4: Exclude Features and Reserve Nodes

```bash
python3 load_balancer.py -a --exclude "avfortisandbox,avfortindr" --reserved-nodes "node1,node2"
```

**What happens**:
- Uses Jenkins idle nodes
- Excludes avfortisandbox and avfortindr features
- Reserves node1 and node2 (won't be used for dispatch)
- Remaining nodes distributed among other features

---

### Example 5: Use Specific Release for Duration Data

```bash
python3 load_balancer.py -a --release "7.6.5" --mongo-db "fortistack_qa"
```

**What happens**:
- Queries MongoDB for test durations filtered by release 7.6.5
- Uses database `fortistack_qa`
- More accurate load balancing based on historical data
- Falls back to JSON or 1hr default if MongoDB unavailable

---

### Example 6: Range Notation Examples

```bash
# Single range
python3 load_balancer.py -n "node10-node20"

# Multiple ranges
python3 load_balancer.py -n "node1-node5,node10-node15"

# Mixed: ranges and individual nodes
python3 load_balancer.py -n "node2,node3,node10-node20,node50"
```

---

### Example 7: Debug with Verbose Logging

```bash
python3 load_balancer.py -a -n "node40-node90" 2>&1 | tee load_balancer.log
```

**What happens**:
- Runs with intersection strategy
- Logs all output to both console and `load_balancer.log`
- Useful for debugging node allocation issues

---

## Exit Codes

| Code | Meaning |
|------|---------|
| `0` | ✅ Success - `dispatch.json` generated |
| `1` | ❌ Error - one of: |
|  | • Configuration error |
|  | • No nodes available after filtering |
|  | • No features to test after filtering |
|  | • Static node not in available pool |
|  | • Intersection of node pools is empty |

---

## Troubleshooting

### Issue: "No nodes available after intersection"

**Cause**: The intersection of defined nodes and Jenkins idle nodes is empty.

**Solution**:
```bash
# Check which nodes are idle in Jenkins
python3 load_balancer.py -a --nodes "" | grep "Jenkins idle nodes"

# Adjust your node range to include idle nodes
python3 load_balancer.py -a --nodes "node1-node100"
```

---

### Issue: Feature skipped due to static binding

**Example log**:
```
Feature 'foc' is statically bound to nodes ['node28', 'node29'],
but the following nodes are NOT in the available pool: ['node28', 'node29'].
Skipping this feature.
```

**Solution**:
- Either include the static nodes in your range:
  ```bash
  python3 load_balancer.py -n "node1-node50" -a
  ```
- Or remove the feature from testing:
  ```bash
  python3 load_balancer.py -n "node40-node90" -a --exclude "foc"
  ```

---

### Issue: MongoDB connection failed

**Example log**:
```
Failed to connect to MongoDB: connection refused
```

**Solution**:
- Use JSON fallback:
  ```bash
  python3 load_balancer.py -a --durations "durations.json" --no-mongo
  ```
- Or fix MongoDB connection settings:
  ```bash
  python3 load_balancer.py -a --mongo-uri "mongodb://10.96.227.206:27017"
  ```

---

## Best Practices

1. **Production Runs**: Always use intersection strategy (`-n` + `-a`) for safety
2. **Development**: Use Jenkins-only (`-a`) for quick iterations
3. **Specific Hardware**: Use defined pool only (`-n`) when you need specific nodes
4. **Load Balancing**: Always specify `--release` for accurate duration data
5. **Debugging**: Pipe output to log file for troubleshooting
6. **Static Bindings**: Verify static nodes are in your range before running

---

## See Also

- `feature_list.py` - Feature definitions
- `features.json` - Merged feature configuration
- `dispatch.json` - Output dispatch configuration
- Jenkins Pipeline: `groupingPipelines_v9_auto.groovy`
