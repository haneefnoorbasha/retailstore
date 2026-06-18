#!/bin/bash
# stop-dev.sh — Tear down the dev environment and stop EC2 to save cost
#
# Usage: ./scripts/stop-dev.sh

set -euo pipefail
cd "$(dirname "$0")/.."

EC2_INSTANCE_ID="${EC2_INSTANCE_ID:-}"
AWS_REGION="${AWS_REGION:-us-east-1}"
NAMESPACE="retailstore"
KUBECONFIG_DEV="${KUBECONFIG_DEV:-$HOME/.kube/config-dev-k3s}"

if [[ -z "$EC2_INSTANCE_ID" ]]; then
  echo "ERROR: EC2_INSTANCE_ID is not set."
  exit 1
fi

export KUBECONFIG="$KUBECONFIG_DEV"

echo "▶ Uninstalling services..."
for svc in gateway experience checkout orders carts catalog; do
  helm uninstall "$svc" -n "$NAMESPACE" --ignore-not-found 2>/dev/null && echo "  uninstalled $svc" || true
done

echo "▶ Uninstalling infrastructure..."
for infra in keycloak redis mysql; do
  helm uninstall "$infra" -n "$NAMESPACE" --ignore-not-found 2>/dev/null && echo "  uninstalled $infra" || true
done
kubectl delete -f k8s/dev/zipkin.yaml --ignore-not-found -n "$NAMESPACE" 2>/dev/null || true
kubectl delete -f k8s/dev/localstack.yaml --ignore-not-found -n "$NAMESPACE" 2>/dev/null || true
kubectl delete -f k8s/dev/dynamodb-local.yaml --ignore-not-found -n "$NAMESPACE" 2>/dev/null || true

echo "▶ Stopping EC2 instance $EC2_INSTANCE_ID..."
aws ec2 stop-instances --instance-ids "$EC2_INSTANCE_ID" --region "$AWS_REGION" > /dev/null
echo "  Instance stopping. You will not be billed for compute while stopped."
echo "  Note: EBS volumes and Elastic IPs still incur minor charges."
