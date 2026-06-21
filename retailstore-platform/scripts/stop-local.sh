#!/bin/bash
# stop-local.sh — Stop the RetailStore local environment
#
# Usage:
#   ./scripts/stop-local.sh            # uninstall services + infra (keep namespace)
#   ./scripts/stop-local.sh --full     # also delete the namespace (clean slate)
#   ./scripts/stop-local.sh --services # uninstall services only (keep infra running)
#
# Note: This does NOT stop Docker Desktop itself.
# To pause resource usage, quit Docker Desktop from the menu bar.

set -euo pipefail
cd "$(dirname "$0")/.."

NAMESPACE="retailstore"
FULL=false
SERVICES_ONLY=false

for arg in "$@"; do
  case "$arg" in
    --full)     FULL=true ;;
    --services) SERVICES_ONLY=true ;;
  esac
done

SERVICES=(gateway experience catalog carts checkout orders)

echo "▶ Stopping port-forwards (if any)..."
./scripts/port-forward.sh stop 2>/dev/null || true
echo ""

echo "▶ Uninstalling services..."
for svc in "${SERVICES[@]}"; do
  if helm status "$svc" -n "$NAMESPACE" &>/dev/null; then
    helm uninstall "$svc" -n "$NAMESPACE"
    echo "  ✓ $svc removed"
  fi
done
echo ""

if [[ "$SERVICES_ONLY" == "true" ]]; then
  echo "  --services only: infrastructure left running."
  exit 0
fi

echo "▶ Uninstalling infrastructure..."
kubectl delete -f k8s/local/keycloak.yaml --ignore-not-found
kubectl delete -f k8s/local/redis.yaml --ignore-not-found
kubectl delete -f k8s/local/mysql.yaml --ignore-not-found
kubectl delete -f k8s/local/kafka.yaml --ignore-not-found
kubectl delete deployment zipkin dynamodb-local -n "$NAMESPACE" --ignore-not-found
kubectl delete service zipkin dynamodb-local -n "$NAMESPACE" --ignore-not-found
kubectl delete configmap keycloak-realm -n "$NAMESPACE" --ignore-not-found
kubectl delete pvc -l app=mysql -n "$NAMESPACE" --ignore-not-found
echo "  ✓ Infrastructure removed"

echo ""
if [[ "$FULL" == "true" ]]; then
  echo "▶ Deleting namespace $NAMESPACE..."
  kubectl delete namespace "$NAMESPACE" --ignore-not-found
  echo "  ✓ Namespace deleted — clean slate"
else
  echo "  Namespace '$NAMESPACE' kept."
  echo "  Run with --full to delete it completely."
fi

echo ""
echo "  Done. Docker Desktop is still running."
echo "  To restart: ./scripts/start-local.sh --no-build"
