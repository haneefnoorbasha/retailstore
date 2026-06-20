# Local k3s Setup — Rancher Desktop

> **Goal:** Run the full RetailStore platform on your MacBook using the same k3s Kubernetes
> distribution as the dev EC2 environment — so everything you practice locally transfers
> directly to dev and stage.
>
> **Your machine:** macOS 26.3.1, Apple Silicon (arm64)

---

## Why Rancher Desktop?

| | Rancher Desktop | Docker Desktop k8s | minikube |
|---|---|---|---|
| **k8s distro** | **k3s** ← same as dev EC2 | kubeadm (different) | kubeadm (different) |
| **Free** | Yes | Yes (personal) | Yes |
| **Replaces Docker Desktop** | Yes | — | No |
| **Includes kubectl + helm** | Yes | kubectl only | No |
| **Apple Silicon** | Yes | Yes | Yes |

Rancher Desktop uses k3s under the hood — the exact same Kubernetes distribution as your
EC2 dev environment. What you learn locally (Helm, kubectl, namespaces, ConfigMaps, port-
forwards) works identically in dev.

---

## Architecture on Local

```
Your MacBook
┌────────────────────────────────────────────────────────────────────────┐
│  Rancher Desktop                                                        │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │  k3s (single-node cluster)   namespace: retailstore              │  │
│  │                                                                  │  │
│  │  Infrastructure (Helm — Bitnami charts):                         │  │
│  │    mysql:3306          redis-master:6379    keycloak:8180        │  │
│  │    dynamodb-local:8000 localstack:4566      zipkin:9411          │  │
│  │                                                                  │  │
│  │  Services (Helm — your charts, local images):                    │  │
│  │    gateway:8080   experience:8080   catalog:8080                 │  │
│  │    carts:8080     checkout:8080     orders:8080                  │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                │                                                        │
│                │  kubectl port-forward                                  │
│                ▼                                                        │
│  localhost:8080  (api-gateway)                                          │
│  localhost:8180  (keycloak admin)                                       │
│  localhost:9411  (zipkin UI)                                            │
│  localhost:3000  (web-storefront, npm run dev)                          │
└────────────────────────────────────────────────────────────────────────┘
```

Services run inside k3s as pods. You reach them from your browser and IntelliJ via
`kubectl port-forward`. This is exactly how it works on dev EC2.

---

## Step 1 — Install Rancher Desktop

1. Download from **rancherdesktop.io** → choose **macOS (aarch64 / Apple Silicon)**
2. Open the `.dmg` and drag to `/Applications`
3. Launch **Rancher Desktop** from Launchpad or Spotlight

**First-launch settings (critical):**

When Rancher Desktop opens for the first time it shows a setup wizard:

| Setting | Value | Why |
|---------|-------|-----|
| Container Engine | **dockerd (moby)** | Lets you use `docker build` — images are immediately visible to k3s without any extra import step |
| Kubernetes version | `v1.29.x` or latest stable | Any recent version works |
| Enable Kubernetes | ON | |

> **If you missed the wizard:** Rancher Desktop → Preferences → Container Engine → select
> **dockerd (moby)** → Apply.

4. Wait for both status indicators to turn green (Docker + Kubernetes). Takes 1–2 minutes.

---

## Step 2 — Verify Tools

Rancher Desktop installs `kubectl`, `helm`, and `docker` automatically. Open a new terminal:

```bash
kubectl version --client
# Client Version: v1.29.x

helm version
# version.BuildInfo{Version:"v3.x.x", ...}

docker --version
# Docker version 27.x.x

kubectl config current-context
# rancher-desktop          ← must show this
```

If commands are not found, add Rancher Desktop's bin to your PATH:
```bash
# Add to ~/.zshrc:
export PATH="$PATH:$HOME/.rd/bin"
```

Then restart the terminal and retry.

---

## Step 3 — Build Docker Images

From the **root of the repo** (`retailstore/`):

```bash
cd /Users/haneefnoorbasha/HaneefWorkspace/retailstore
./retailstore-platform/scripts/build-local.sh
```

This builds all 6 services and tags them as `retailstore/<service>:local`:

```
▶ Building gateway → retailstore/gateway:local   ✓
▶ Building experience → retailstore/experience:local ✓
▶ Building catalog → retailstore/catalog:local   ✓
▶ Building carts → retailstore/carts:local       ✓
▶ Building checkout → retailstore/checkout:local ✓
▶ Building orders → retailstore/orders:local     ✓
```

Verify images are built:
```bash
docker images | grep retailstore
# retailstore/gateway     local   ...
# retailstore/catalog     local   ...
```

> **Why no push step?** In dockerd mode, Rancher Desktop shares the Docker image store
> with k3s. Any image you build with `docker build` is already available to k3s pods — no
> `docker push` to a registry needed. The Helm values use `imagePullPolicy: Never` to tell
> k3s to use the local image and never attempt a registry pull.

---

