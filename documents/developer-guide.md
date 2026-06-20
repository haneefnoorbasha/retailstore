# RetailStore — Developer Guide

> This guide covers the **local** (Rancher Desktop k3s) and **dev** (EC2 k3s) environments.
> For stage and prod, see [platform-design.md](platform-design.md) Sections 8, 9, and 11.
> For the full local k3s setup walkthrough, see [local-k3s-setup.md](local-k3s-setup.md).

## Environment Quick Reference

| Profile | Infrastructure | How to run | What changes |
|---|---|---|---|
| `dev` | k3s on **Rancher Desktop** (local Mac) | `start-local.sh` + `port-forward.sh` | Same as dev EC2 — MySQL, Redis, Keycloak, Zipkin in k3s pods |
| `dev` | k3s on **EC2** (cloud dev) | `start-dev.sh` + `port-forward.sh` | Same profile, same Helm charts — images from ECR |
| `stage` | AWS EKS (Terraform required) | GitHub Actions CI → EKS deploy | Replace `# Replace:` vars in `helm/stage/*.yaml` |
| `prod` | AWS EKS HA (Terraform required) | ArgoCD → EKS canary deploy | Replace `# Replace:` vars in `helm/prod/*.yaml` |

> **Both local and dev use the same `dev` Spring profile.** The only differences are where
> the images come from (local Docker build vs ECR) and which Helm values file is used
> (`helm/local/` vs `helm/dev/`). All cluster DNS names, env vars, and application config
> are identical.

---

## Dev Environment Overview

The dev environment runs the full RetailStore stack on a **k3s cluster inside an EC2 instance**. You start and stop the EC2 on demand to keep costs near zero when not working.

```
Your Laptop
┌─────────────────────────────────────────────────────────┐
│  IntelliJ  ──(SPRING_PROFILES_ACTIVE=dev)──┐            │
│                                             │            │
│  kubectl + helm    ─────── kubeconfig ──►  │  EC2       │
│                                            │  ┌────────┐│
│  port-forward.sh ──► localhost:3306 ──────►│  │  k3s   ││
│                  ──► localhost:6379        │  │cluster ││
│                  ──► localhost:8180        │  └────────┘│
└─────────────────────────────────────────────────────────┘
```

**Stack (dev replicates stage technology):**

| Stage (AWS)            | Dev (k3s on EC2)          |
|------------------------|---------------------------|
| RDS MySQL              | MySQL 8 (Bitnami chart)    |
| ElastiCache Redis      | Redis 7 (Bitnami chart)    |
| AWS DynamoDB           | DynamoDB Local             |
| AWS SQS                | LocalStack SQS             |
| Keycloak on EKS        | Keycloak 25 (Bitnami)      |
| AWS X-Ray / tracing    | Zipkin                     |
| EKS                    | k3s on EC2                 |
| ECR                    | ECR (same)                 |

---

## Part 1: One-Time Setup

### 1.1 Tools Required (on your laptop)

```bash
# macOS
brew install awscli kubectl helm

# Verify versions
aws --version          # 2.x+
kubectl version        # 1.28+
helm version           # 3.14+
```

### 1.2 Launch the EC2 Instance

1. Open AWS Console → EC2 → **Launch Instance**
2. **AMI**: Amazon Linux 2023 (or Ubuntu 22.04)
3. **Instance type**: `t3.xlarge` (4 vCPU, 16 GB RAM) — enough for all 6 services + infra
4. **Key pair**: Create or select a key pair; download the `.pem` file
5. **Security group** — open inbound:

   | Port  | Source         | Purpose                     |
   |-------|----------------|-----------------------------|
   | 22    | Your IP/0.0.0.0 | SSH                         |
   | 6443  | Your IP        | k3s API server (kubectl)    |
   | 8080  | Your IP        | API Gateway (optional test) |

6. **Storage**: 30 GB gp3 (images + k3s data)
7. **Note the Instance ID** — e.g. `i-0a1b2c3d4e5f67890`

### 1.3 Install k3s on EC2

SSH into the instance and run:

```bash
ssh -i ~/.ssh/dev-key.pem ec2-user@<EC2_PUBLIC_IP>

# Install k3s
curl -sfL https://get.k3s.io | sh -

# Verify
sudo k3s kubectl get nodes
# NAME       STATUS   ROLES                  AGE
# ip-10-x-x  Ready    control-plane,master   30s

# Allow ec2-user to use kubectl without sudo
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown ec2-user:ec2-user ~/.kube/config
```

