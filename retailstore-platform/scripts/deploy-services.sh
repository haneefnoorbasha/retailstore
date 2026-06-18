#!/bin/bash
# deploy-services.sh — Helm install/upgrade all RetailStore microservices
#
# Usage:
#   ./scripts/deploy-services.sh                       # deploy all services
#   ./scripts/deploy-services.sh catalog               # deploy one service
#
# Required env vars:
#   ECR_REGISTRY  — e.g. 123456789012.dkr.ecr.us-east-1.amazonaws.com
#   IMAGE_TAG     — e.g. abc1234 (git sha) or "latest"

set -euo pipefail
cd "$(dirname "$0")/.."

NAMESPACE="retailstore"
ENV="dev"
ECR_REGISTRY="${ECR_REGISTRY:-}"
IMAGE_TAG="${IMAGE_TAG:-latest}"
TARGET="${1:-all}"

if [[ -z "$ECR_REGISTRY" ]]; then
  echo "ERROR: ECR_REGISTRY is not set."
  echo "  Export: export ECR_REGISTRY=123456789012.dkr.ecr.us-east-1.amazonaws.com"
  exit 1
fi

# Map: helm-release-name → service-dir-name → ecr-repo-name
declare -A SERVICE_DIRS=(
  [catalog]="catalog-service"
  [carts]="cart-service"
  [checkout]="checkout-service"
  [orders]="order-service"
  [experience]="experience-service"
  [gateway]="api-gateway"
)

declare -A ECR_REPOS=(
  [catalog]="retailstore/catalog"
  [carts]="retailstore/carts"
  [checkout]="retailstore/checkout"
  [orders]="retailstore/orders"
  [experience]="retailstore/experience"
  [gateway]="retailstore/gateway"
)

deploy_service() {
  local name="$1"
  local dir="${SERVICE_DIRS[$name]}"
  local repo="${ECR_REPOS[$name]}"
  local chart="../../$dir/chart"
  local values="helm/$ENV/$name.yaml"

  echo "▶ Deploying $name..."
  helm upgrade --install "$name" "$chart" \
    -n "$NAMESPACE" \
    -f "$values" \
    --set image.repository="$ECR_REGISTRY/$repo" \
    --set image.tag="$IMAGE_TAG" \
    --wait --timeout 5m
  echo "  ✓ $name deployed"
}

# Deploy order: infra-dependent services first, gateway last
DEPLOY_ORDER=(catalog carts orders checkout experience gateway)

if [[ "$TARGET" == "all" ]]; then
  for svc in "${DEPLOY_ORDER[@]}"; do
    deploy_service "$svc"
  done
else
  deploy_service "$TARGET"
fi

echo ""
echo "  Deployed with image tag: $IMAGE_TAG"
kubectl get pods -n "$NAMESPACE" --field-selector=status.phase!=Succeeded
