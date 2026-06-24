# Infrastructure & Deployment Guide

> **Who is this for?** You know Terraform and Docker Compose at a moderate level but are new
> to Helm. This document ties all three together and explains exactly how RetailStore uses each
> tool — environment by environment, from your laptop to production.

---

## The Core Question: What Does Each Tool Do?

Think of building a restaurant:

| Tool | Analogy | What it actually does |
|---|---|---|
| **Terraform** | Building the restaurant — walls, plumbing, electricity | Provisions **cloud infrastructure**: VPC, EKS cluster, RDS, ElastiCache, IAM roles, S3 |
| **Helm** | Setting up the kitchen inside — equipment, menu, staff layout | Deploys **applications** onto a Kubernetes cluster |
| **k8s manifests (plain YAML)** | Temporary equipment for practice runs | Runs infra services **inside k8s** locally, where real AWS services don't exist |
| **Docker Compose** | A practice kitchen at home (old approach) | Was used for local dev before. Now replaced by k8s locally |

The critical insight: **Terraform and Helm operate at different layers and never overlap.**

```
┌─────────────────────────────────────────────────────────────┐
│  TERRAFORM LAYER — "Create the platform"                    │
│  VPC, EKS cluster, RDS, ElastiCache, IAM, S3, NAT Gateway  │
└────────────────────────┬────────────────────────────────────┘
                         │ outputs: cluster endpoint, DB URLs, etc.
┌────────────────────────▼────────────────────────────────────┐
│  HELM LAYER — "Deploy apps onto the platform"               │
│  api-gateway, catalog, experience, checkout, orders, carts  │
└─────────────────────────────────────────────────────────────┘
```

---

## Is Terraform Only for Cloud?

**Yes — Terraform provisions cloud resources (AWS, GCP, Azure).**

Terraform talks to AWS APIs to create things like:
- EKS cluster (your k8s control plane)
- VPC + subnets + NAT Gateway + route tables
- RDS MySQL (managed database)
- ElastiCache Redis (managed cache)
- S3 buckets, SQS queues, IAM roles

On your **local machine**, none of these exist. Docker Desktop gives you a k8s cluster directly
(no AWS needed), and you run MySQL, Redis, and Keycloak as pods inside k8s. That is why there
is no Terraform in the local environment.

---

## Does Helm Work Like Terraform Modules?

**Yes — the concept is identical:**

| Concept | Terraform | Helm |
|---|---|---|
| **Template** | Module (reusable HCL code) | Chart (reusable k8s YAML templates) |
| **Per-env config** | `terraform.tfvars` | `values.yaml` override file |
| **Command** | `terraform apply -var-file=stage.tfvars` | `helm upgrade --install -f helm/stage/gateway.yaml` |
| **Result** | Cloud infrastructure created | App deployed onto k8s |

### Terraform: one set of modules, different tfvars

```
retailstore-infra/terraform/
  modules/
    vpc/          ← reusable: creates VPC + subnets + NAT Gateway
    eks/          ← reusable: creates EKS cluster + node groups
    rds/          ← reusable: creates RDS MySQL
    elasticache/  ← reusable: creates Redis cluster
    iam/          ← reusable: IAM roles, IRSA for pods

  environments/
    stage/
      main.tf           ← calls the modules above
      terraform.tfvars  ← stage config: 1 NAT Gateway, t3.medium nodes, single-AZ RDS
    prod/
      main.tf           ← same modules
      terraform.tfvars  ← prod config:  3 NAT Gateways, t3.xlarge nodes, Multi-AZ RDS
```

The modules are written once. The `tfvars` file is the only thing that changes per environment.
Stage uses fewer resources (cost-optimised). Prod uses full HA configuration.

### Helm: one chart per service, different values files

```
api-gateway/
  chart/                    ← THE CHART (one set of templates, used by all environments)
    Chart.yaml
    templates/
      deployment.yaml       ← k8s Deployment — references {{ .Values.image.repository }}
      service.yaml          ← k8s Service
      configmap.yaml        ← env vars — references {{ .Values.appEnv }}
      hpa.yaml              ← HorizontalPodAutoscaler — references {{ .Values.autoscaling }}
    values.yaml             ← defaults

retailstore-platform/helm/
  local/gateway.yaml        ← local overrides
  stage/gateway.yaml        ← stage overrides
  prod/gateway.yaml         ← prod overrides
```

Same `deployment.yaml` template for every environment. Only the values file changes:

```yaml
# helm/local/gateway.yaml               # helm/stage/gateway.yaml
image:                                   image:
  repository: "067744548987.dkr.ecr.     repository: "067744548987.dkr.ecr.
    us-east-1.amazonaws.com/             us-east-1.amazonaws.com/
    retailstore/gateway"                  retailstore/gateway"
  tag: "local"                             tag: "sha-abc1234"
  pullPolicy: IfNotPresent               replicaCount: 2
imagePullSecrets:                        autoscaling:
  - name: ecr-pull-secret                  enabled: true
replicaCount: 1                            minReplicas: 2
autoscaling:                               maxReplicas: 4
  enabled: false                         resources:
resources:                                 limits:
  limits:                                    memory: 768Mi
    memory: 512Mi
```