k3s installs in ~30 seconds and auto-starts on reboot.

### 1.4 Install Helm on EC2

```bash
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash
```

### 1.5 Create ECR Repositories

Run **once** from your laptop (or use Terraform in `retailstore-platform/terraform/`):

```bash
AWS_REGION=us-east-1
AWS_ACCOUNT_ID=$(aws sts get-caller-identity --query Account --output text)

for repo in retailstore/catalog retailstore/carts retailstore/checkout \
            retailstore/orders retailstore/experience retailstore/gateway; do
  aws ecr create-repository --repository-name "$repo" --region "$AWS_REGION" 2>/dev/null || true
  echo "ECR repo: $AWS_ACCOUNT_ID.dkr.ecr.$AWS_REGION.amazonaws.com/$repo"
done
```

### 1.6 Allow EC2 to Pull from ECR

Attach the **AmazonEC2ContainerRegistryReadOnly** IAM role to your EC2 instance:

1. AWS Console → EC2 → Select instance → Actions → Security → Modify IAM role
2. Create role with `AmazonEC2ContainerRegistryReadOnly` policy
3. Attach to instance

### 1.7 Configure Your Laptop

Add to `~/.zshrc` or `~/.bashrc`:

```bash
export EC2_INSTANCE_ID="i-0a1b2c3d4e5f67890"   # ← Replace
export EC2_SSH_KEY="$HOME/.ssh/dev-key.pem"
export EC2_USER="ec2-user"                        # ubuntu for Ubuntu AMI
export AWS_REGION="us-east-1"
export ECR_REGISTRY="123456789012.dkr.ecr.us-east-1.amazonaws.com"  # ← Replace
export KUBECONFIG_DEV="$HOME/.kube/config-dev-k3s"
```

---

## Part 2: Daily Workflow

### 2.1 Start the Dev Environment

```bash
cd retailstore-platform
./scripts/start-dev.sh
```

This script:
1. Starts the EC2 instance
2. Waits for k3s to be reachable
3. Downloads the kubeconfig to `~/.kube/config-dev-k3s`
4. Installs infra (MySQL, Redis, Keycloak, DynamoDB Local, LocalStack, Zipkin)
5. Deploys all 6 microservices from ECR

**First-time run** takes ~10 minutes (Keycloak startup + image pulls). Subsequent starts take ~4 minutes.

### 2.2 Develop a Single Service in IntelliJ

While the full stack runs in k3s, forward infra ports to your laptop:

```bash
export KUBECONFIG=$HOME/.kube/config-dev-k3s
./scripts/port-forward.sh start
# Forwarding:
#   MySQL       → localhost:3306
#   Redis       → localhost:6379
#   Keycloak    → localhost:8180
#   DynamoDB    → localhost:8000
#   LocalStack  → localhost:4566
#   Zipkin      → localhost:9411
```

Then in **IntelliJ**:
1. Open the service module (e.g. `catalog-service`)
2. Edit Run/Debug Configuration:
   - **VM options**: `-DSPRING_PROFILES_ACTIVE=dev`
   - **Environment variables**: *(none needed — localhost defaults in application-dev.yml work)*
3. Run the service

Your IntelliJ service connects to k3s infra via `localhost:port` (the port-forwarded ports). The other services continue running in k3s.

> **Why this works:** `application-dev.yml` uses `${MYSQL_HOST:localhost}` — when `MYSQL_HOST` is not set, it defaults to `localhost`, which resolves to the port-forwarded container.

### 2.3 Stop the Dev Environment

```bash
./scripts/stop-dev.sh
```

Uninstalls all Helm releases and stops EC2. **Cost stops immediately** (no compute charge while stopped; EBS ~$0.08/GB/month).

---

## Part 3: Build & Deploy Workflow

### 3.1 Build and Push a Single Service

After making code changes to a service:

```bash
# From workspace root
export ECR_REGISTRY="123456789012.dkr.ecr.us-east-1.amazonaws.com"

# Build & push one service
./retailstore-platform/scripts/build-push.sh catalog-service

# Build all services
./retailstore-platform/scripts/build-push.sh
```

