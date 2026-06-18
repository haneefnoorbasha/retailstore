#!/bin/bash
# port-forward.sh — Forward dev infra ports to localhost for IntelliJ debugging
#
# After running this, IntelliJ services use SPRING_PROFILES_ACTIVE=dev
# and connect to Docker Compose / k8s infra via localhost.
#
# Usage: ./scripts/port-forward.sh [start|stop]
#
# Port mappings (same as docker-compose port bindings):
#   MySQL       localhost:3306  → mysql:3306
#   Redis       localhost:6379  → redis-master:6379
#   Keycloak    localhost:8180  → keycloak:8180
#   DynamoDB    localhost:8000  → dynamodb-local:8000
#   LocalStack  localhost:4566  → localstack:4566
#   Zipkin      localhost:9411  → zipkin:9411
#   Grafana     localhost:3001  → grafana:3000      (if installed)

set -euo pipefail

NAMESPACE="retailstore"
PF_PIDFILE="/tmp/retailstore-port-forwards.pids"

start_forwards() {
  echo "▶ Starting port forwards (namespace: $NAMESPACE)..."

  # Clean up any stale forwarded ports
  stop_forwards 2>/dev/null || true
  > "$PF_PIDFILE"

  fwd() {
    local local_port="$1"
    local resource="$2"
    local remote_port="$3"
    local label="$4"
    kubectl port-forward "$resource" "$local_port:$remote_port" -n "$NAMESPACE" > /dev/null 2>&1 &
    echo "$!" >> "$PF_PIDFILE"
    echo "  $label → localhost:$local_port"
  }

  fwd 3306 svc/mysql              3306 "MySQL    "
  fwd 6379 svc/redis-master       6379 "Redis    "
  fwd 8180 svc/keycloak           8180 "Keycloak "
  fwd 8000 svc/dynamodb-local     8000 "DynamoDB "
  fwd 4566 svc/localstack         4566 "LocalStack"
  fwd 9411 svc/zipkin             9411 "Zipkin   "

  echo ""
  echo "  All ports forwarded. PIDs stored in $PF_PIDFILE"
  echo "  Run IntelliJ service with VM arg: -DSPRING_PROFILES_ACTIVE=dev"
  echo "  Stop with: ./scripts/port-forward.sh stop"
  echo ""
  echo "  Press Ctrl+C to stop all forwards."
  wait
}

stop_forwards() {
  if [[ -f "$PF_PIDFILE" ]]; then
    echo "▶ Stopping port forwards..."
    while IFS= read -r pid; do
      kill "$pid" 2>/dev/null || true
    done < "$PF_PIDFILE"
    rm -f "$PF_PIDFILE"
    echo "  Done."
  else
    echo "  No active port forwards found."
  fi
}

case "${1:-start}" in
  start) start_forwards ;;
  stop)  stop_forwards  ;;
  *)     echo "Usage: $0 [start|stop]"; exit 1 ;;
esac