**Why `imagePullSecrets` only in local?** On EKS (stage/prod), the EKS node IAM role has ECR pull permissions built in — no secret needed. On Docker Desktop K8s there is no IAM role, so an explicit `ecr-pull-secret` is required. A CronJob inside the cluster refreshes it every 6 hours because ECR tokens expire after 12 hours.

Helm merges your override file on top of the chart's default `values.yaml`. The chart template
is never edited — only the values files are.

---

## Environment-by-Environment Breakdown

### Local — Docker Desktop Kubernetes

```
Your MacBook
└── Docker Desktop (provides k8s cluster — no AWS needed)
    └── namespace: retailstore
        ├── Infra (plain k8s manifests — simulate AWS services)
        │     mysql pod        ← replaces AWS RDS
        │     redis pod        ← replaces AWS ElastiCache
        │     keycloak pod     ← same in all envs (not an AWS service)
        │     dynamodb-local   ← replaces AWS DynamoDB
        │     zipkin           ← replaces AWS X-Ray
        │
        └── Services (Helm charts — same charts as stage/prod)
              gateway, experience, catalog, carts, checkout, orders
              Spring profile: local
              Images: 067744548987.dkr.ecr.us-east-1.amazonaws.com/retailstore/<service>:sha-*
```

**Who creates what:**

| Component | Created by | File |
|---|---|---|
| k8s cluster | Docker Desktop (manual, one-time) | — |
| namespace | `kubectl apply` | `k8s/local/namespace.yaml` |
| MySQL, Redis, Keycloak | `kubectl apply` | `k8s/local/mysql.yaml`, etc. |
| DynamoDB, Zipkin | `kubectl apply` | `k8s/local/dynamodb-local.yaml`, etc. |
| ECR pull secret | `setup-argocd-local.sh` (one-time) | — |
| ECR secret refresh | CronJob (every 6h) | `argocd/local/ecr-token-refresh.yaml` |
| Microservices | ArgoCD (GitOps — auto-syncs on every Jenkins push) | `helm/local/*.yaml` |

**Scripts:**
```bash
./scripts/build-local.sh           # docker build + push to ECR (via Jenkins, or manually)
./scripts/install-infra-local.sh   # kubectl apply all infra manifests (MySQL, Redis, etc.)
./scripts/setup-argocd-local.sh    # ONE-TIME: install ArgoCD + ECR secret + Application manifests
./scripts/refresh-ecr-token.sh     # manual ECR token refresh if pods show ImagePullBackOff
./scripts/port-forward.sh          # expose services to localhost for IntelliJ/browser
```

**How local deployment works (GitOps):**

Jenkins builds the Docker image, pushes it to ECR, then commits one line to `helm/local/gateway.yaml`:

```yaml
image:
  tag: "sha-a1b2c3d"   ← Jenkins updates this
```

ArgoCD running on Docker Desktop K8s detects the change (polls every 3 min) and runs `helm upgrade` automatically. No manual `helm` or `kubectl` commands needed after the one-time setup.

**One-time setup:**
```bash
cd retailstore-platform
./scripts/setup-argocd-local.sh
# Access ArgoCD UI at https://localhost:8443 (after port-forward)
```

---

### Stage — AWS EKS (cost-optimised)

```
AWS (us-east-1)
└── Terraform creates:
    ├── VPC (3 subnets across 3 AZs, but only 1 NAT Gateway ← cost saving)
    ├── EKS cluster (managed control plane)
    ├── Node group (t3.medium, 2–4 nodes)
    ├── RDS MySQL (single-AZ, db.t3.medium)
    ├── ElastiCache Redis (single-node, cache.t3.micro)
    ├── DynamoDB tables (on-demand)
    ├── SQS queues
    └── ECR repositories (one per service)

└── Helm deploys onto EKS:
    └── namespace: retailstore
          gateway, experience, catalog, carts, checkout, orders
          Spring profile: stage
          Images: <account>.dkr.ecr.us-east-1.amazonaws.com/retailstore/<service>:<sha>
          replicaCount: 2, autoscaling: 2–4 pods
```

**Why only 1 NAT Gateway in stage?**
A NAT Gateway costs ~$45/month each. With 3 AZs you'd normally have 3 (one per AZ for
HA). Stage uses 1 to save ~$90/month — if the NAT Gateway AZ goes down, stage loses
internet egress, but that's acceptable for a non-production environment.

**Who creates what:**

| Component | Created by | File |
|---|---|---|
| VPC, EKS, RDS, ElastiCache | Terraform | `retailstore-infra/terraform/environments/stage/` |
| ECR repos | Terraform | `retailstore-infra/terraform/ecr/` |
| Docker images | Jenkins | `catalog-service/Jenkinsfile`, etc. |
| Microservices | ArgoCD (GitOps, triggered by Jenkins) | `helm/stage/*.yaml` |

