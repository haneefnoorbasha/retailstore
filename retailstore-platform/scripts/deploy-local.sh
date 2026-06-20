#!/bin/bash
# deploy-local.sh — Deploy RetailStore services to local Docker Desktop k8s
#
# Uses locally built images (retailstore/<service>:local) — no registry needed.
# Run build-local.sh first to build the images.
#
# Usage:
#   ./scripts/deploy-local.sh           # deploy all services
#   ./scripts/deploy-local.sh catalog   # redeploy one service after rebuild
#
# Prerequisites:
#   - Docker Desktop running with Kubernetes enabled
#   - Images built: ./scripts/build-local.sh
#   - Infra running: ./scripts/install-infra-local.sh

set -euo pipefail
cd "$(dirname "$0")/.."

NAMESPACE="retailstore"
TAG="local"
TARGET="${1:-all}"

# Format: "helm-release:chart-dir:image-name"
# Deploy order: data services first, BFF and gateway last
SERVICES=(
  "catalog:catalog-service:catalog"
  "carts:cart-service:carts"
  "orders:order-service:orders"
  "checkout:checkout-service:checkout"
  "experience:experience-service:experience"
  "gateway:api-gateway:gateway"
)

deploy_service() {
  local entry="$1"
  local name="${entry%%:*}"
  local rest="${entry#*:}"
  local dir="${rest%%:*}"
  local image_name="${rest##*:}"
  local chart="../$dir/chart"
  local values="helm/local/$name.yaml"
  local image="retailstore/$image_name"

  echo "▶ Deploying $name..."
  helm upgrade --install "$name" "$chart" \
    -n "$NAMESPACE" \
    -f "$values" \
    --set image.repository="$image" \
    --set image.tag="$TAG" \
    --wait --timeout 3m
  echo "  ✓ $name deployed"
}

if [[ "$TARGET" == "all" ]]; then
  echo "Deploying all services (image tag=$TAG)..."
  echo ""
  for entry in "${SERVICES[@]}"; do
    deploy_service "$entry"
  done
else
  matched=""
  for entry in "${SERVICES[@]}"; do
    name="${entry%%:*}"
    if [[ "$name" == "$TARGET" ]]; then
      matched="$entry"
      break
    fi
  done
  if [[ -z "$matched" ]]; then
    echo "ERROR: Unknown service '$TARGET'"
    echo "  Valid names: catalog, carts, orders, checkout, experience, gateway"
    exit 1
  fi
  deploy_service "$matched"
fi

echo ""
echo "══════════════════════════════════════════════════════"
echo "  Services deployed."
echo ""
kubectl get pods -n "$NAMESPACE" \
  --field-selector=status.phase!=Succeeded \
  --sort-by=.metadata.name
echo ""
echo "  Next: start port-forwards in a new terminal:"
echo "    ./scripts/port-forward.sh"
echo "══════════════════════════════════════════════════════"
