#!/bin/bash
# setup-argocd-local.sh — One-time setup of ArgoCD on Docker Desktop K8s for local GitOps
#
# Run this once. After this, every Jenkins pipeline push to helm/local/*.yaml
# is automatically picked up and deployed by ArgoCD — no manual helm commands needed.
#
# Prerequisites:
#   - Docker Desktop running with Kubernetes enabled, context = docker-desktop
#   - AWS CLI configured (aws configure) — needed to create the ECR pull secret
#   - helm installed (brew install helm)
#
# Usage: cd retailstore-platform && ./scripts/setup-argocd-local.sh

set -euo pipefail
cd "$(dirname "$0")/.."

NAMESPACE="retailstore"
ARGOCD_NS="argocd"
ECR_REGISTRY="067744548987.dkr.ecr.us-east-1.amazonaws.com"
AWS_REGION="us-east-1"

# ── Preflight ──────────────────────────────────────────────────────────────────
CURRENT_CONTEXT=$(kubectl config current-context 2>/dev/null || echo "none")
if [[ "$CURRENT_CONTEXT" != "docker-desktop" && "$CURRENT_CONTEXT" != "rancher-desktop" ]]; then
  echo "ERROR: kubectl context is '$CURRENT_CONTEXT', expected docker-desktop"
  echo "  Switch: kubectl config use-context docker-desktop"
  exit 1
fi
echo "▶ kubectl context: $CURRENT_CONTEXT ✓"

# ── Step 1: Install ArgoCD ────────────────────────────────────────────────────
echo ""
echo "▶ Step 1/5 — Installing ArgoCD..."
kubectl create namespace "$ARGOCD_NS" --dry-run=client -o yaml | kubectl apply -f -
kubectl apply -n "$ARGOCD_NS" \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
echo "  Waiting for argocd-server to be ready (may take 2-3 min)..."
kubectl wait --for=condition=available deployment/argocd-server \
  -n "$ARGOCD_NS" --timeout=5m
echo "  ✓ ArgoCD installed"

# ── Step 2: Create ECR pull secret ───────────────────────────────────────────
echo ""
echo "▶ Step 2/5 — Creating ECR image pull secret..."
kubectl create namespace "$NAMESPACE" --dry-run=client -o yaml | kubectl apply -f -
TOKEN=$(aws ecr get-login-password --region "$AWS_REGION")
kubectl create secret docker-registry ecr-pull-secret \
  --docker-server="$ECR_REGISTRY" \
  --docker-username=AWS \
  --docker-password="$TOKEN" \
  -n "$NAMESPACE" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "  ✓ ecr-pull-secret created in namespace $NAMESPACE"

# ── Step 3: Create AWS credentials secret (for ECR refresh CronJob) ──────────
echo ""
echo "▶ Step 3/5 — Creating aws-credentials-local secret..."
echo ""
echo "  Enter your AWS credentials for the ECR token refresh CronJob."
echo "  These are stored in a K8s Secret in the cluster — never committed to git."
echo ""
read -rp "  AWS_ACCESS_KEY_ID: " AWS_KEY_ID
read -rsp "  AWS_SECRET_ACCESS_KEY: " AWS_SECRET
echo ""
kubectl create secret generic aws-credentials-local \
  --from-literal=AWS_ACCESS_KEY_ID="$AWS_KEY_ID" \
  --from-literal=AWS_SECRET_ACCESS_KEY="$AWS_SECRET" \
  -n "$NAMESPACE" \
  --dry-run=client -o yaml | kubectl apply -f -
echo "  ✓ aws-credentials-local secret created"

# ── Step 4: Apply ArgoCD AppProject + ECR refresh CronJob ────────────────────
echo ""
echo "▶ Step 4/5 — Applying AppProject and ECR refresh CronJob..."
kubectl apply -f argocd/local/project.yaml
kubectl apply -f argocd/local/ecr-token-refresh.yaml
echo "  ✓ AppProject retailstore-local and ecr-credentials-refresh CronJob created"

# ── Step 5: Apply ArgoCD Application manifests ────────────────────────────────
echo ""
echo "▶ Step 5/5 — Applying ArgoCD Application manifests..."
for app in catalog carts checkout orders experience gateway; do
  kubectl apply -f "argocd/local/$app.yaml"
  echo "  ✓ $app Application created"
done

# ── Done ──────────────────────────────────────────────────────────────────────
ARGOCD_PASSWORD=$(kubectl -n argocd get secret argocd-initial-admin-secret \
  -o jsonpath="{.data.password}" 2>/dev/null | base64 -d 2>/dev/null || echo "<not available yet>")

echo ""
echo "╔══════════════════════════════════════════════════════╗"
echo "║   ArgoCD local setup complete!                       ║"
echo "╠══════════════════════════════════════════════════════╣"
echo "║                                                      ║"
echo "║  Access the ArgoCD UI:                               ║"
echo "║    kubectl port-forward svc/argocd-server            ║"
echo "║      -n argocd 8443:443                              ║"
echo "║    Open: https://localhost:8443                      ║"
echo "║    Username: admin                                   ║"
echo "║    Password: $ARGOCD_PASSWORD"
echo "║                                                      ║"
echo "║  ArgoCD will auto-sync whenever Jenkins pushes       ║"
echo "║  a new image tag to helm/local/*.yaml                ║"
echo "║                                                      ║"
echo "║  ECR token refreshes every 6h via CronJob.           ║"
echo "║  Manual refresh: ./scripts/refresh-ecr-token.sh      ║"
echo "╚══════════════════════════════════════════════════════╝"
