# ArgoCD Local Setup Guide

Complete reference for how ArgoCD was installed and configured on Docker Desktop Kubernetes
for the RetailStore local environment. Covers installation, credentials, ECR token refresh,
and how the GitOps loop works end-to-end.

---

## What ArgoCD Does Here

Jenkins builds a Docker image, pushes it to ECR, then commits one line change to
`retailstore-platform/helm/local/gateway.yaml`:

```yaml
image:
  tag: "sha-8326d11"   ← Jenkins updates this
```

ArgoCD watches that file in GitHub. When it changes, ArgoCD runs `helm upgrade`
automatically on Docker Desktop K8s. No manual `kubectl` or `helm` commands needed
after the one-time setup described in this document.

---

## Architecture

```
GitHub repo (retailstore)
  └── retailstore-platform/helm/local/*.yaml   ← ArgoCD watches these
  └── api-gateway/chart/                        ← Helm chart templates
  └── retailstore-platform/argocd/local/        ← ArgoCD Application manifests

Docker Desktop K8s
  └── namespace: argocd
  │     argocd-server           ← ArgoCD control plane
  │     argocd-repo-server      ← clones GitHub, renders Helm charts
  │     argocd-application-controller  ← compares desired vs actual, syncs
  │
  └── namespace: retailstore
        gateway, catalog, carts, checkout, orders, experience  ← your services
        ecr-pull-secret          ← lets pods pull images from ECR
        aws-credentials-local    ← used by ECR token refresh CronJob
        ecr-credentials-refresh  ← CronJob, runs every 6h
```

---

## Prerequisites

| Requirement | How to verify |
|---|---|
| Docker Desktop running with K8s enabled | `kubectl get nodes` → shows `docker-desktop Ready` |
| kubectl context = docker-desktop | `kubectl config current-context` → `docker-desktop` |
| AWS CLI configured | `aws sts get-caller-identity` → shows your account |
| GitHub repo is public OR ArgoCD has deploy key | Public repo — no extra config needed |

---

## One-Time Setup

### Step 1 — Install ArgoCD

```bash
kubectl create namespace argocd
kubectl apply -n argocd \
  -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
```

Wait for ArgoCD server to be ready (~2-3 min):

```bash
kubectl wait --for=condition=available deployment/argocd-server \
  -n argocd --timeout=5m
```

> **Known issue:** You may see this error during install:
> `The CustomResourceDefinition "applicationsets.argoproj.io" is invalid: metadata.annotations: Too long`
>
> This is harmless — it only affects the ApplicationSet CRD annotation size.
> ArgoCD itself installs and runs correctly. Ignore it.

**Verify all pods are running:**

```bash
kubectl get pods -n argocd
```

Expected output (all pods Running/Ready):
```
argocd-application-controller-0        1/1  Running
argocd-applicationset-controller-xxx   1/1  Running
argocd-dex-server-xxx                  1/1  Running
argocd-notifications-controller-xxx    1/1  Running
argocd-redis-xxx                       1/1  Running
argocd-repo-server-xxx                 1/1  Running
argocd-server-xxx                      1/1  Running
```

---

### Step 2 — Create ECR Image Pull Secret

Docker Desktop K8s has no IAM Role (unlike EKS), so pods cannot pull from ECR without
explicit credentials. This secret gives the cluster permission to pull images.

```bash
TOKEN=$(aws ecr get-login-password --region us-east-1)

kubectl create secret docker-registry ecr-pull-secret \
  --docker-server="067744548987.dkr.ecr.us-east-1.amazonaws.com" \
  --docker-username=AWS \
  --docker-password="$TOKEN" \
  -n retailstore \
  --dry-run=client -o yaml | kubectl apply -f -
```

> **Why `--dry-run=client -o yaml | kubectl apply`?**
> This is an upsert pattern — it creates the secret if it doesn't exist, or updates it
> if it already exists. A plain `kubectl create` fails if the secret already exists.

> **ECR tokens expire every 12 hours.** The CronJob in Step 3 refreshes this automatically.
> For manual refresh: `cd retailstore-platform && ./scripts/refresh-ecr-token.sh`

---

### Step 3 — Create AWS Credentials Secret (for ECR Refresh CronJob)

The ECR token refresh CronJob (created in Step 4) needs AWS credentials to call
`aws ecr get-login-password`. These are stored in a K8s Secret — never committed to git.