The script:
- Builds `linux/amd64` Docker image (required for EC2)
- Tags with `$(git rev-parse --short HEAD)` and also `:latest`
- Pushes to ECR

### 3.2 Deploy a Single Service

```bash
export KUBECONFIG=$HOME/.kube/config-dev-k3s
export ECR_REGISTRY="123456789012.dkr.ecr.us-east-1.amazonaws.com"
export IMAGE_TAG=$(git rev-parse --short HEAD)

cd retailstore-platform

# Redeploy one service (rolling update)
./scripts/deploy-services.sh catalog

# Redeploy all services
IMAGE_TAG=latest ./scripts/deploy-services.sh
```

### 3.3 Fast Iteration Loop

```bash
# Edit code → build → push → redeploy (one liner)
export IMAGE_TAG=$(git rev-parse --short HEAD)
./retailstore-platform/scripts/build-push.sh catalog-service && \
  (cd retailstore-platform && ./scripts/deploy-services.sh catalog)
```

---

## Part 4: Configuration Reference

### 4.1 Helm Dev Values — Where to Change Things

All service configuration lives in `retailstore-platform/helm/dev/*.yaml`. These are Helm value overrides applied on top of each service's `chart/values.yaml`.

| File | Controls |
|------|----------|
| `helm/dev/gateway.yaml` | Route URLs, Keycloak host, Redis host, CORS origin |
| `helm/dev/catalog.yaml` | MySQL credentials, Redis host, Keycloak host |
| `helm/dev/carts.yaml` | DynamoDB endpoint, AWS region |
| `helm/dev/checkout.yaml` | Redis host, Keycloak client secret, order service URL |
| `helm/dev/orders.yaml` | MySQL credentials, SQS queue URL, Keycloak host |
| `helm/dev/experience.yaml` | Downstream service URLs, Keycloak client secret |

**Env var → application-dev.yml mapping** (how k8s env var names map to Spring properties):

| k8s env var | application-dev.yml placeholder | Used by |
|-------------|----------------------------------|---------|
| `KEYCLOAK_HOST` | `${KEYCLOAK_HOST:localhost}` | All services |
| `KEYCLOAK_PORT` | `${KEYCLOAK_PORT:8180}` | All services |
| `MYSQL_HOST` | `${MYSQL_HOST:localhost}` | catalog, orders |
| `MYSQL_PASSWORD` | `${MYSQL_PASSWORD:xxx_pass}` | catalog, orders |
| `REDIS_HOST` | `${REDIS_HOST:localhost}` | gateway, catalog, checkout |
| `RETAIL_CART_DYNAMODB_ENDPOINT` | `${RETAIL_CART_DYNAMODB_ENDPOINT:http://localhost:8000}` | cart |
| `RETAIL_ORDER_MESSAGING_SQS_QUEUE_URL` | `${RETAIL_ORDER_MESSAGING_SQS_QUEUE_URL:http://localhost:4566/...}` | orders |
| `SQS_ENDPOINT` | `${SQS_ENDPOINT:}` (in SqsConfig.java) | orders |
| `ZIPKIN_HOST` | `${ZIPKIN_HOST:localhost}` | All services |

### 4.2 Infra Helm Values — Where to Change Versions/Sizes

| File | Controls |
|------|----------|
| `helm/infra/mysql-values.yaml` | MySQL version pin, storage size, init SQL |
| `helm/infra/redis-values.yaml` | Redis version pin, memory |
| `helm/infra/keycloak-values.yaml` | Keycloak version pin, MySQL connection, realm import path |

**Chart versions pinned** (update only when testing an upgrade):

| Chart | Pinned version | Upstream |
|-------|---------------|----------|
| bitnami/mysql | 11.1.14 | MySQL 8.0 |
| bitnami/redis | 20.1.7 | Redis 7.4 |
| bitnami/keycloak | 22.2.1 | Keycloak 25 |

### 4.3 URLs That Need Replacing

These placeholders appear in the codebase and must be updated with real values before using stage/prod environments:

