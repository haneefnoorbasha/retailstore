#!/bin/bash
# install-infra.sh — Install all infrastructure Helm charts and k8s manifests
# Called by start-dev.sh. Can also be run standalone after export KUBECONFIG=...
#
# Usage: ./scripts/install-infra.sh

set -euo pipefail
cd "$(dirname "$0")/.."

NAMESPACE="retailstore"
REALM_JSON="./keycloak/realms/retailstore-realm.json"

# Add Helm repos (idempotent)
helm repo add bitnami https://charts.bitnami.com/bitnami 2>/dev/null || true
helm repo update bitnami 2>/dev/null || true

echo "▶ Creating namespace..."
kubectl apply -f k8s/dev/namespace.yaml

# ── MySQL ─────────────────────────────────────────────────────────────────────
echo "▶ Installing MySQL..."
helm upgrade --install mysql bitnami/mysql \
  --version 11.1.14 \
  -n "$NAMESPACE" \
  -f helm/infra/mysql-values.yaml \
  --wait --timeout 5m

# ── Redis ─────────────────────────────────────────────────────────────────────
echo "▶ Installing Redis..."
helm upgrade --install redis bitnami/redis \
  --version 20.1.7 \
  -n "$NAMESPACE" \
  -f helm/infra/redis-values.yaml \
  --wait --timeout 3m

# ── DynamoDB Local ────────────────────────────────────────────────────────────
echo "▶ Installing DynamoDB Local..."
kubectl apply -f k8s/dev/dynamodb-local.yaml -n "$NAMESPACE"
kubectl wait --for=condition=available deployment/dynamodb-local -n "$NAMESPACE" --timeout=60s

# ── LocalStack (SQS) ─────────────────────────────────────────────────────────
echo "▶ Installing LocalStack..."
kubectl apply -f k8s/dev/localstack.yaml -n "$NAMESPACE"
kubectl wait --for=condition=available deployment/localstack -n "$NAMESPACE" --timeout=90s

# ── Zipkin ────────────────────────────────────────────────────────────────────
echo "▶ Installing Zipkin..."
kubectl apply -f k8s/dev/zipkin.yaml -n "$NAMESPACE"

# ── Keycloak realm ConfigMap ──────────────────────────────────────────────────
echo "▶ Creating Keycloak realm ConfigMap..."
kubectl create configmap keycloak-realm \
  --from-file=retailstore-realm.json="$REALM_JSON" \
  -n "$NAMESPACE" \
  --dry-run=client -o yaml | kubectl apply -f -

# ── Keycloak ──────────────────────────────────────────────────────────────────
echo "▶ Installing Keycloak (this takes ~2 min for first run)..."
helm upgrade --install keycloak bitnami/keycloak \
  --version 22.2.1 \
  -n "$NAMESPACE" \
  -f helm/infra/keycloak-values.yaml \
  --wait --timeout 10m

echo ""
echo "  Infrastructure ready."
echo "  MySQL    → mysql:3306"
echo "  Redis    → redis-master:6379"
echo "  DynamoDB → dynamodb-local:8000"
echo "  LocalStack → localstack:4566"
echo "  Keycloak → keycloak:8180"
echo "  Zipkin   → zipkin:9411"