```bash
# Read from your local aws configure (safest — no manual copy-paste of keys)
AWS_KEY_ID=$(aws configure get aws_access_key_id)
AWS_SECRET=$(aws configure get aws_secret_access_key)

kubectl create secret generic aws-credentials-local \
  --from-literal=AWS_ACCESS_KEY_ID="$AWS_KEY_ID" \
  --from-literal=AWS_SECRET_ACCESS_KEY="$AWS_SECRET" \
  -n retailstore \
  --dry-run=client -o yaml | kubectl apply -f -
```

> **This secret is never in git.** It lives only in the cluster.
> If you reset Docker Desktop K8s, re-run this step.

---

### Step 4 — Apply AppProject and ECR Token Refresh CronJob

```bash
cd retailstore-platform

kubectl apply -f argocd/local/project.yaml
kubectl apply -f argocd/local/ecr-token-refresh.yaml
```

**What `project.yaml` does:**
Defines an ArgoCD AppProject named `retailstore-local` that restricts all 6 Applications
to only deploy from your GitHub repo into the `retailstore` namespace. Prevents accidental
deployment to wrong clusters or namespaces.

**What `ecr-token-refresh.yaml` creates:**

| Resource | Purpose |
|---|---|
| `ServiceAccount ecr-refresher` | Identity for the CronJob pods |
| `Role ecr-secret-manager` | Permission to get/create/update secrets in `retailstore` ns |
| `RoleBinding` | Binds the Role to the ServiceAccount |
| `CronJob ecr-credentials-refresh` | Runs every 6h — refreshes `ecr-pull-secret` |

**How the CronJob works (2-container pattern):**

```
Every 6 hours:
  initContainer (amazon/aws-cli:2.17.0)
    → reads AWS_ACCESS_KEY_ID + AWS_SECRET_ACCESS_KEY from aws-credentials-local secret
    → calls: aws ecr get-login-password --region us-east-1
    → writes token to shared emptyDir volume at /shared/token

  container (bitnami/kubectl:1.30)
    → reads token from /shared/token
    → runs: kubectl create secret docker-registry ecr-pull-secret ... | kubectl apply -f -
    → ecr-pull-secret updated with fresh 12-hour token
```

---

### Step 5 — Apply ArgoCD Application Manifests

```bash
cd retailstore-platform

for app in catalog carts checkout orders experience gateway; do
  kubectl apply -f "argocd/local/$app.yaml"
done
```

This creates 6 ArgoCD Application resources — one per microservice. Each Application tells
ArgoCD:
- Which GitHub repo to watch
- Which Helm chart to use (e.g. `api-gateway/chart/`)
- Which values file to use (e.g. `retailstore-platform/helm/local/gateway.yaml`)
- Which K8s cluster and namespace to deploy into

**Multi-source pattern (why two sources per Application):**

```yaml
sources:
  - repoURL: https://github.com/haneefnoorbasha/retailstore.git
    targetRevision: main
    ref: repo                          ← Source 1: gives this source the alias "$repo"

  - repoURL: https://github.com/haneefnoorbasha/retailstore.git
    targetRevision: main
    path: api-gateway/chart            ← Source 2: the Helm chart
    helm:
      valueFiles:
        - $repo/retailstore-platform/helm/local/gateway.yaml  ← references Source 1
```

This is needed because the chart (`api-gateway/chart/`) and values file
(`retailstore-platform/helm/local/gateway.yaml`) are in different directories of the
same monorepo. ArgoCD's standard single-source mode doesn't support cross-directory
value files. Multi-source (ArgoCD 2.6+) solves this with the `ref:` + `$ref` pattern.

---

## Accessing the ArgoCD UI

**Start port-forward (run in a separate terminal — keep it open):**

```bash
kubectl port-forward svc/argocd-server -n argocd 8443:443
```

**Open:** https://localhost:8443

> Browser will warn about self-signed certificate — click "Advanced → Proceed"

**Credentials:**
- Username: `admin`
- Password: retrieve with:
  ```bash
  kubectl -n argocd get secret argocd-initial-admin-secret \
    -o jsonpath="{.data.password}" | base64 -d
  ```

> The initial admin password is stored in `argocd-initial-admin-secret`.
> After first login, change it: ArgoCD UI → User Info → Update Password