| Location | Placeholder | What to replace with |
|----------|-------------|----------------------|
| `helm/stage/*/` all files | `KEYCLOAK_JWKS_URI: ""` | Your EKS Keycloak JWKS URL |
| `helm/stage/*/` all files | `RDS_ENDPOINT: ""` | Your RDS endpoint |
| `helm/stage/*/` all files | `REDIS_HOST: ""` | Your ElastiCache endpoint |
| `helm/stage/orders.yaml` | `RETAIL_ORDER_MESSAGING_SQS_QUEUE_URL: ""` | Your SQS queue URL |
| `helm/stage/*/` all files | `TRACING_ENDPOINT: ""` | Your tracing backend |
| `helm/prod/*/` all files | Same set as stage | Prod equivalents |
| `application-stage.yml` | `# Replace:` comments | Real AWS resource endpoints |
| `application-prod.yml` | `# Replace:` comments | Real AWS resource endpoints |

---

## Part 5: Keycloak Realm Management

The Keycloak realm JSON lives at:
```
retailstore-platform/keycloak/realms/retailstore-realm.json
```

**After modifying the realm JSON**, update the ConfigMap and restart Keycloak:

```bash
export KUBECONFIG=$HOME/.kube/config-dev-k3s
cd retailstore-platform

kubectl create configmap keycloak-realm \
  --from-file=retailstore-realm.json=keycloak/realms/retailstore-realm.json \
  -n retailstore --dry-run=client -o yaml | kubectl apply -f -

kubectl rollout restart statefulset/keycloak -n retailstore
```

**Dev Keycloak credentials:**
- Admin UI: `http://<EC2_IP>:8180` (via NodePort, if configured) or `http://localhost:8180` (via port-forward)
- Admin user: `admin` / `admin`
- Realm: `retailstore`
- Clients: `web-storefront` (PKCE), `experience-service` (client_credentials), `checkout-service` (client_credentials)

---

## Part 6: Debugging

### View Logs

```bash
export KUBECONFIG=$HOME/.kube/config-dev-k3s

# All pods
kubectl get pods -n retailstore

# Logs for a service
kubectl logs -f deployment/catalog -n retailstore

# Logs with previous crash
kubectl logs deployment/catalog -n retailstore --previous

# Follow all pods matching label
kubectl logs -f -l app.kubernetes.io/part-of=retailstore -n retailstore --prefix
```

### Describe a Failing Pod

```bash
kubectl describe pod <pod-name> -n retailstore
```

### Shell into a Pod

```bash
kubectl exec -it deployment/catalog -n retailstore -- sh
```

### Check Keycloak Health

```bash
# Inside the cluster
kubectl exec -it statefulset/keycloak -n retailstore -- \
  curl -sf http://localhost:8180/realms/retailstore/.well-known/openid-configuration | head -5

# From laptop (via port-forward)
curl http://localhost:8180/realms/retailstore/.well-known/openid-configuration
```

### Check MySQL

```bash
kubectl exec -it statefulset/mysql -n retailstore -- \
  mysql -u root -prootpass -e "SHOW DATABASES;"
```

### Zipkin Traces

Port-forward Zipkin and open in browser:
```bash
kubectl port-forward svc/zipkin 9411:9411 -n retailstore
open http://localhost:9411
```

---

## Part 7: EC2 Setup Script (Run Once)

Save this as `setup-ec2.sh` on your EC2 instance and run it after launching:

```bash
#!/bin/bash
set -e

# Install k3s
curl -sfL https://get.k3s.io | sh -

# Allow current user kubectl access
mkdir -p ~/.kube
sudo cp /etc/rancher/k3s/k3s.yaml ~/.kube/config
sudo chown "$(whoami):$(whoami)" ~/.kube/config

# Install Helm
curl https://raw.githubusercontent.com/helm/helm/main/scripts/get-helm-3 | bash

# Configure Docker (for ECR image pull via k3s containerd — not needed, k3s handles ECR via IRSA)
# k3s uses containerd internally; ECR auth is handled via the EC2 instance IAM role

# Verify
kubectl get nodes
helm version
echo "EC2 k3s setup complete"
```

---

## Part 8: Cost Estimate

| Resource | Cost when RUNNING | Cost when STOPPED |
|----------|-------------------|-------------------|
| EC2 t3.xlarge | ~$0.166/hr | $0 |
| EC2 EBS 30GB gp3 | ~$0.08/GB/month | ~$0.08/GB/month |
| ECR storage (all images) | ~$0.01/GB/month | ~$0.01/GB/month |
| Data transfer | Minimal | $0 |

