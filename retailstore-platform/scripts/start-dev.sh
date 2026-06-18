#!/bin/bash
# start-dev.sh — Start the RetailStore dev environment on EC2/k3s
#
# Prerequisites:
#   - AWS CLI configured with access to manage EC2
#   - SSH key for EC2 access
#   - helm, kubectl installed locally
#
# Usage: ./scripts/start-dev.sh
#
# Configure the variables below or export them before running.

set -euo pipefail
cd "$(dirname "$0")/.."

# ── Configuration ─────────────────────────────────────────────────────────────
EC2_INSTANCE_ID="${EC2_INSTANCE_ID:-}"          # Replace: your EC2 instance ID (i-xxxxxxxxxxxxxxxxx)
EC2_SSH_KEY="${EC2_SSH_KEY:-~/.ssh/dev-key.pem}"  # Replace: path to your EC2 SSH private key
EC2_USER="${EC2_USER:-ec2-user}"                # Amazon Linux: ec2-user  |  Ubuntu: ubuntu
AWS_REGION="${AWS_REGION:-us-east-1}"
NAMESPACE="retailstore"
KUBECONFIG_DEV="${KUBECONFIG_DEV:-$HOME/.kube/config-dev-k3s}"
ECR_REGISTRY="${ECR_REGISTRY:-}"               # Replace: 123456789012.dkr.ecr.us-east-1.amazonaws.com

if [[ -z "$EC2_INSTANCE_ID" ]]; then
  echo "ERROR: EC2_INSTANCE_ID is not set. Edit start-dev.sh or export the variable."
  exit 1
fi

# ── Step 1: Start EC2 ─────────────────────────────────────────────────────────
echo "▶ Starting EC2 instance $EC2_INSTANCE_ID..."
aws ec2 start-instances --instance-ids "$EC2_INSTANCE_ID" --region "$AWS_REGION" > /dev/null

echo "  Waiting for instance to enter running state..."
aws ec2 wait instance-running --instance-ids "$EC2_INSTANCE_ID" --region "$AWS_REGION"

EC2_PUBLIC_IP=$(aws ec2 describe-instances \
  --instance-ids "$EC2_INSTANCE_ID" \
  --region "$AWS_REGION" \
  --query 'Reservations[0].Instances[0].PublicIpAddress' \
  --output text)
echo "  EC2 running at: $EC2_PUBLIC_IP"

# ── Step 2: Wait for SSH ──────────────────────────────────────────────────────
echo "▶ Waiting for SSH..."
until ssh -o StrictHostKeyChecking=no -o ConnectTimeout=5 \
  -i "$EC2_SSH_KEY" "$EC2_USER@$EC2_PUBLIC_IP" "echo ok" > /dev/null 2>&1; do
  sleep 5
done
echo "  SSH ready"

# ── Step 3: Fetch kubeconfig ──────────────────────────────────────────────────
echo "▶ Fetching kubeconfig from k3s..."
ssh -o StrictHostKeyChecking=no -i "$EC2_SSH_KEY" "$EC2_USER@$EC2_PUBLIC_IP" \
  "sudo cat /etc/rancher/k3s/k3s.yaml" \
  | sed "s/127.0.0.1/$EC2_PUBLIC_IP/g" > "$KUBECONFIG_DEV"
chmod 600 "$KUBECONFIG_DEV"
export KUBECONFIG="$KUBECONFIG_DEV"
echo "  kubeconfig saved to $KUBECONFIG_DEV"

# ── Step 4: Install infra ─────────────────────────────────────────────────────
echo "▶ Installing infrastructure..."
./scripts/install-infra.sh

# ── Step 5: Install services ──────────────────────────────────────────────────
echo "▶ Installing services..."
./scripts/deploy-services.sh

echo ""
echo "══════════════════════════════════════════════════════"
echo "  Dev environment ready"
echo "  EC2 public IP: $EC2_PUBLIC_IP"
echo ""
echo "  To connect from IntelliJ:"
echo "    export KUBECONFIG=$KUBECONFIG_DEV"
echo "    ./scripts/port-forward.sh"
echo ""
echo "  To stop: ./scripts/stop-dev.sh"
echo "══════════════════════════════════════════════════════"
