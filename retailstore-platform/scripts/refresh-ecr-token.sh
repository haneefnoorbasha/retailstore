#!/bin/bash
# refresh-ecr-token.sh — Manually refresh the ECR image pull secret
#
# ECR tokens expire every 12 hours. The in-cluster CronJob (ecr-token-refresh)
# does this automatically every 6h. Run this manually if the CronJob hasn't
# fired yet or if pods are showing ImagePullBackOff errors.
#
# Usage: cd retailstore-platform && ./scripts/refresh-ecr-token.sh

set -euo pipefail

ECR_REGISTRY="067744548987.dkr.ecr.us-east-1.amazonaws.com"
AWS_REGION="us-east-1"
NAMESPACE="retailstore"

echo "▶ Refreshing ECR credentials in namespace $NAMESPACE..."
TOKEN=$(aws ecr get-login-password --region "$AWS_REGION")
kubectl create secret docker-registry ecr-pull-secret \
  --docker-server="$ECR_REGISTRY" \
  --docker-username=AWS \
  --docker-password="$TOKEN" \
  -n "$NAMESPACE" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "  ✓ ecr-pull-secret refreshed (valid for 12 hours)"