**Typical monthly cost** if you work 8 hours/day, 20 days/month:
- Compute: 160 hours × $0.166 = **~$26.50/month**
- EBS: 30GB × $0.08 = **~$2.40/month**
- ECR: ~$1/month
- **Total: ~$30/month**

Compare to always-on EKS cluster: ~$150-300/month for dev.

---

## Quick Reference

```bash
# ── Start / Stop ─────────────────────────────────────────
./retailstore-platform/scripts/start-dev.sh         # start EC2 + install everything
./retailstore-platform/scripts/stop-dev.sh          # uninstall + stop EC2

# ── Connect (run after start, before IntelliJ) ───────────
export KUBECONFIG=$HOME/.kube/config-dev-k3s
./retailstore-platform/scripts/port-forward.sh start

# ── Build & Deploy ───────────────────────────────────────
./retailstore-platform/scripts/build-push.sh catalog-service
export IMAGE_TAG=$(git rev-parse --short HEAD)
(cd retailstore-platform && ./scripts/deploy-services.sh catalog)

# ── Logs & Debug ─────────────────────────────────────────
kubectl get pods -n retailstore
kubectl logs -f deployment/catalog -n retailstore
kubectl exec -it deployment/catalog -n retailstore -- sh
```

---

## Part 9: Stage / Prod Deployment Reference

Stage and prod use **AWS EKS** (not k3s). The Helm charts and Spring Boot profiles are identical;
only the Helm values files differ.

### Before You Can Deploy to Stage

1. **Run Terraform** to provision the AWS infrastructure:
   ```bash
   cd retailstore-platform/terraform/environments/stage
   terraform init && terraform apply
   # Outputs: RDS endpoint, ElastiCache endpoint, EKS cluster name
   ```

2. **Fill in the placeholders** in all `helm/stage/*.yaml` files:
   ```yaml
   # Every file has these with "# Replace:" comments:
   KEYCLOAK_JWKS_URI: "https://auth.stage.retailstore.com/realms/retailstore/..."
   RDS_ENDPOINT: "your-rds.us-east-1.rds.amazonaws.com"
   REDIS_HOST: "your-elasticache.cache.amazonaws.com"
   ```

3. **Set up the GitHub Actions CI pipeline** (`.github/workflows/*.yml`) with:
   - `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` secrets
   - `ECR_REGISTRY` variable
   - `EKS_CLUSTER_NAME` and `EKS_REGION` variables

4. **Import the Keycloak realm** into stage Keycloak after first deploy.

### Spring Profile Activation (stage / prod)

```yaml
# helm/stage/gateway.yaml  (and all other services)
appEnv:
  SPRING_PROFILES_ACTIVE: "stage"
  KEYCLOAK_JWKS_URI: "..."      # real EKS Keycloak URL
  RDS_ENDPOINT: "..."           # real RDS endpoint
  REDIS_HOST: "..."             # real ElastiCache endpoint
  TRACING_ENDPOINT: "..."       # AWS X-Ray or OTEL collector
```

```yaml
# helm/prod/gateway.yaml
appEnv:
  SPRING_PROFILES_ACTIVE: "prod"
  # Same vars as stage + prod-specific values
  # Swagger disabled in application-prod.yml
  # Health show-details: never
  # 5% trace sampling
```

### What application-stage.yml enables vs dev

| Feature | DEV | STAGE |
|---|---|---|
| Database | MySQL via `${MYSQL_HOST:localhost}` | MySQL via `${RDS_ENDPOINT}` (no default — must be set) |
| Redis SSL | No | `spring.data.redis.ssl.enabled: true` |
| JDBC SSL | No | `useSSL=true&requireSSL=true` in URL |
| Tracing | 100% to Zipkin | 10% (configured by `TRACING_ENDPOINT`) |
| Logging format | Colored console | JSON (Logstash encoder) |
| Swagger | Enabled | Enabled (internal URL only) |
| Health details | `always` | `when-authorized` |

### What application-prod.yml adds on top of stage

- `springdoc.api-docs.enabled: false` (Swagger completely disabled)
- `management.endpoint.health.show-details: never`
- `management.tracing.sampling.probability: 0.05` (5%)
- HikariCP `leak-detection-threshold: 60000ms`
- `logging.level: WARN` (not INFO)
- Tighter Resilience4j thresholds (failure rate 40%, wait 20s)