## Step 4 — Install Infrastructure

```bash
./retailstore-platform/scripts/install-infra-local.sh
```

This installs MySQL, Redis, DynamoDB Local, LocalStack, Zipkin, and Keycloak into the
`retailstore` namespace using Bitnami Helm charts:

```
▶ Creating namespace...
▶ Installing MySQL...          ✓ mysql:3306
▶ Installing Redis...          ✓ redis-master:6379
▶ Installing DynamoDB Local... ✓ dynamodb-local:8000
▶ Installing LocalStack...     ✓ localstack:4566
▶ Installing Zipkin...         ✓ zipkin:9411
▶ Creating Keycloak realm ConfigMap...
▶ Installing Keycloak...       ✓ keycloak:8180   (takes ~2 min on first run)
```

Check everything is running:
```bash
kubectl get pods -n retailstore
```

All pods should show `Running` or `Completed`. Example:
```
NAME                             READY   STATUS    RESTARTS
dynamodb-local-xxx               1/1     Running   0
keycloak-0                       1/1     Running   0
localstack-xxx                   1/1     Running   0
mysql-0                          1/1     Running   0
redis-master-0                   1/1     Running   0
zipkin-xxx                       1/1     Running   0
```

---

## Step 5 — Deploy Services

```bash
./retailstore-platform/scripts/deploy-local.sh
```

```
▶ Deploying catalog...    ✓
▶ Deploying carts...      ✓
▶ Deploying orders...     ✓
▶ Deploying checkout...   ✓
▶ Deploying experience... ✓
▶ Deploying gateway...    ✓
```

Check all pods:
```bash
kubectl get pods -n retailstore
```

All 6 service pods should be `Running` with `1/1` ready (readiness probe passing).

---

## Step 6 — Start Port-Forwards

In a **dedicated terminal** (leave it running):

```bash
./retailstore-platform/scripts/port-forward.sh
```

```
▶ Starting port forwards (namespace: retailstore)...
  MySQL     → localhost:3306
  Redis     → localhost:6379
  Keycloak  → localhost:8180
  DynamoDB  → localhost:8000
  LocalStack → localhost:4566
  Zipkin    → localhost:9411
```

> The script waits (blocking). Keep this terminal open while you work.
> Stop with Ctrl+C or run `./scripts/port-forward.sh stop` from another terminal.

You also need to port-forward the api-gateway to reach it from a browser:
```bash
# In another terminal:
kubectl port-forward svc/gateway 8080:80 -n retailstore
```

---

## Step 7 — Verify the Stack

```bash
# 1. Gateway health
curl http://localhost:8080/actuator/health
# {"status":"UP"}

# 2. Keycloak realm
curl -s http://localhost:8180/realms/retailstore/.well-known/openid-configuration \
  | python3 -m json.tool | grep issuer
# "issuer": "http://keycloak:8180/realms/retailstore"
# NOTE: issuer shows cluster-internal URL — this is expected and correct

# 3. Get a token
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -d "grant_type=password&client_id=web-storefront" \
  -d "username=customer@example.com&password=Customer@1234" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# 4. Call through the gateway
curl -H "Authorization: Bearer $TOKEN" \
  http://localhost:8080/api/v1/catalog/products
# {"products":[...], "totalElements":...}

# 5. Zipkin traces
open http://localhost:9411
```

---

## One-Command Startup (After First Setup)

Once images are built and you've done the first-time setup, you can start everything with:

```bash
# Full rebuild + deploy
./retailstore-platform/scripts/start-local.sh

# Skip rebuild (images already exist)
./retailstore-platform/scripts/start-local.sh --no-build
```

---

## Stopping the Environment

```bash
# Stop services and infra (keep namespace)
./retailstore-platform/scripts/stop-local.sh

# Stop services only (keep infra running — faster restart)
./retailstore-platform/scripts/stop-local.sh --services

# Full clean slate (delete namespace too)
./retailstore-platform/scripts/stop-local.sh --full
```

To free up RAM without uninstalling anything, just quit Rancher Desktop from the menu bar.
k3s suspends. Resume by reopening Rancher Desktop.

---

## Rebuilding After Code Changes

When you change a service's code:

```bash
# 1. Rebuild the changed service
./retailstore-platform/scripts/build-local.sh catalog

# 2. Redeploy it (forces pod restart with new image)
./retailstore-platform/scripts/deploy-local.sh catalog
```

> **Why does redeploy pick up the new image?** The Helm values use `imagePullPolicy: Never`
> and `tag: local`. Helm upgrade detects a change (it bumps an annotation on the pod template
> to force a rollout). The new pod pulls the freshly built local image.

For a quicker iteration loop during development, you can also run the service in IntelliJ
directly (with `SPRING_PROFILES_ACTIVE=dev` and port-forwards active for infra) — no rebuild/
redeploy cycle needed. Use k3s when you want to test the full containerized deployment.

---

## Key kubectl Commands to Practice