---

## What You See in the ArgoCD UI

After setup, all 6 applications appear. Initial states:

| State | Meaning | What to do |
|---|---|---|
| **Synced** | K8s matches git — healthy | Nothing |
| **OutOfSync** | Git changed, K8s not updated yet | Wait ~3 min for auto-sync, or click Sync |
| **Degraded** | Applied but pod is crashing | Check pod logs: `kubectl logs -n retailstore deploy/gateway` |
| **Unknown** | ArgoCD hasn't checked yet | Wait ~1 min |

Applications go **OutOfSync → Syncing → Synced** automatically within 3 minutes of
a Jenkins push to `helm/local/*.yaml`.

---

## The Full GitOps Loop (End to End)

```
1. Developer pushes code to api-gateway/
         │
         ▼
2. GitHub webhook → Jenkins gateway-pipeline triggers
         │
         ▼
3. Jenkins: Path Check → detects api-gateway/ changed
         │
         ▼
4. Jenkins: docker build --platform linux/amd64 -t ECR/retailstore/gateway:sha-xxxxxxx
         │
         ▼
5. Jenkins: aws ecr get-login-password | docker login → docker push to ECR
         │
         ▼
6. Jenkins: sed updates helm/local/gateway.yaml tag → git commit → git push
         │
         ▼
7. GitHub repo: helm/local/gateway.yaml now has tag: "sha-xxxxxxx"
         │
         ▼
8. ArgoCD polls GitHub every 3 min → detects helm/local/gateway.yaml changed
         │
         ▼
9. ArgoCD: renders Helm chart with new values → compares with running pods
           Current pod image: sha-oldtag
           Desired pod image: sha-xxxxxxx  ← DIFF
         │
         ▼
10. ArgoCD: applies diff → K8s rolling update → pulls sha-xxxxxxx from ECR
         │
         ▼
11. New gateway pod running with latest code — zero downtime
```

---

## Day-to-Day Operations

### Check application sync status
```bash
kubectl get applications -n argocd
```

### Force immediate sync (without waiting 3 min)
```bash
# Via UI: click the application → Sync → Synchronize
# Via CLI:
argocd app sync gateway --server localhost:8443 --insecure
```

### Check what's running
```bash
kubectl get pods -n retailstore
kubectl get deployments -n retailstore
```

### Check gateway logs
```bash
kubectl logs -n retailstore deploy/gateway -f
```

### Manually refresh ECR token (if pods show ImagePullBackOff)
```bash
cd retailstore-platform && ./scripts/refresh-ecr-token.sh
```

### Check ECR token refresh CronJob history
```bash
kubectl get cronjob -n retailstore
kubectl get jobs -n retailstore
```

---

## Re-running Setup After a K8s Reset

If you reset Docker Desktop Kubernetes (Settings → Kubernetes → Reset Kubernetes Cluster):

```bash
# All steps must be re-run — the cluster is wiped
cd retailstore-platform && ./scripts/setup-argocd-local.sh
```

The script is idempotent — safe to run multiple times.

---

## Files Reference

| File | Purpose |
|---|---|
| `retailstore-platform/argocd/local/project.yaml` | ArgoCD AppProject — scopes all apps to your repo + retailstore namespace |
| `retailstore-platform/argocd/local/gateway.yaml` | ArgoCD Application for api-gateway |
| `retailstore-platform/argocd/local/catalog.yaml` | ArgoCD Application for catalog-service |
| `retailstore-platform/argocd/local/carts.yaml` | ArgoCD Application for cart-service |
| `retailstore-platform/argocd/local/checkout.yaml` | ArgoCD Application for checkout-service |
| `retailstore-platform/argocd/local/orders.yaml` | ArgoCD Application for order-service |
| `retailstore-platform/argocd/local/experience.yaml` | ArgoCD Application for experience-service |
| `retailstore-platform/argocd/local/ecr-token-refresh.yaml` | CronJob that refreshes ECR credentials every 6h |
| `retailstore-platform/scripts/setup-argocd-local.sh` | One-time setup script (runs all 5 steps) |
| `retailstore-platform/scripts/refresh-ecr-token.sh` | Manual ECR token refresh |
| `retailstore-platform/helm/local/*.yaml` | Helm values files — Jenkins updates image tags here |
