#!/usr/bin/env python3
"""
This script updates the ownership and permissions of a target directory so that the owner
is set to fosqa (both user and group). This is useful if you want to revert from having the
directory owned by jenkins:fosqa to fosqa:fosqa.

It will:
  - Change ownership recursively to fosqa:fosqa
  - Set permissions recursively to 755

Run this script with sudo.

Usage: sudo python3 set_fosqa_owner.py
"""

import subprocess
import sys
import os

def update_ownership(directory, owner_group):
    try:
        subprocess.check_call(["sudo", "chown", "-R", owner_group, directory])
        print(f"Ownership set to {owner_group} for {directory}")
    except subprocess.CalledProcessError as e:
        print("Error updating ownership:", e)
        sys.exit(1)

def update_permissions(directory, mode):
    try:
        subprocess.check_call(["sudo", "chmod", "-R", mode, directory])
        print(f"Permissions set to {mode} for {directory}")
    except subprocess.CalledProcessError as e:
        print("Error updating permissions:", e)
        sys.exit(1)

if __name__ == "__main__":
    target_directory = "/home/fosqa/jenkins-master"
    desired_owner = "fosqa:fosqa"
    desired_mode = "755"
    
    update_ownership(target_directory, desired_owner)
    update_permissions(target_directory, desired_mode)
