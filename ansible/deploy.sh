#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")"

echo "Installing Ansible collections..."
ansible-galaxy collection install -r requirements.yml --upgrade

# Backend only: ansible-playbook -i inventory.ini app.yml -e "ansible_become_password=..."
# PostgreSQL only: ansible-playbook -i inventory.ini postgres.yml -e "ansible_become_password=..."
echo "Running playbook..."
ansible-playbook -i inventory.ini playbook.yml "$@"