```bash
# See all resources in your namespace
kubectl get all -n retailstore

# Watch pods in real time
kubectl get pods -n retailstore -w

# Logs for a service
kubectl logs -f deployment/gateway -n retailstore
kubectl logs -f deployment/catalog -n retailstore

# Describe a pod (see events, resource usage, image)
kubectl describe pod <pod-name> -n retailstore

# Shell into a running pod
kubectl exec -it deployment/catalog -n retailstore -- sh

# See ConfigMap values injected into a service
kubectl get configmap gateway -n retailstore -o yaml

# Scale a service
kubectl scale deployment catalog --replicas=2 -n retailstore

# See resource usage (metrics-server required)
kubectl top pods -n retailstore

# See Helm releases
helm list -n retailstore

# Check Helm values for a release
helm get values gateway -n retailstore
```

---

## Helm Concepts to Practice

```bash
# Install a chart (what deploy-local.sh does)
helm upgrade --install gateway ../../api-gateway/chart \
  -n retailstore \
  -f helm/local/gateway.yaml \
  --set image.repository=retailstore/gateway \
  --set image.tag=local

# Dry run — see what would be deployed without actually deploying
helm upgrade --install gateway ../../api-gateway/chart \
  -n retailstore \
  -f helm/local/gateway.yaml \
  --set image.repository=retailstore/gateway \
  --set image.tag=local \
  --dry-run

# Template — render the manifests locally (great for learning)
helm template gateway ../../api-gateway/chart \
  -f helm/local/gateway.yaml \
  --set image.repository=retailstore/gateway \
  --set image.tag=local

# History of a release
helm history gateway -n retailstore

# Roll back to a previous release
helm rollback gateway 1 -n retailstore

# Uninstall
helm uninstall gateway -n retailstore
```

---

## Environment Differences — Local vs Dev

Both use the same k3s distribution and the same `dev` Spring profile. The differences are:

| | Local (Rancher Desktop) | Dev (EC2 k3s) |
|---|---|---|
| **Image source** | Local Docker build (`retailstore/x:local`) | ECR registry (`<account>.ecr.../retailstore/x:<sha>`) |
| **Image pull** | `Never` | `IfNotPresent` |
| **MySQL persistence** | 2Gi PVC | 5Gi PVC |
| **kubeconfig** | `~/.kube/config` (default) | `~/.kube/config-dev-k3s` |
| **Access** | `localhost` via port-forward | EC2 public IP via port-forward |
| **Resource limits** | Smaller (laptop) | Larger (t3.xlarge: 4 vCPU, 16 GB) |
| **Spring profile** | `dev` | `dev` |
| **Cluster DNS** | Same (`keycloak`, `mysql`, `redis-master`) | Same |

The Spring profile and all application configuration is **identical**. Only the infrastructure
size and image source differ.

---

## Troubleshooting

### Pod stuck in `Pending`

```bash
kubectl describe pod <pod-name> -n retailstore
# Look at "Events:" section at the bottom
```

Common cause: not enough resources. Check:
```bash
kubectl top nodes
# If the node is at >90% memory, stop some pods first
./scripts/stop-local.sh --services   # free up service pods, keep infra
```

### Pod in `ImagePullBackOff` or `ErrImageNeverPull`

The local image wasn't built, or the image name doesn't match the Helm value.

```bash
# Check what image the pod is trying to use
kubectl describe pod <pod-name> -n retailstore | grep Image

# Rebuild
./scripts/build-local.sh catalog

# Verify image exists
docker images | grep retailstore
```

### Pod in `CrashLoopBackOff`

The application is crashing. Check logs:
```bash
kubectl logs <pod-name> -n retailstore --previous
# --previous shows the last crashed container's logs
```

### Keycloak takes too long / times out

First-time Keycloak install runs the realm import. It can take 3–5 minutes on a laptop.
The `install-infra-local.sh` script uses `--timeout 10m`. If it times out:
```bash
# Check if Keycloak is still starting
kubectl logs statefulset/keycloak -n retailstore -f
# Wait for: "Keycloak 25.x.x on /0.0.0.0:8180 ready."

# Then continue with deploy
./scripts/deploy-local.sh
```

### Port-forward drops (connection reset)

`kubectl port-forward` can drop if the pod restarts. Just re-run:
```bash
./scripts/port-forward.sh stop
./scripts/port-forward.sh
```

### Services can't find each other (e.g. `catalog-service` can't connect to MySQL)

Check the service DNS names inside the cluster:
```bash
kubectl get svc -n retailstore
# Should show: mysql, redis-master, keycloak, dynamodb-local, localstack, zipkin
```

Shell into a service pod and test DNS:
```bash
kubectl exec -it deployment/catalog -n retailstore -- sh
# Inside pod:
wget -qO- http://mysql:3306  # should get a MySQL handshake response
wget -qO- http://keycloak:8180/realms/retailstore/.well-known/openid-configuration
```
