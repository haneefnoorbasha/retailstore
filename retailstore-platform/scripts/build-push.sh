#!/bin/bash
# build-push.sh — Build one or all services and push to ECR
#
# Usage:
#   ./scripts/build-push.sh                      # build all services, tag = git sha
#   ./scripts/build-push.sh catalog-service      # build one service
#   IMAGE_TAG=v1.2.3 ./scripts/build-push.sh     # custom tag
#
# Required env vars:
#   ECR_REGISTRY  — e.g. 123456789012.dkr.ecr.us-east-1.amazonaws.com
#   AWS_REGION    — e.g. us-east-1

set -euo pipefail
cd "$(dirname "$0")/../.."   # workspace root

ECR_REGISTRY="${ECR_REGISTRY:-}"
AWS_REGION="${AWS_REGION:-us-east-1}"
IMAGE_TAG="${IMAGE_TAG:-$(git rev-parse --short HEAD 2>/dev/null || echo "latest")}"
TARGET="${1:-all}"

if [[ -z "$ECR_REGISTRY" ]]; then
  echo "ERROR: ECR_REGISTRY is not set."
  echo "  Export: export ECR_REGISTRY=123456789012.dkr.ecr.us-east-1.amazonaws.com"
  exit 1
fi

# Map: service-directory → ecr-repo-name
declare -A SERVICES=(
  [catalog-service]="retailstore/catalog"
  [cart-service]="retailstore/carts"
  [checkout-service]="retailstore/checkout"
  [order-service]="retailstore/orders"
  [experience-service]="retailstore/experience"
  [api-gateway]="retailstore/gateway"
)

# Authenticate Docker to ECR
echo "▶ Authenticating to ECR..."
aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "$ECR_REGISTRY"

build_push() {
  local dir="$1"
  local repo="${SERVICES[$dir]}"
  local image="$ECR_REGISTRY/$repo:$IMAGE_TAG"

  echo ""
  echo "▶ Building $dir → $image"
  docker build -t "$image" "$dir" --platform linux/amd64
  docker push "$image"
  echo "  ✓ Pushed $image"

  # Also tag and push as 'latest'
  docker tag "$image" "$ECR_REGISTRY/$repo:latest"
  docker push "$ECR_REGISTRY/$repo:latest"
}

if [[ "$TARGET" == "all" ]]; then
  for dir in "${!SERVICES[@]}"; do
    build_push "$dir"
  done
else
  if [[ -z "${SERVICES[$TARGET]+_}" ]]; then
    echo "ERROR: Unknown service '$TARGET'. Valid: ${!SERVICES[*]}"
    exit 1
  fi
  build_push "$TARGET"
fi

echo ""
echo "══════════════════════════════════════════════════════"
echo "  Build complete. Image tag: $IMAGE_TAG"
echo ""
echo "  To deploy: IMAGE_TAG=$IMAGE_TAG ./retailstore-platform/scripts/deploy-services.sh"
echo "══════════════════════════════════════════════════════"
