#!/bin/bash

# ==============================================================================
# Jenkins Node Network Scanner
# ==============================================================================
#
# DESCRIPTION:
#   Scans the 10.96.234.0/25 network segment to discover Jenkins slave nodes
#   by checking SSH connectivity and hostname patterns. Maps node names to IP
#   addresses for use with Jenkins automation scripts.
#
# USAGE:
#   ./scan_nodes.sh
#
# OUTPUT:
#   - Console output showing discovery progress
#   - node_ip_mapping.txt file with node:ip mappings
#
# REQUIREMENTS:
#   - SSH access to target nodes with fosqa/ftnt123! credentials
#   - Network connectivity to 10.96.234.0/25 segment
#   - ping and sshpass commands available
#
# AUTHOR:
#   GitHub Copilot & Fosqa Team
#
# VERSION:
#   1.0 - Initial implementation for 50-node Jenkins cluster
# ==============================================================================

# Network configuration
NETWORK="10.96.234"
START_IP=1
END_IP=126
NODE_USER="fosqa"
NODE_PASSWORD="ftnt123!"

echo "=== Jenkins Node Network Scanner v1.0 ==="
echo "Scanning 10.96.234.0/25 for Jenkins nodes..."
echo "This may take a few minutes..."
echo ""

# Create arrays to store results
declare -A node_ip_map
alive_ips=()

echo "Step 1: Discovering alive IPs in network segment..."
for i in $(seq $START_IP $END_IP); do
    ip="${NETWORK}.$i"
    if ping -c 1 -W 1 "$ip" >/dev/null 2>&1; then
        alive_ips+=("$ip")
        echo "  ✅ $ip is alive"
    fi
done

echo ""
echo "Found ${#alive_ips[@]} alive IPs in network segment"
echo ""

echo "Step 2: Identifying Jenkins nodes by hostname pattern..."
for ip in "${alive_ips[@]}"; do
    echo "Checking $ip for Jenkins node hostname..."

    # Try to get hostname via SSH
    hostname=$(timeout 10 sshpass -p "$NODE_PASSWORD" ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5 "$NODE_USER@$ip" "hostname" 2>/dev/null)

    if [ $? -eq 0 ] && [ -n "$hostname" ]; then
        echo "  Hostname: $hostname"

        # Check if hostname matches all-in-one-nodeXX pattern
        if [[ "$hostname" =~ ^all-in-one-node([0-9]+)$ ]]; then
            node_num="${BASH_REMATCH[1]}"
            node_name="node$node_num"
            node_ip_map["$node_name"]="$ip"
            echo "  ✅ Mapped: $node_name -> $ip"
        else
            echo "  ℹ️  Hostname doesn't match node pattern: $hostname"
        fi
    else
        # Try alternative method - check /etc/hostname via SSH
        hostname=$(timeout 10 sshpass -p "$NODE_PASSWORD" ssh -o StrictHostKeyChecking=no -o UserKnownHostsFile=/dev/null -o ConnectTimeout=5 "$NODE_USER@$ip" "cat /etc/hostname 2>/dev/null" 2>/dev/null)

        if [ $? -eq 0 ] && [ -n "$hostname" ]; then
            echo "  Hostname (from /etc/hostname): $hostname"

            if [[ "$hostname" =~ ^all-in-one-node([0-9]+)$ ]]; then
                node_num="${BASH_REMATCH[1]}"
                node_name="node$node_num"
                node_ip_map["$node_name"]="$ip"
                echo "  ✅ Mapped: $node_name -> $ip"
            else
                echo "  ℹ️  Hostname doesn't match node pattern: $hostname"
            fi
        else
            echo "  ❌ Could not get hostname for $ip"
        fi
    fi

    sleep 1
done

echo ""
echo "=== Discovery Results ==="
if [ ${#node_ip_map[@]} -eq 0 ]; then
    echo "❌ No Jenkins nodes found with all-in-one-nodeXX hostname pattern"
    exit 1
else
    echo "✅ Found ${#node_ip_map[@]} Jenkins nodes:"
    echo ""

    # Sort by node number for better readability
    for node_name in $(printf '%s\n' "${!node_ip_map[@]}" | sort -V); do
        echo "  $node_name -> ${node_ip_map[$node_name]}"
    done

    echo ""
    echo "=== Saving Results ==="
    # Save to file for later use by other scripts
    mapping_file="node_ip_mapping.txt"
    > "$mapping_file"
    for node_name in $(printf '%s\n' "${!node_ip_map[@]}" | sort -V); do
        echo "$node_name:${node_ip_map[$node_name]}" >> "$mapping_file"
    done
    echo "✅ Node mapping saved to: $mapping_file"
fi

echo ""
echo "=== Scan completed successfully! ==="
echo "Use the mapping file with mass_reconnect_nodes.sh to reconnect nodes."
