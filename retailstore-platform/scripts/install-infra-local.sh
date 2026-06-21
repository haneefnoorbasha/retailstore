#!/bin/bash
# install-infra-local.sh — Install infrastructure for local Docker Desktop k8s
#
# Installs: MySQL, Redis, DynamoDB Local, Kafka, Zipkin, Keycloak
# Uses plain k8s manifests (k8s/local/) — no Helm/OCI auth required.
#
# Usage: ./scripts/install-infra-local.sh
#
# Prerequisites:
#   - Docker Desktop running with Kubernetes enabled, kubectl context = docker-desktop
#   - helm installed (brew install helm)  ← only needed for service deploys, not infra

set -euo pipefail
cd "$(dirname "$0")/.."

NAMESPACE="retailstore"
REALM_JSON="./keycloak/realms/retailstore-realm.json"

# Verify kubectl is pointing at a local cluster
CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "none")
if [[ "$CURRENT_CONTEXT" != "rancher-desktop" && "$CURRENT_CONTEXT" != "docker-desktop" ]]; then
  echo "⚠  Current kubectl context is: $CURRENT_CONTEXT"
  echo "   Expected: docker-desktop"
  echo ""
  echo "   Switch with: kubectl config use-context docker-desktop"
  read -p "   Continue anyway? [y/N] " -n 1 -r
  echo ""
  [[ $REPLY =~ ^[Yy]$ ]] || exit 1
fi

# ── Local Registry ────────────────────────────────────────────────────────────
echo "▶ Starting local registry..."
docker run -d -p 5000:5000 --name local-registry --restart=always registry:2 2>/dev/null || \
  docker start local-registry 2>/dev/null || true
echo "  ✓ Registry at localhost:5000 (k8s pulls from host.docker.internal:5000)"

echo "▶ Creating namespace..."
kubectl apply -f k8s/local/namespace.yaml

# ── MySQL ─────────────────────────────────────────────────────────────────────
echo "▶ Installing MySQL..."
kubectl apply -f k8s/local/mysql.yaml
kubectl wait --for=condition=ready pod -l app=mysql \
  -n "$NAMESPACE" --timeout=3m
echo "  ✓ MySQL ready (mysql:3306)"

# ── Redis ─────────────────────────────────────────────────────────────────────
echo "▶ Installing Redis..."
kubectl apply -f k8s/local/redis.yaml
kubectl wait --for=condition=ready pod -l app=redis \
  -n "$NAMESPACE" --timeout=2m
echo "  ✓ Redis ready (redis-master:6379)"

# ── DynamoDB Local ────────────────────────────────────────────────────────────
echo "▶ Installing DynamoDB Local..."
kubectl apply -f k8s/dev/dynamodb-local.yaml -n "$NAMESPACE"
kubectl wait --for=condition=available deployment/dynamodb-local \
  -n "$NAMESPACE" --timeout=2m
echo "  ✓ DynamoDB Local ready (dynamodb-local:8000)"

# ── Kafka (KRaft) ────────────────────────────────────────────────────────────
echo "▶ Installing Kafka..."
kubectl apply -f k8s/local/kafka.yaml
kubectl wait --for=condition=available deployment/kafka \
  -n "$NAMESPACE" --timeout=3m
echo "  ✓ Kafka ready (kafka:9092)"

# ── Zipkin ────────────────────────────────────────────────────────────────────
echo "▶ Installing Zipkin..."
kubectl apply -f k8s/dev/zipkin.yaml -n "$NAMESPACE"
kubectl wait --for=condition=available deployment/zipkin \
  -n "$NAMESPACE" --timeout=2m
echo "  ✓ Zipkin ready (zipkin:9411)"

# ── Keycloak realm ConfigMap ──────────────────────────────────────────────────
echo "▶ Creating Keycloak realm ConfigMap..."
kubectl create configmap keycloak-realm \
  --from-file=retailstore-realm.json="$REALM_JSON" \
  -n "$NAMESPACE" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "  ✓ Realm ConfigMap created"

# ── Keycloak ──────────────────────────────────────────────────────────────────
echo "▶ Installing Keycloak (first run takes 3-5 min)..."
kubectl apply -f k8s/local/keycloak.yaml
kubectl wait --for=condition=ready pod -l app=keycloak \
  -n "$NAMESPACE" --timeout=10m
echo "  ✓ Keycloak ready (keycloak:8180)"

echo ""
echo "══════════════════════════════════════════════════════"
echo "  Infrastructure ready in namespace: $NAMESPACE"
echo ""
echo "  Internal cluster addresses:"
echo "    MySQL      → mysql:3306"
echo "    Redis      → redis-master:6379"
echo "    DynamoDB   → dynamodb-local:8000"
echo "    Kafka      → kafka:9092"
echo "    Keycloak   → keycloak:8180"
echo "    Zipkin     → zipkin:9411"
echo ""
echo "  Next: ./scripts/deploy-local.sh"
echo "══════════════════════════════════════════════════════"
