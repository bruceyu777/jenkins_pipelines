# Jenkins Node Management Tools

This directory contains automation scripts for managing Jenkins slave nodes in the FortiStack infrastructure.

## Scripts Overview

### 1. `scan_nodes.sh`
**Purpose**: Network discovery and node mapping
**Description**: Scans the 10.96.234.0/25 network segment to discover Jenkins slave nodes and create IP mappings.

**Usage:**
```bash
cd /home/fosqa/jenkins-master/tools
./scan_nodes.sh
```

**Output:**
- Console progress and discovery results
- `node_ip_mapping.txt` - Node to IP address mapping file

### 2. `mass_reconnect_nodes.sh`
**Purpose**: Automated mass node reconnection
**Description**: Reconnects all offline Jenkins nodes using JNLP secrets and SSH automation.

**Usage:**
```bash
cd /home/fosqa/jenkins-master/tools
./mass_reconnect_nodes.sh
```

**Prerequisites:**
- Jenkins master running at http://10.96.227.206:8080
- Valid node_ip_mapping.txt file (from scan_nodes.sh)
- SSH access to all nodes
- install_jenkins_agent.py available on remote nodes

## Workflow

1. **Discovery Phase**: Run `scan_nodes.sh` to map all nodes in the network
2. **Reconnection Phase**: Run `mass_reconnect_nodes.sh` to reconnect offline nodes

## Network Configuration

- **Jenkins Master**: 10.96.227.206:8080
- **Node Network**: 10.96.234.0/25 (IPs 1-126)
- **Node Pattern**: Hostname `all-in-one-nodeXX` maps to Jenkins `nodeXX`
- **SSH Credentials**: fosqa/ftnt123!
- **Jenkins API**: fosqa/110dec5c2d2974a67968074deafccc1414

## Troubleshooting

### Common Issues:
1. **"node_ip_mapping.txt not found"**: Run scan_nodes.sh first
2. **SSH connection failures**: Check network connectivity and credentials
3. **JNLP secret extraction fails**: Verify Jenkins API credentials and node configuration
4. **Agent installation fails**: Check install_jenkins_agent.py availability on remote nodes

### Logs:
- Scripts provide detailed console output
- Check Jenkins master logs at http://10.96.227.206:8080/log/all
- Check systemd logs on nodes: `systemctl status jenkins-agent`

## Author
GitHub Copilot & Fosqa Team
Version: 2.0 - September 2025
