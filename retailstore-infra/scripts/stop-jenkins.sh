#!/bin/bash
set -e

if ! docker ps -a --format '{{.Names}}' | grep -q '^jenkins$'; then
    echo "Jenkins container not found."
    exit 0
fi

docker stop jenkins && docker rm jenkins
echo ""
echo "Jenkins stopped. All data preserved in 'jenkins_home' Docker volume."
echo "Run ./scripts/start-jenkins.sh to restart (resumes from saved state)."
echo ""
echo "To wipe all Jenkins data and start fresh:"
echo "  docker volume rm jenkins_home"
