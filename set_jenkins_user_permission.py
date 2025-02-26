#!/usr/bin/env python3
"""
This script adjusts the ownership and permissions of a given directory (and all its subdirectories)
so that the jenkins user has the same level of access as the local user fosqa.
It assumes that the target directory is a Git repository that Jenkins will use locally.

Usage: sudo python3 set_permissions.py
"""

import subprocess
import sys

def update_permissions(target_dir):
    try:
        # Change ownership recursively: set owner to "jenkins" and group to "fosqa"
        subprocess.check_call(["sudo", "chown", "-R", "jenkins:fosqa", target_dir])
        print(f"Ownership set to jenkins:fosqa for {target_dir}")
        
        # Set permissions recursively to 775
        subprocess.check_call(["sudo", "chmod", "-R", "775", target_dir])
        print(f"Permissions set to 775 for {target_dir}")
    except subprocess.CalledProcessError as e:
        print("Error updating permissions:", e)
        sys.exit(1)

if __name__ == "__main__":
    # Set the target directory for your Jenkins master repository
    target_directory = "/home/fosqa/jenkins-master"
    update_permissions(target_directory)
