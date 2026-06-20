#!/bin/bash
# start-local.sh — Start the full RetailStore stack on Docker Desktop k8s
#
# Runs: build → install-infra → deploy → port-forward
#
# Usage:
#   ./scripts/start-local.sh              # full start (build + infra + deploy)
#   ./scripts/start-local.sh --no-build   # skip build (use existing images)
#   ./scripts/start-local.sh --infra-only # install infra only (no service deploy)
#
# Prerequisites:
#   - Docker Desktop running with Kubernetes enabled
#   - kubectl context = docker-desktop
#   - helm installed (brew install helm)

set -euo pipefail
cd "$(dirname "$0")/.."

NO_BUILD=false
INFRA_ONLY=false

for arg in "$@"; do
  case "$arg" in
    --no-build)    NO_BUILD=true ;;
    --infra-only)  INFRA_ONLY=true ;;
  esac
done

# ── Preflight checks ──────────────────────────────────────────────────────────
echo "▶ Checking prerequisites..."

# Check kubectl
if ! command -v kubectl &>/dev/null; then
  echo "  ERROR: kubectl not found."
  echo "  Docker Desktop should have installed it. Check your PATH or install:"
  echo "    brew install kubectl"
  exit 1
fi

# Check helm
if ! command -v helm &>/dev/null; then
  echo "  ERROR: helm not found."
  echo "  Install: brew install helm"
  exit 1
fi

# Check docker (dockerd mode)
if ! docker info &>/dev/null; then
  echo "  ERROR: Docker daemon not reachable."
  echo "  Make sure Docker Desktop is running."
  exit 1
fi

# Check kubectl context
CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "none")
echo "  kubectl context: $CURRENT_CONTEXT"
if [[ "$CURRENT_CONTEXT" != "rancher-desktop" && "$CURRENT_CONTEXT" != "docker-desktop" ]]; then
  echo ""
  echo "  WARNING: context is not a local cluster. Switch with:"
  echo "    kubectl config use-context docker-desktop"
  read -p "  Continue with current context? [y/N] " -n 1 -r
  echo ""
  [[ $REPLY =~ ^[Yy]$ ]] || exit 1
fi

echo "  ✓ Prerequisites OK"
echo ""

# ── Step 1: Build images ──────────────────────────────────────────────────────
if [[ "$NO_BUILD" == "false" && "$INFRA_ONLY" == "false" ]]; then
  echo "▶ Step 1/3 — Building Docker images..."
  ./scripts/build-local.sh
  echo ""
else
  echo "▶ Step 1/3 — Skipping build (--no-build)"
  echo ""
fi

# ── Step 2: Install infrastructure ───────────────────────────────────────────
echo "▶ Step 2/3 — Installing infrastructure..."
./scripts/install-infra-local.sh
echo ""

if [[ "$INFRA_ONLY" == "true" ]]; then
  echo "  --infra-only set. Stopping here."
  exit 0
fi

# ── Step 3: Deploy services ───────────────────────────────────────────────────
echo "▶ Step 3/3 — Deploying services..."
./scripts/deploy-local.sh
echo ""

# ── Done ──────────────────────────────────────────────────────────────────────
echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║   RetailStore local environment is ready!            ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║                                                      ║"
echo "║  Start port-forwards (new terminal):                 ║"
echo "║    ./scripts/port-forward.sh                         ║"
echo "║                                                      ║"
echo "║  After port-forward, services are at:                ║"
echo "║    API Gateway  → http://localhost:8080              ║"
echo "║    Keycloak     → http://localhost:8180/admin        ║"
echo "║    Zipkin       → http://localhost:9411              ║"
echo "║    MySQL        → localhost:3306                     ║"
echo "║    Redis        → localhost:6379                     ║"
echo "║                                                      ║"
echo "║  Stop everything:  ./scripts/stop-local.sh           ║"
echo "╚══════════════════════════════════════════════════════╝"
