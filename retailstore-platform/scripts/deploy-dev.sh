#!/bin/bash
# Deploy all RetailStore services to EKS (dev environment)
# Run from: retailstore-platform/scripts/
# Prereqs: kubectl configured, helm installed, ECR authenticated

set -e

NAMESPACE=${NAMESPACE:-default}
CHART_REPO=${CHART_REPO:-""}  # set to your ECR registry if using remote charts
ENV=dev

echo "================================================"
echo "  RetailStore — Deploy (env=$ENV, ns=$NAMESPACE)"
echo "================================================"

VALUES_DIR="$(dirname "$0")/../helm/$ENV"

deploy() {
  local name=$1
  local chart=$2
  local values=$3
  echo ""
  echo "── Deploying $name ──"
  helm upgrade --install "$name" "$chart" \
    -f "$VALUES_DIR/$values" \
    --namespace "$NAMESPACE" \
    --create-namespace \
    --wait \
    --timeout 5m
}

# Order matters — gateway deploys last
deploy catalog   oci://public.ecr.aws/retailstore/catalog-chart   catalog.yaml
deploy carts     oci://public.ecr.aws/retailstore/cart-chart      carts.yaml
deploy checkout  oci://public.ecr.aws/retailstore/checkout-chart  checkout.yaml
deploy orders    oci://public.ecr.aws/retailstore/order-chart     orders.yaml
deploy identity  oci://public.ecr.aws/retailstore/identity-chart  identity.yaml
deploy experience oci://public.ecr.aws/retailstore/experience-chart experience.yaml
deploy gateway   oci://public.ecr.aws/retailstore/gateway-chart   gateway.yaml

echo ""
echo "================================================"
echo "  All services deployed successfully"
echo "================================================"
kubectl get pods -n "$NAMESPACE"
