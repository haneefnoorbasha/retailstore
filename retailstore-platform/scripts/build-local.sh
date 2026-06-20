#!/bin/bash
# build-local.sh — Build all RetailStore Docker images for local Docker Desktop k8s
#
# Builds and pushes to the local registry at localhost:5000.
# k8s pods pull from host.docker.internal:5000 (same registry, different hostname).
#
# Usage:
#   ./scripts/build-local.sh          # build all services
#   ./scripts/build-local.sh catalog  # build one service
#
# Prerequisites:
#   - Docker Desktop running with Kubernetes enabled
#   - Local registry running (started by install-infra-local.sh)
#   - Docker Engine insecure-registries includes host.docker.internal:5000

set -euo pipefail
cd "$(dirname "$0")/../.."

REGISTRY="localhost:5000"
TAG="local"
TARGET="${1:-all}"

# service-dir:helm-release-name:image-name
SERVICES=(
  "catalog-service:catalog:catalog"
  "cart-service:carts:carts"
  "checkout-service:checkout:checkout"
  "order-service:orders:orders"
  "experience-service:experience:experience"
  "api-gateway:gateway:gateway"
)

# Ensure registry is running
if ! curl -sf http://localhost:5000/v2/ >/dev/null 2>&1; then
  echo "▶ Starting local registry..."
  docker run -d -p 5000:5000 --name local-registry --restart=always registry:2 2>/dev/null || \
    docker start local-registry 2>/dev/null || true
  sleep 2
fi

build_service() {
  local entry="$1"
  local dir="${entry%%:*}"
  local rest="${entry#*:}"
  local name="${rest%%:*}"
  local image_name="${rest##*:}"
  local image="$REGISTRY/retailstore/$image_name:$TAG"

  echo "▶ Building $name → $image"
  docker build -t "$image" "./$dir"
  docker push "$image"
  echo "  ✓ $image built and pushed"
}

if [[ "$TARGET" == "all" ]]; then
  echo "Building all services (registry=$REGISTRY, tag=$TAG)..."
  echo ""
  for entry in "${SERVICES[@]}"; do
    build_service "$entry"
    echo ""
  done
else
  matched=""
  for entry in "${SERVICES[@]}"; do
    release="${entry#*:}"
    release="${release%%:*}"
    if [[ "$release" == "$TARGET" ]]; then
      matched="$entry"
      break
    fi
  done
  if [[ -z "$matched" ]]; then
    echo "ERROR: Unknown service '$TARGET'"
    echo "  Valid names: catalog, carts, checkout, orders, experience, gateway"
    exit 1
  fi
  build_service "$matched"
fi

echo ""
echo "══════════════════════════════════════════════════"
echo "  Build complete (registry=$REGISTRY, tag=$TAG)"
echo ""
echo "  Next: ./scripts/deploy-local.sh"
echo "══════════════════════════════════════════════════"
