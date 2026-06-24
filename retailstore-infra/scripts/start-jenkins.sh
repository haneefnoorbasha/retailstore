#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLATFORM_DIR="$(dirname "$SCRIPT_DIR")"

# Avoid starting a second instance
if docker ps --format '{{.Names}}' | grep -q '^jenkins$'; then
    echo "Jenkins is already running → http://localhost:8090"
    exit 0
fi

# Restart a stopped container (preserves jenkins_home volume data)
if docker ps -a --format '{{.Names}}' | grep -q '^jenkins$'; then
    echo "Restarting existing Jenkins container..."
    docker start jenkins
    echo "Jenkins restarted → http://localhost:8090"
    exit 0
fi

echo "Building Jenkins image (first run — this takes ~5 min to download plugins)..."
docker build -t jenkins-retailstore:latest "$PLATFORM_DIR/jenkins"

echo ""
echo "Starting Jenkins container..."
docker run -d \
    --name jenkins \
    --restart unless-stopped \
    -p 8090:8080 \
    -p 50000:50000 \
    -v jenkins_home:/var/jenkins_home \
    -v /var/run/docker.sock:/var/run/docker.sock \
    jenkins-retailstore:latest

echo ""
echo "Jenkins starting at http://localhost:8090"
echo ""
echo "First-time setup:"
echo "  1. Wait ~60s for Jenkins to finish starting"
echo "  2. Run this to get the initial admin password:"
echo "       docker exec jenkins cat /var/jenkins_home/secrets/initialAdminPassword"
echo "  3. Open http://localhost:8090 → paste the password"
echo "  4. Click 'Install suggested plugins' → skip (plugins already pre-installed)"
echo "  5. Create your admin user → Save and Finish"
