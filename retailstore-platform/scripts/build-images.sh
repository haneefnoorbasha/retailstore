#!/bin/bash
# Build and push all Docker images to ECR
# Usage: ./build-images.sh <ecr-registry> <tag>
# Example: ./build-images.sh 123456789.dkr.ecr.us-east-1.amazonaws.com sha-abc1234

set -e

REGISTRY=${1:?"Usage: $0 <ecr-registry> <image-tag>"}
TAG=${2:?"Usage: $0 <ecr-registry> <image-tag>"}

SERVICES=(
  "catalog-service:catalog"
  "cart-service:carts"
  "checkout-service:checkout"
  "order-service:orders"
"experience-service:experience"
  "api-gateway:gateway"
)

REPO_ROOT="$(dirname "$0")/../.."

echo "Building and pushing images (tag=$TAG)..."

for entry in "${SERVICES[@]}"; do
  dir="${entry%%:*}"
  name="${entry##*:}"
  image="$REGISTRY/retailstore/$name:$TAG"
  echo ""
  echo "── $name ──"
  docker build -t "$image" "$REPO_ROOT/$dir"
  docker push "$image"
  echo "✓ Pushed $image"
done

echo ""
echo "All images pushed. Update helm values with tag: $TAG"
