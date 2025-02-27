#!/usr/bin/env python3
"""
This script ensures that the parent directory (/home/fosqa) has permissions
that allow traversal by the Jenkins user.
It sets the permissions to 755 for /home/fosqa.
Run this script with sudo.
"""

import subprocess
import sys
import os

def set_parent_permissions(directory, mode):
    try:
        # Get current permissions
        current_mode = oct(os.stat(directory).st_mode)[-3:]
        print(f"Current permissions for {directory}: {current_mode}")
        
        # Change permissions to desired mode (e.g., '755')
        subprocess.check_call(["sudo", "chmod", mode, directory])
        print(f"Permissions for {directory} set to {mode}")
    except Exception as e:
        print("Error:", e)
        sys.exit(1)

if __name__ == "__main__":
    parent_dir = "/home/fosqa"
    desired_mode = "755"
    set_parent_permissions(parent_dir, desired_mode)
