#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

echo "Installing Ansible collections..."
ansible-galaxy collection install -r requirements.yml --upgrade

echo "Running playbook..."
ansible-playbook -i inventory.ini playbook.yml "$@"