**Deployment flow:**
```
1. terraform apply (one-time per environment, or on infra changes)
       ↓
2. Jenkins: docker build → docker push → ECR → update helm/stage/*.yaml → git push
       ↓
3. ArgoCD on EKS stage detects the tag change → helm upgrade automatically
```

---

### Prod — AWS EKS (full HA)

```
AWS (us-east-1)
└── Terraform creates (same modules as stage, different tfvars):
    ├── VPC (3 subnets, 3 NAT Gateways ← one per AZ for HA)
    ├── EKS cluster (managed control plane, v1.30+)
    ├── Node group (t3.xlarge, 3–10 nodes, spot + on-demand mix)
    ├── RDS MySQL Multi-AZ (db.t3.large, automatic failover)
    ├── ElastiCache Redis (cluster mode, cache.t3.small, 3 shards)
    ├── DynamoDB (on-demand, point-in-time recovery enabled)
    ├── SQS (with DLQs and redrive policy)
    └── ECR (image scanning enabled)

└── Helm deploys onto EKS:
    └── namespace: retailstore
          gateway, experience, catalog, carts, checkout, orders
          Spring profile: prod
          Images: <account>.dkr.ecr.us-east-1.amazonaws.com/retailstore/<service>:<sha>
          replicaCount: 3, autoscaling: 3–10 pods
          resources: higher limits (1Gi memory for gateway)
```

**What differs from stage (same modules, different tfvars):**

| Setting | Stage | Prod |
|---|---|---|
| NAT Gateways | 1 | 3 (one per AZ) |
| Node type | t3.medium | t3.xlarge |
| Node count | 2–4 | 3–10 (with spot instances) |
| RDS | Single-AZ, t3.medium | Multi-AZ, t3.large |
| ElastiCache | Single node | Cluster mode, 3 shards |
| Gateway replicas | 2 | 3 |
| Autoscaling max | 4 | 10 |
| Memory limits | 512Mi–768Mi | 768Mi–1Gi |

---

## Full Picture: Local → Stage → Prod

```
LOCAL                        STAGE                        PROD
─────────────────────        ─────────────────────────    ───────────────────────────
Docker Desktop k8s           Terraform → EKS              Terraform → EKS (HA)

k8s manifests:               AWS managed:                 AWS managed (HA):
  mysql pod                    RDS MySQL (single-AZ)        RDS MySQL (Multi-AZ)
  redis pod                    ElastiCache (single)         ElastiCache (cluster)
  keycloak pod                 Keycloak on EKS              Keycloak on EKS
  dynamodb-local               DynamoDB                     DynamoDB
  zipkin                       SQS                          SQS
                               X-Ray / Zipkin on EKS        AWS X-Ray

Deployed by: ArgoCD local    Deployed by: ArgoCD stage    Deployed by: ArgoCD prod
Helm values: helm/local/     Helm values: helm/stage/     Helm values: helm/prod/
Profile: local               Profile: stage               Profile: prod
Image: ECR/retailstore/<svc> Image: ECR/<sha>             Image: ECR/<sha>
Pull secret: ecr-pull-secret Node IAM role (auto)         Node IAM role (auto)
Replicas: 1                  Replicas: 2                  Replicas: 3
Autoscaling: off             Autoscaling: 2–4             Autoscaling: 3–10
```

---

## How the Spring Profile Connects Everything

Each environment passes a different `SPRING_PROFILES_ACTIVE` via Helm values. Spring Boot
then loads `application-<profile>.yml` which contains the environment-specific config:

```
application.yml           ← base config (loaded always)
application-local.yml     ← local overrides: ${MYSQL_HOST:localhost}, etc.
application-stage.yml     ← stage overrides: RDS endpoint, ElastiCache endpoint
application-prod.yml      ← prod overrides: prod RDS, prod Redis, SSL enabled
```

The `${VAR:default}` pattern means:
- **In k8s pods**: Helm injects the real value via env var (e.g., `MYSQL_HOST=mysql`)
- **In IntelliJ**: The default is used (e.g., `localhost`) when port-forward is active

---

## Summary: What to Use When

| Task | Tool | Command |
|---|---|---|
| Create EKS cluster, RDS, VPC on AWS | Terraform | `terraform apply` |
| Destroy AWS infra | Terraform | `terraform destroy` |
| Deploy/update a microservice | Helm | `helm upgrade --install` |
| Roll back a service to previous version | Helm | `helm rollback <release> <revision>` |
| Run infra locally (no AWS) | kubectl | `./scripts/install-infra-local.sh` |
| Build + push images locally | Docker | `./scripts/build-local.sh` |
| See what Helm would deploy (dry run) | Helm | `helm upgrade --install ... --dry-run` |
| See rendered k8s YAML from Helm chart | Helm | `helm template ...` |
| Check what's running in k8s | kubectl | `kubectl get all -n retailstore` |
