# RetailStore — Complete Platform Design Across All Environments

> **Purpose:** This document defines exactly what runs where, why, and how — for every
> cross-cutting concern in the platform, across all four environments. Read this before
> implementing any environment. Use it as the single source of truth for "should we enable
> X in environment Y?"

---

## Table of Contents

1. [Industry Environment Philosophy](#1-industry-environment-philosophy)
2. [Environment Overview & Decision](#2-environment-overview--decision)
3. [Concerns Matrix — Quick Reference](#3-concerns-matrix--quick-reference)
4. [Architecture Diagrams per Environment](#4-architecture-diagrams-per-environment)
5. [Per-Concern Deep Dive (32 concerns)](#5-per-concern-deep-dive)
6. [Testing Strategy per Environment](#6-testing-strategy-per-environment)
7. [CI/CD Pipeline Design](#7-cicd-pipeline-design)
8. [Infrastructure Design (Terraform + Helm)](#8-infrastructure-design)
9. [Spring Boot Profile Strategy](#9-spring-boot-profile-strategy)
10. [Cost Estimates](#10-cost-estimates)
11. [Implementation Order](#11-implementation-order)

---

## 1. Industry Environment Philosophy

### How Top Companies Structure Environments

| Company | Environments | Key Differentiator |
|---|---|---|
| **Amazon** | local → beta (per-team) → gamma → prod | Separate AWS account per env; gamma = production-like traffic with synthetic load |
| **Walmart** | local → dev → perf → staging → prod | Has a dedicated **perf** environment for load testing under production-like conditions |
| **Netflix** | local → test → e2e → prod (canary) | No staging — canary releases to 1% of prod traffic, then 10%, then 100% |
| **JPMorgan / Financial** | local → dev → SIT → UAT → prod | Strict separation: SIT = System Integration, UAT = User Acceptance (compliance-gated) |
| **Zalando** | local → dev → staging → prod | Blue/green at staging and prod; every deploy goes to staging first |
| **Airbnb** | local → dev → canary → prod | Canary is 5% of prod traffic; monitoring gates promotion |
| **Google** | local → dev → staging → prod (10% canary) | All envs use identical Kubernetes config; only replicas and resource limits differ |

### What This Means for RetailStore

You are building a **learning project with production standards**. The right model for you is:

```
local (k3s on Rancher Desktop) → dev (k3s on EC2) → stage (AWS EKS minimal) → prod (AWS EKS full HA)
```

This mirrors Google/Zalando: same configs, same code, same patterns — only the infra sizing
and feature toggle states change. One rule: **no feature that is "off by default" in prod
gets switched on in local without a documented reason.**

---

## 2. Environment Overview & Decision

### 2.1 The Four Environments

```
┌─────────────────────────────────────────────────────────────────────────────────┐
│                          ENVIRONMENT PROGRESSION                                 │
│                                                                                  │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐    ┌───────────────┐  │
│  │    LOCAL      │    │     DEV       │    │    STAGE      │    │     PROD      │  │
│  │  (dev profile)│───►│  (dev profile)│───►│  (stage)      │───►│   (prod)      │  │
│  │               │    │               │    │               │    │               │  │
│  │ k3s on Rancher│    │ k3s on EC2    │    │ AWS EKS       │    │ AWS EKS       │  │
│  │ Desktop (Mac) │    │ all services  │    │ minimal size  │    │ full HA size  │  │
│  │ Local images  │    │ ECR images    │    │               │    │               │  │
│  └──────────────┘    └──────────────┘    └──────────────┘    └───────────────┘  │
│  Rancher Desktop        k3s (t3.xlarge)     EKS t3.large        EKS m5.large    │
│  MySQL 8 (Bitnami)      MySQL 8 (Bitnami)   RDS MySQL single-AZ RDS MySQL HA    │
│  Keycloak-k3s           Keycloak-k3s        Keycloak-EKS        Keycloak-EKS HA │
│  Redis rate limit       Redis rate limit    Redis rate limit    Redis rate limit  │
│  Zipkin tracing         Zipkin tracing      Full observability  Full + alerting  │
└─────────────────────────────────────────────────────────────────────────────────┘
```

### 2.2 What Changes vs What Stays the Same

| Category | Stays the Same | Changes per Environment |
|---|---|---|
| **Code** | 100% identical JAR/Docker image | Nothing — one artifact |
| **Spring profile** | Base `application.yml` | Profile-specific overrides |
| **Auth flow** | OAuth2 PKCE + Client Credentials | Keycloak location + client secrets |
| **Patterns** | Circuit breaker, retry, rate limit | Thresholds and window sizes |
| **APIs** | All REST endpoints, all contracts | Nothing |
| **Infra type** | Same AWS services | Instance sizes, replica counts, AZ count |
| **Observability** | Same metrics/logs format | Destination (local console vs CloudWatch) |

---

## 3. Concerns Matrix — Quick Reference

Legend: ✅ Full | ⚡ Simplified | 🔇 Disabled | ❌ Not applicable

| Concern | LOCAL | DEV | STAGE | PROD |
|---|:---:|:---:|:---:|:---:|
| Authentication (Keycloak) | ⚡ local Docker | ⚡ k3s Bitnami | ✅ EKS Bitnami | ✅ EKS HA |
| Authorization (JWT scopes) | ✅ | ✅ | ✅ | ✅ |
| API Gateway | ✅ | ✅ | ✅ ALB+GW | ✅ ALB+GW |
| Load Balancing (L7) | ❌ | ❌ | ✅ ALB | ✅ ALB |
| Rate Limiting | 🔇 off | ✅ Redis (k3s) | ✅ ElastiCache | ✅ ElastiCache |
| Circuit Breaker | ✅ loose | ✅ normal | ✅ tight | ✅ tight |
| Retry with Backoff | ✅ 2 attempts | ✅ 3 attempts | ✅ 3 attempts | ✅ 3 attempts |
| Timeout (per call) | ✅ 5s | ✅ 5s | ✅ 3s | ✅ 3s |
| Bulkhead | 🔇 | ⚡ thread pool | ✅ thread pool | ✅ thread pool |
| Caching L1 (in-process) | ✅ Spring Cache | ✅ Spring Cache | ✅ Spring Cache | ✅ Spring Cache |
| Caching L2 (Redis) | 🔇 | ✅ Redis (k3s) | ✅ ElastiCache | ✅ ElastiCache |
| Service Discovery | ⚡ hardcoded | ⚡ k3s ClusterIP DNS | ✅ K8s DNS | ✅ K8s DNS |
| Config Management | application.yml | k8s ConfigMap (Helm) | ConfigMap | Secrets Manager |
| Secret Management | .env / env vars | k8s env vars (Helm) | K8s Secrets | AWS Secrets Mgr |
| Database | H2 / in-memory | MySQL 8 (k3s Bitnami) | RDS MySQL single-AZ | RDS MySQL HA |
| DB Migration (Flyway) | ✅ | ✅ | ✅ | ✅ |
| Message Queue (SQS) | 🔇 disabled | ⚡ LocalStack (k3s) | ✅ AWS SQS | ✅ AWS SQS |
| Structured Logging | ⚡ console | ✅ console (k3s stdout) | ✅ CloudWatch | ✅ CloudWatch |
| Distributed Tracing | 🔇 | ⚡ Zipkin (k3s) | ✅ AWS X-Ray | ✅ AWS X-Ray |
| Metrics (Prometheus) | ✅ /actuator | ✅ Actuator (k3s) | ✅ CloudWatch | ✅ CloudWatch + Grafana |
| Health Checks (probes) | ✅ Actuator | ✅ K8s probes (k3s) | ✅ K8s probes | ✅ K8s probes |
| Graceful Shutdown | ✅ | ✅ | ✅ | ✅ |
| CORS | ✅ | ✅ | ✅ | ✅ |
| TLS / HTTPS | 🔇 HTTP | 🔇 HTTP | ✅ ACM cert | ✅ ACM cert |
| CDN (CloudFront) | ❌ | ❌ | ⚡ optional | ✅ |
| WAF | ❌ | ❌ | ⚡ basic rules | ✅ full ruleset |
| DDoS Protection | ❌ | ❌ | ✅ Shield Std | ✅ Shield Std |
| Service Mesh (mTLS) | ❌ | ❌ | ❌ optional | ⚡ Istio planned |
| Autoscaling (HPA) | ❌ | ❌ | 🔇 disabled | ✅ enabled |
| Cluster Autoscaler | ❌ | ❌ | 🔇 fixed nodes | ✅ enabled |
| Pod Disruption Budget | ❌ | ❌ | ❌ | ✅ |
| Blue/Green Deploy | ❌ | ❌ | ⚡ rolling | ✅ ArgoCD Rollouts |
| Audit Logging | 🔇 | ⚡ console | ✅ CloudWatch | ✅ CloudTrail + CW |
| Feature Flags | ❌ | ❌ | ⚡ config-based | ✅ Unleash/config |
| API Docs (Swagger) | ✅ | ✅ | ✅ | 🔇 disabled |
| Contract Testing | ❌ | ❌ | ✅ CI gate | ✅ CI gate |
| Chaos Engineering | ❌ | ❌ | ❌ | ⚡ periodic FIS |

---

## 4. Architecture Diagrams per Environment

### 4.1 LOCAL (default profile)

```
Developer Machine
─────────────────────────────────────────────────────────────────────
  Browser (localhost:3000)
       │
       │ HTTP (no TLS)
       ▼
  api-gateway (localhost:8080)          ← mvn spring-boot:run
  └── GlobalJwtFilter                   ← validates Keycloak JWT
  └── Rate Limiting: DISABLED           ← Redis not wired locally
  └── Circuit Breaker: Resilience4j     ← in-memory state
  └── Routes →
       ├── experience (localhost:8086)  ← mvn spring-boot:run
       │    └── CatalogClient           ← HTTP, service token
       │    └── CartClient              ← HTTP, service token
       ├── catalog  (localhost:8081)    ← mvn spring-boot:run / in-memory store
       ├── cart     (localhost:8082)    ← mvn spring-boot:run
       ├── checkout (localhost:8083)    ← mvn spring-boot:run
       └── orders   (localhost:8084)    ← mvn spring-boot:run / H2

  ─── docker-compose.infra-only.yml ─────────────────────────────────
  │  keycloak     (localhost:8180)      ← OAuth2/OIDC IdP
  │  redis        (localhost:6379)      ← checkout sessions only
  │  dynamodb-local (localhost:8000)    ← cart storage
  └──────────────────────────────────────────────────────────────────

  Data stores:
    catalog  → in-memory (Java Map)
    cart     → DynamoDB Local (Docker)
    checkout → Redis (Docker)
    orders   → H2 in-memory
```

### 4.2 DEV (k3s on EC2)

```
Your Laptop                               EC2 t3.xlarge
────────────────────────────────          ──────────────────────────────────────────────
                                          k3s cluster  (Namespace: retailstore)
  Browser / IntelliJ                      ┌────────────────────────────────────────────┐
       │                                  │  gateway Pod (port 30080 NodePort)          │
       │  kubectl port-forward            │  └── GlobalJwtFilter (JWKS→keycloak:8180)  │
       │  or EC2 NodePort:30080           │  └── Rate Limiting → redis-master:6379      │
       ▼                                  │  └── Routes →                               │
  IntelliJ (SPRING_PROFILES_ACTIVE=dev)   │       ├── experience:8080                  │
  └── connects via kubectl port-forward   │       │    └── Bearer service token         │
      to infra on localhost:              │       ├── catalog:8080                      │
        MySQL       → localhost:3306      │       ├── carts:8080                        │
        Redis       → localhost:6379      │       ├── checkout:8080                     │
        Keycloak    → localhost:8180      │       └── orders:8080                       │
        DynamoDB    → localhost:8000      │                                             │
        LocalStack  → localhost:4566      │  Infrastructure Pods:                       │
        Zipkin      → localhost:9411      │    mysql (Bitnami 11.1.14)                 │
                                          │    redis-master (Bitnami 20.1.7)           │
  Scripts:                               │    keycloak (Bitnami 22.2.1, realm import) │
    start-dev.sh   → start EC2 + deploy  │    dynamodb-local:8000                     │
    stop-dev.sh    → undeploy + stop EC2 │    localstack:4566 (SQS)                   │
    build-push.sh  → ECR image push      │    zipkin:9411                             │
    port-forward.sh→ infra to localhost  │                                             │
                                          │  Databases (MySQL on k3s):                 │
  ECR (image registry):                  │    catalogdb  (catalog_user)               │
    retailstore/catalog                  │    ordersdb   (orders_user)                │
    retailstore/orders                   │    keycloakdb (keycloak_user)              │
    retailstore/carts                    │                                             │
    retailstore/checkout                 │  Tracing: Zipkin (100% sampling)           │
    retailstore/experience               └────────────────────────────────────────────┘
    retailstore/gateway
```

**Key design decision:** Dev uses k3s on EC2 instead of local Docker Compose so that:
- The dev environment uses the same container orchestrator as stage/prod (Kubernetes)
- MySQL 8 in dev replicates RDS MySQL in stage/prod (not H2 or PostgreSQL)
- Helm charts used in dev are the same charts deployed to stage/prod (only values differ)
- EC2 can be stopped when not working to control cost (~$30/month at 8hr/day)
- IntelliJ connects to k3s infra via `kubectl port-forward` using the `localhost:port` defaults
  in `application-dev.yml` (`${MYSQL_HOST:localhost}`, `${REDIS_HOST:localhost}`, etc.)

### 4.3 STAGE (AWS EKS — minimal)

```
Internet
    │
    ▼
Route53 (stage.retailstore.com)
    │
    ▼
AWS ALB  ←── ACM TLS cert (HTTPS)
    │         WAF: basic rules (OWASP managed ruleset)
    │         Shield Standard (free, always-on)
    ▼
EKS Cluster (2x t3.large nodes, single AZ us-east-1a)
  ├── Namespace: retailstore-stage
  │
  ├── api-gateway Pod (1 replica)
  │    └── GlobalJwtFilter → Keycloak (in-cluster)
  │    └── Rate Limiting → ElastiCache Redis
  │    └── Circuit Breaker (Resilience4j)
  │
  ├── experience Pod (1 replica)
  ├── catalog Pod (1 replica)
  ├── cart Pod (1 replica)
  ├── checkout Pod (1 replica)
  ├── orders Pod (1 replica)
  └── keycloak Pod (1 replica, DB: RDS MySQL)
  
  AWS Managed Services:
    ElastiCache Redis (cache.t3.micro, single node)
    RDS MySQL 8.0 (db.t3.medium, single-AZ)
    DynamoDB (on-demand billing)
    SQS (standard queue)
    ECR (image registry)
    CloudWatch (logs + metrics)
    AWS X-Ray (distributed tracing)
    Secrets Manager (all secrets)
```

### 4.4 PROD (AWS EKS — full HA)

```
Internet
    │
    ▼
CloudFront CDN ←── WAF (OWASP + custom rules)
    │               DDoS: Shield Standard
    │
    ▼
Route53 (retailstore.com) → ALB (multi-AZ) ←── ACM TLS
    │
    ▼
EKS Cluster (3 AZs, 3–9 nodes m5.large)
  ├── Namespace: retailstore-prod
  │
  ├── api-gateway (2 replicas, HPA 2–5)
  │    └── GlobalJwtFilter → Keycloak (HA, 2 replicas)
  │    └── Rate Limiting → ElastiCache Redis Cluster
  │    └── Circuit Breaker (tight thresholds)
  │
  ├── experience  (2 replicas, HPA 2–8)
  ├── catalog     (2 replicas, HPA 2–6)
  ├── cart        (2 replicas, HPA 2–6)
  ├── checkout    (2 replicas, HPA 2–4)
  ├── orders      (2 replicas, HPA 2–4)
  └── keycloak    (2 replicas + PodDisruptionBudget)
  
  Pod Disruption Budgets: minAvailable=1 for all services
  Topology Spread: pods spread across 3 AZs

  AWS Managed Services:
    ElastiCache Redis Cluster (cache.r6g.large, 2 nodes, multi-AZ)
    RDS Aurora MySQL 8.0 (db.r5.large, Multi-AZ, read replica)
    DynamoDB (on-demand, point-in-time recovery enabled)
    SQS (standard queue + DLQ)
    ECR (image registry + vulnerability scanning enabled)
    CloudWatch (logs + metrics + alarms + dashboards)
    AWS X-Ray (distributed tracing + service map)
    Secrets Manager (rotation enabled, 30-day schedule)
    CloudTrail (audit log of all API calls)
    Cluster Autoscaler (scales 3–9 nodes)
    ArgoCD (GitOps deployment + canary rollouts)
```

---

## 5. Per-Concern Deep Dive

---

### Concern 1 — Authentication & Authorization

**What it is:** Verifying who the caller is (AuthN) and what they can do (AuthZ).

**Industry reality:**
- Amazon/Walmart: OAuth2 + OIDC with an internal IdP (similar to Keycloak)
- Financial corps: Keycloak with LDAP/Active Directory federation; strict RBAC; token expiry 5 min
- Netflix: OAuth2 service tokens; no user-facing JWT in most internal services

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **IdP** | Keycloak (local Docker) | Keycloak 25 (k3s Bitnami) | Keycloak 25 (EKS Bitnami, 1 pod) | Keycloak 25 (EKS Bitnami, 2 pods + PDB) |
| **Realm** | `retailstore` | `retailstore` | `retailstore` | `retailstore` |
| **User login** | PKCE (Authorization Code) | PKCE | PKCE | PKCE |
| **M2S auth** | Client Credentials | Client Credentials | Client Credentials | Client Credentials |
| **Token TTL** | 300s (5 min) | 300s | 300s | 300s |
| **Refresh TTL** | 1800s (30 min) | 1800s | 1800s | 1800s |
| **Refresh rotation** | Yes | Yes | Yes | Yes |
| **Token validation** | JWKS at gateway | JWKS at gateway | JWKS at gateway | JWKS at gateway |
| **Downstream authz** | X-User-* headers | X-User-* headers | X-User-* headers | X-User-* headers |
| **Client secrets** | Plaintext defaults in application.yml | k8s env vars in Helm dev values | K8s Secrets (optional: true) | AWS Secrets Manager |
| **Brute force** | Enabled (10 failures) | Enabled | Enabled | Enabled (5 failures) |
| **Token revocation** | Session invalidation | Session invalidation | Session invalidation | Session invalidation |
| **LDAP federation** | No | No | No | Optional (enterprise add-on) |

**Spring Boot config difference:**
```yaml
# local (default profile) — application.yml
spring.security.oauth2.resourceserver.jwt.jwk-set-uri:
  http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}/realms/retailstore/protocol/openid-connect/certs

# dev profile — application-dev.yml (same pattern; k3s injects KEYCLOAK_HOST=keycloak)
spring.security.oauth2.resourceserver.jwt.jwk-set-uri:
  http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}/realms/retailstore/protocol/openid-connect/certs

# stage / prod — application-stage.yml / application-prod.yml
# KEYCLOAK_JWKS_URI injected from Helm values → K8s ConfigMap
spring.security.oauth2.resourceserver.jwt.jwk-set-uri: ${KEYCLOAK_JWKS_URI}
```

**Do we need this in all environments?** Yes — skip it in any env and you lose the ability to
test your security model. Auth bugs in prod are the worst bugs.

---

### Concern 2 — API Gateway

**What it is:** Single entry point for all external traffic. Handles routing, auth enforcement,
rate limiting, circuit breaking, CORS, and header enrichment.

**Industry reality:**
- Amazon: API Gateway + ALB; internal services bypass the public gateway
- Walmart: Custom Kubernetes ingress controller + Spring Cloud Gateway
- Financial: Kong or NGINX Plus; strict audit trail on every route
- Netflix: Zuul (moving to Spring Cloud Gateway)

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | Spring Cloud Gateway (reactive) | Spring Cloud Gateway | SCG + AWS ALB | SCG + AWS ALB |
| **TLS termination** | None | None | ALB (ACM cert) | ALB (ACM cert) |
| **JWT validation** | GlobalJwtFilter | GlobalJwtFilter | GlobalJwtFilter | GlobalJwtFilter |
| **Routing** | application.yml routes | application.yml + Helm dev values | application.yml + Helm stage values | application.yml + Helm prod values |
| **Public paths** | /actuator/**, /fallback/** | Same | Same | Same + /api-docs disabled |
| **Replicas** | 1 (mvn run) | 1 (k3s pod) | 1 (fixed) | 2 (HPA 2–5) |
| **Timeouts** | 5s connect, 10s response | 5s / 10s | 3s / 8s | 3s / 8s |

**Do we need this in all environments?** Yes. The gateway is what makes this a realistic
microservices architecture. Running services directly without a gateway is a local-only shortcut.

---

### Concern 3 — Load Balancing & Traffic Ingress

**What it is:** Distributing incoming traffic across multiple instances of a service.

**Industry reality:**
- Layer 4 (TCP): AWS NLB — used for non-HTTP workloads
- Layer 7 (HTTP/HTTPS): AWS ALB — used for all web traffic; routes by path/host/header
- In-cluster L7: Kubernetes Service (ClusterIP/NodePort) + Ingress Controller (Nginx or ALB Ingress)
- Amazon uses ALB for external; internal uses K8s Service
- Financial corps: F5 BIG-IP hardware LB + software LB inside cluster

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **External LB** | None (direct port) | NodePort 30080 (gateway) | AWS ALB (1 AZ) | AWS ALB (3 AZs) |
| **Internal LB** | None | k3s ClusterIP DNS | K8s ClusterIP Service | K8s ClusterIP Service |
| **Ingress Controller** | N/A | k3s Traefik (unused) | AWS Load Balancer Controller | AWS Load Balancer Controller |
| **Health check** | None | k3s readiness probe | ALB target group health | ALB target group health |
| **Sticky sessions** | N/A | N/A | None (stateless services) | None (stateless services) |
| **Connection draining** | N/A | N/A | 30s deregistration delay | 30s deregistration delay |

**Do we need this in local/dev?** No — single instance, no distribution needed.
Stage and prod both need it. Dev uses k3s ClusterIP for internal service-to-service DNS (same
`fullnameOverride` names as stage/prod: `catalog`, `carts`, `orders`, etc.).

---

### Concern 4 — Rate Limiting

**What it is:** Capping how many requests a single IP/user/client can make per time window.
Prevents abuse, DDoS at the application layer, and runaway clients.

**Industry reality:**
- Amazon: Per API-key, per IP, per account — different tiers (free vs premium)
- Walmart: Per IP at gateway, per user-ID for checkout and order APIs
- Financial: Strict rate limiting on all endpoints; violations are logged and investigated
- Netflix: Adaptive rate limiting that backs off under load (not just fixed)

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | DISABLED | Spring Cloud Gateway RequestRateLimiter | Same | Same |
| **Backend** | N/A | Redis (k3s Bitnami) | ElastiCache | ElastiCache |
| **Key** | N/A | IP address | IP address + user sub | IP address + user sub |
| **catalog replenish** | N/A | 200 req/s | 150 req/s | 200 req/s |
| **catalog burst** | N/A | 400 | 300 | 400 |
| **checkout replenish** | N/A | 20 req/s | 10 req/s | 20 req/s |
| **checkout burst** | N/A | 40 | 20 | 40 |
| **Over-limit response** | N/A | HTTP 429 | HTTP 429 | HTTP 429 + Retry-After header |
| **Per-user limiting** | N/A | No | Yes | Yes |

**Why disabled locally?** Redis is not running unless you start it manually. H2 catalog
doesn't need rate limiting for a single developer. Enable it in local only if you're
specifically testing rate limit behavior.

---

### Concern 5 — Circuit Breaker

**What it is:** Automatically stops calling a service that is failing, gives it time to
recover, then tests it again (CLOSED → OPEN → HALF-OPEN pattern).

**Industry reality:**
- Netflix invented the circuit breaker pattern (Hystrix, now Resilience4j)
- Amazon: every service call has a circuit breaker; dashboards show OPEN circuits
- Walmart: circuit breaker + fallback data (cached or degraded response)
- Financial: circuit breakers prevent cascade failures during peak periods (Black Friday, market open)

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | Resilience4j (in-process) | Resilience4j | Resilience4j | Resilience4j |
| **Storage** | In-memory | In-memory | In-memory per pod | In-memory per pod |
| **Sliding window** | 5 calls | 10 calls | 10 calls | 20 calls |
| **Failure threshold** | 60% | 50% | 50% | 40% |
| **Wait in OPEN** | 5s | 10s | 10s | 15s |
| **Permitted in HALF-OPEN** | 2 | 3 | 3 | 5 |
| **Fallback** | Empty response | Empty response | Cached response | Cached response |
| **Slow call threshold** | 5s | 3s | 3s | 2s |
| **Slow call rate** | N/A | 80% triggers | 70% triggers | 60% triggers |

**Important note:** In production, a circuit breaker that is too sensitive will trip on
normal transient errors and cause unnecessary downtime. Tune stage first, then prod.

**Fallback strategy per service:**
- catalog: return empty product list from cache
- cart: return empty cart (allow user to continue browsing)
- checkout: return 503 with "try again" message — do NOT degrade checkout
- orders: return 503 — do NOT degrade order placement (financial integrity)

---

### Concern 6 — Retry with Exponential Backoff

**What it is:** Automatically re-attempting a failed call a fixed number of times, with
increasing delays between attempts (to avoid thundering herd).

**Industry reality:**
- Standard pattern: max 3 retries, exponential backoff (100ms → 200ms → 400ms), jitter
- Amazon SDK has retry built into every AWS SDK call (3 retries, default)
- Financial: retries only on idempotent operations (GET, PUT with idempotency key)
- Critical rule: **NEVER retry non-idempotent operations** (POST /orders, POST /checkout/submit)

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | Resilience4j Retry | Resilience4j Retry | Resilience4j Retry | Resilience4j Retry |
| **Max attempts** | 2 | 3 | 3 | 3 |
| **Wait duration** | 100ms | 200ms | 200ms | 200ms |
| **Backoff multiplier** | None | 2x (200→400→800ms) | 2x | 2x |
| **Jitter** | None | ±50ms | ±50ms | ±100ms |
| **Retryable on** | 503, 504, IOException | 503, 504, IOException | Same | Same |
| **NOT retried** | POST /orders, POST /checkout/submit | Same | Same | Same |

**Config to add (Resilience4j):**
```yaml
resilience4j:
  retry:
    instances:
      catalog-retry:
        max-attempts: 3
        wait-duration: 200ms
        exponential-backoff-multiplier: 2
        retry-exceptions:
          - java.io.IOException
          - org.springframework.web.reactive.function.client.WebClientException
      order-retry:
        max-attempts: 1  # orders are non-idempotent — no retry
```

---

### Concern 7 — Timeout

**What it is:** Maximum time to wait for a downstream call before giving up.
Without timeouts, a slow service can exhaust all your threads.

**Industry reality:**
- Amazon: every HTTP client has explicit connect timeout (1s) and read timeout (3s)
- Netflix: timeout per service, documented in a service registry
- Financial: extremely tight timeouts (500ms for auth, 1s for read, 3s for writes)

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **WebClient connect** | 3000ms | 3000ms | 2000ms | 2000ms |
| **WebClient response** | 5000ms | 5000ms | 3000ms | 3000ms |
| **Gateway route timeout** | Not configured | 10s | 8s | 8s |
| **DB query timeout** | Not configured | Not configured | 5s (JPA) | 3s (JPA) |
| **Redis timeout** | 2000ms | 2000ms | 1000ms | 1000ms |

**Already configured in WebClientConfig.java** — just need to tighten for stage/prod via
environment-specific Helm values injected as env vars.

---

### Concern 8 — Bulkhead (Thread Pool Isolation)

**What it is:** Separate thread pools per downstream service so that a slow service X
doesn't exhaust ALL threads and block calls to healthy service Y. Named after ship bulkheads
that contain flooding to one compartment.

**Industry reality:**
- Netflix Hystrix (now Resilience4j) pioneered this
- Amazon: separate connection pools per dependency
- Financial: critical for audit/risk systems — payment thread pool is isolated from everything

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | Disabled | Resilience4j Bulkhead | Resilience4j Bulkhead | Resilience4j Bulkhead |
| **Type** | N/A | ThreadPool Bulkhead | ThreadPool Bulkhead | ThreadPool Bulkhead |
| **core-thread-pool-size** | N/A | 10 | 10 | 20 |
| **max-thread-pool-size** | N/A | 20 | 20 | 40 |
| **queue-capacity** | N/A | 100 | 100 | 200 |
| **Rejected calls** | N/A | BulkheadFullException → 503 | Same | Same |

**Do we need this in local?** No — single developer, no concurrency pressure.
Add to dev and above when building resilience.

---

### Concern 9 — Caching

**What it is:** Storing results in fast memory so repeated calls don't hit the database.
Two levels: L1 = in-process JVM heap, L2 = Redis (shared across pods).

**Industry reality:**
- Amazon product catalog: L1 in-process (milliseconds) + L2 ElastiCache (tens of ms) + L3 DynamoDB DAX
- Walmart inventory: Redis cluster; cache TTL 60s; stale is acceptable for availability
- Financial quotes: no caching (always real-time); positions: Redis with 5s TTL

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **L1 (in-process)** | Spring Cache (ConcurrentHashMap) | Spring Cache | Spring Cache | Spring Cache |
| **L2 (distributed)** | Disabled | Redis (k3s Bitnami) | ElastiCache (cache.t3.micro) | ElastiCache (cache.r6g.large, 2 nodes) |
| **catalog products** | L1, 60s TTL | L1+L2, 60s TTL | L1+L2, 60s TTL | L1+L2, 60s TTL |
| **catalog product by ID** | L1, 120s TTL | L1+L2, 120s TTL | L1+L2, 120s TTL | L1+L2, 120s TTL |
| **checkout session** | Redis | Redis | Redis | Redis |
| **rate limit counters** | None | Redis | Redis | Redis |
| **Auth tokens (M2S)** | In-process volatile | In-process volatile | In-process volatile | In-process volatile |
| **Cache eviction** | Manual or TTL | TTL | TTL | TTL |
| **Cache aside** | Yes (app manages) | Yes | Yes | Yes |

**Catalog service needs `spring-boot-starter-cache` + Redis config added for dev+:**
```yaml
# application-dev.yml / stage / prod
spring:
  cache:
    type: redis
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 1000ms
```
**Local uses `spring.cache.type: simple` (already configured).**

---

### Concern 10 — Service Discovery

**What it is:** How services find each other's network address dynamically.

**Industry reality:**
- Old school: Eureka (Netflix) — services register themselves, others poll
- Modern: Kubernetes DNS — K8s gives every Service a stable DNS name; no Eureka needed
- Amazon internally: AWS Cloud Map + DNS
- Recommendation: **don't use Eureka if you're on Kubernetes** — K8s DNS replaces it entirely

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Mechanism** | Hardcoded localhost URLs | k3s ClusterIP DNS (same names via fullnameOverride) | Kubernetes ClusterIP + DNS | Kubernetes ClusterIP + DNS |
| **Service URL** | http://localhost:8081 | http://catalog:8080 | http://catalog.retailstore.svc | http://catalog.retailstore.svc |
| **Dynamic registration** | None | None | K8s Service auto-registers | K8s Service auto-registers |
| **Health-aware routing** | None | None | K8s readiness probe removes unhealthy pods | Same |
| **Eureka** | NOT used | NOT used | NOT used | NOT used |

**Why no Eureka?** It was designed for VMs. On Kubernetes, the Service object IS the
service registry. Adding Eureka on top is redundancy and operational overhead.

---

### Concern 11 — Configuration Management

**What it is:** How environment-specific config values reach your services without
being baked into the Docker image.

**Industry reality:**
- Amazon: SSM Parameter Store (non-secret) + Secrets Manager (secrets) — accessed via SDK
- Walmart: Consul + K8s ConfigMaps
- Financial: HashiCorp Vault for everything; GitOps for non-secret config
- Netflix: Archaius (their own; being replaced by ConfigMaps)

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Non-secret config** | application.yml defaults | k8s ConfigMap via Helm dev values | K8s ConfigMap | K8s ConfigMap |
| **Secret config** | application.yml defaults | k8s env vars (Helm values, `optional: true`) | K8s Secrets (base64) | AWS Secrets Manager |
| **Database URL** | application.yml | helm/dev/catalog.yaml, orders.yaml | ConfigMap | Secrets Manager |
| **Keycloak URLs** | application.yml | helm/dev/*.yaml (KEYCLOAK_HOST, PORT) | ConfigMap | ConfigMap |
| **Client secrets** | application.yml | k8s env vars in Helm dev values | K8s Secret (optional) | Secrets Manager + External Secrets Operator |
| **Spring Config Server** | NOT used | NOT used | NOT used | NOT used |
| **Hot reload** | Restart | Restart | Rolling restart | Rolling restart |

**Why no Spring Cloud Config Server?** For this platform, K8s ConfigMaps + Secrets Manager
provide all the functionality with less operational overhead. Config Server adds another
service to manage and a single point of failure if not HA.

---

### Concern 12 — Secrets Management

**What it is:** Securely storing and distributing passwords, API keys, client secrets,
certificates — anything that must not appear in source code or logs.

**Industry reality:**
- Financial: HashiCorp Vault (gold standard); automatic rotation; audit log of every secret access
- Amazon: AWS Secrets Manager with automatic rotation via Lambda
- Google: Google Cloud Secret Manager
- Netflix: Confidant (their open-source tool)
- Rule: **never commit a secret to Git. Never log a secret. Never pass secrets as command-line args.**

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | application.yml defaults | k8s env vars via Helm dev values (dummy dev passwords) | K8s Secrets + Sealed Secrets | AWS Secrets Manager + External Secrets Operator |
| **Rotation** | Manual | Manual | Manual (quarterly) | Automated (30 days, Secrets Manager) |
| **Audit trail** | None | None | K8s audit log | CloudTrail + Secrets Manager access log |
| **Encryption at rest** | None | None | etcd encryption | Secrets Manager (KMS) |
| **Who can access** | Developer only | k3s cluster only | RBAC (ServiceAccount) | IAM role (IRSA — per pod) |
| **DB password** | application.yml | helm/dev/catalog.yaml (catalog_pass — dev dummy) | Sealed Secret | Secrets Manager → External Secrets |
| **Keycloak client secret** | application.yml | k8s Secret (optional: true in Helm chart) | K8s Secret | Secrets Manager |
| **JWT signing key** | Keycloak manages | Keycloak manages | Keycloak manages | Keycloak manages (RS256 keypair) |

**External Secrets Operator** (prod): K8s operator that syncs secrets from AWS Secrets Manager
into K8s Secret objects automatically. Pods read K8s secrets as env vars — no AWS SDK in app code.

---

### Concern 13 — Database per Service

**What it is:** Each microservice owns its own data store. No service can directly query
another service's database. This is the most important microservices data principle.

**Industry reality:**
- Amazon DynamoDB (cart, catalog), RDS (orders, identity), ElastiCache (sessions)
- Walmart: PostgreSQL + DynamoDB mix, similar to RetailStore
- Financial: Oracle/DB2 (legacy) or PostgreSQL (modern) per bounded context; strict schema ownership

| Service | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **catalog** | H2 in-memory | MySQL 8 `catalogdb` (k3s Bitnami) | RDS MySQL (db.t3.medium) | RDS Aurora MySQL Multi-AZ |
| **cart** | DynamoDB Local | DynamoDB Local (k3s) | DynamoDB on-demand | DynamoDB on-demand + PITR |
| **checkout** | Redis (session) | Redis (k3s Bitnami) | ElastiCache Redis | ElastiCache Redis Cluster |
| **orders** | H2 in-memory | MySQL 8 `ordersdb` (k3s Bitnami) | RDS MySQL (db.t3.medium) | RDS Aurora MySQL Multi-AZ |
| **keycloak** | H2 (embedded) | MySQL 8 `keycloakdb` (k3s shared) | RDS MySQL shared | RDS MySQL dedicated |

**Flyway database migration** — all envs. See Concern 14. Note: Flyway uses MySQL 8 dialect in dev/stage/prod
(requires both `flyway-core` and `flyway-mysql` dependencies).

---

### Concern 14 — Database Migration (Flyway)

**What it is:** Versioned SQL scripts that evolve your schema in a controlled, repeatable way.
Spring Boot runs migrations at startup before the app accepts traffic.

**Industry reality:**
- Netflix: Flyway on every service; migrations run as a Kubernetes Job before deployment
- Amazon: Schema changes via CloudFormation or Flyway; blue/green DB deployments for zero-downtime
- Financial: Very strict — schema changes require DBA approval, tested in lower envs first
- Rule: **ddl-auto: create-drop or update are development-only. Never use them in stage/prod.**

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | Flyway | Flyway | Flyway | Flyway |
| **ddl-auto** | `update` (H2, acceptable for local) | `validate` | `validate` | `validate` |
| **Migration location** | `classpath:db/migration` | Same | Same | Same |
| **Naming** | `V1__init.sql`, `V2__add_index.sql` | Same | Same | Same |
| **Rollback strategy** | Undo script or new migration | Same | Same | Same |
| **Run as** | Application startup | Application startup | K8s Init Container | K8s Init Container |

**For catalog and orders — need to add Flyway:**
```xml
<dependency>
  <groupId>org.flywaydb</groupId>
  <artifactId>flyway-core</artifactId>
</dependency>
```
```yaml
# application-stage.yml, application-prod.yml
spring.jpa.hibernate.ddl-auto: validate
spring.flyway.enabled: true
spring.flyway.locations: classpath:db/migration
```

---

### Concern 15 — Message Queue / Event Streaming

**What it is:** Asynchronous communication between services. Decouples the producer from
the consumer; provides durability (messages survive restarts).

**Current state:** order-service has SQS disabled (`RETAIL_ORDER_MESSAGING_ENABLED=false`)

**Industry reality:**
- Amazon internal: SQS (simple, at-least-once) or SNS+SQS fanout; Kafka for high-throughput streams
- Walmart: Kafka for all real-time events (inventory, pricing); SQS for order workflows
- Financial: IBM MQ (legacy) or Apache Kafka (modern); guaranteed delivery is critical
- Netflix: Kafka for all event streaming; no SQS (they run on AWS but prefer Kafka)

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | Disabled | LocalStack SQS (k3s pod) | AWS SQS | AWS SQS |
| **Order placed event** | Disabled | Published to LocalStack SQS | Published to SQS | Published to SQS |
| **Dead letter queue** | N/A | Yes (`order-events-dev-dlq`) | Yes (3 retries before DLQ) | Yes (3 retries before DLQ) |
| **Visibility timeout** | N/A | 30s | 30s | 60s |
| **Queue type** | N/A | Standard | Standard | Standard |
| **Consumer** | N/A | OrderEventPublisher (SQS_ENDPOINT=http://localstack:4566) | OrderEventPublisher | OrderEventPublisher |
| **Monitoring** | N/A | N/A | CloudWatch metrics | CW + alarm on DLQ depth > 0 |
| **Kafka (future)** | N/A | N/A | N/A | Migrate when event volume justifies |

**Dev: LocalStack runs as a k3s Deployment with a k8s Job that creates queues at startup:**
```
k8s/dev/localstack.yaml  → Deployment + Service (port 4566) + init Job
  Job creates: order-events-dev, order-events-dev-dlq
SqsConfig.java reads SQS_ENDPOINT env var to override endpoint for LocalStack
helm/dev/orders.yaml injects: SQS_ENDPOINT=http://localstack:4566
```

---

### Concern 16 — Observability (Logging, Metrics, Tracing)

Observability has three pillars. A service is observable only when all three are present.

#### 16a — Structured Logging

**What it is:** JSON-formatted logs with consistent fields (timestamp, level, service, traceId,
userId, requestId) so they can be queried and correlated.

**Industry reality:**
- Amazon: all logs go to CloudWatch; every log line has correlation ID + user context
- Netflix: logs → Elasticsearch → Kibana (ELK)
- Financial: logs are immutable audit records; personally identifiable information is MASKED

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Format** | Human-readable colored (console) | Human-readable colored (console) | JSON (Logstash encoder) | JSON (Logstash encoder) |
| **Destination** | stdout / console | stdout → k3s node logging | CloudWatch Logs agent | CloudWatch Logs |
| **Log group** | N/A | N/A | `/retailstore/stage/<service>` | `/retailstore/prod/<service>` |
| **Correlation ID** | X-Correlation-Id header | Same | Same | Same |
| **Trace ID** | None | Zipkin trace ID (100% sampling) | Trace ID (10% sampling) | Trace ID (5% sampling) |
| **PII masking** | None | None | Mask email in logs | Mask PII (email, card, address) |
| **Log retention** | N/A | N/A | 30 days | 90 days |
| **Search** | grep | `kubectl logs -f deployment/X -n retailstore` | CloudWatch Insights | CloudWatch Insights + Grafana |

**logback-spring.xml** controls format per profile: `<springProfile name="dev">` outputs colored
console; `<springProfile name="stage,prod">` outputs JSON via `LogstashEncoder`.

**Add to pom.xml for JSON logs (logstash-logback-encoder):**
```xml
<dependency>
  <groupId>net.logstash.logback</groupId>
  <artifactId>logstash-logback-encoder</artifactId>
  <version>7.4</version>
</dependency>
```
**Use `logback-spring.xml` with profile-based appenders.**

#### 16b — Metrics

**What it is:** Numerical measurements over time (request rate, error rate, latency, JVM heap).
The RED method: Rate, Errors, Duration. The USE method: Utilization, Saturation, Errors.

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | Micrometer + Actuator | Micrometer + Actuator (k3s) | Micrometer → CloudWatch Metrics | Micrometer → CloudWatch + Grafana |
| **Endpoint** | /actuator/metrics, /actuator/prometheus | Same | Same | Same |
| **Dashboards** | None | Grafana (localhost:3001) | CloudWatch Dashboard | Grafana Cloud or self-hosted |
| **Key metrics** | None monitored | http_server_requests, jvm_memory | + circuit breaker state, cache hit rate | + all, plus custom business metrics |
| **Custom metrics** | None | None | orders_placed_total, cart_items_added | All |
| **Scrape interval** | N/A | 15s | 60s | 30s |
| **Alerting** | None | None | CloudWatch alarm: error rate > 5% | PagerDuty integration |

#### 16c — Distributed Tracing

**What it is:** Following a single request across multiple services by propagating a trace ID.
Essential for debugging "which service slowed down this request?"

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | None | Micrometer Tracing → Zipkin (k3s pod) | Micrometer Tracing → AWS X-Ray | AWS X-Ray |
| **Trace propagation** | None | W3C TraceContext headers | W3C TraceContext | W3C TraceContext |
| **Sampling** | N/A | 100% (`probability: 1.0` in application-dev.yml) | 10% (`probability: 0.1`) | 5% (`probability: 0.05`) |
| **Service map** | None | Zipkin UI (http://localhost:9411 via port-forward) | X-Ray Service Map | X-Ray Service Map |
| **Latency percentiles** | None | Zipkin | CloudWatch + X-Ray | CloudWatch + X-Ray |

**Tracing is already configured in all pom.xml files:**
```xml
<dependency>
  <groupId>io.micrometer</groupId>
  <artifactId>micrometer-tracing-bridge-brave</artifactId>
</dependency>
<dependency>
  <groupId>io.zipkin.reporter2</groupId>
  <artifactId>zipkin-reporter-brave</artifactId>
</dependency>
```
Zipkin endpoint set in application-dev.yml: `http://${ZIPKIN_HOST:localhost}:9411/api/v2/spans`

---

### Concern 17 — Health Checks & Kubernetes Probes

**What it is:** Declaring to the platform that your service is alive (liveness) and
ready to accept traffic (readiness).

**Industry reality:**
- Kubernetes requires both probes; services without them are restarted or receive traffic while starting
- Amazon best practice: readiness probe on /actuator/health/readiness, liveness on /actuator/health/liveness

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Endpoint** | /actuator/health | /actuator/health | /actuator/health/liveness, /readiness | Same |
| **K8s liveness probe** | N/A | N/A | httpGet /actuator/health/liveness, initialDelay=30s | Same |
| **K8s readiness probe** | N/A | N/A | httpGet /actuator/health/readiness, initialDelay=20s | Same |
| **Docker healthcheck** | Not configured | N/A (k3s probes used) | N/A (K8s probes replace) | N/A |
| **Startup probe** | N/A | N/A | httpGet /actuator/health/liveness, failureThreshold=12, period=10s | Same |
| **Already configured** | ✅ spring.boot.actuator | ✅ | ✅ Helm template adds probes | ✅ |

---

### Concern 18 — Graceful Shutdown

**What it is:** When a pod is asked to stop (SIGTERM), it first finishes in-flight requests,
then stops accepting new ones, then terminates. Without this, in-flight requests get 503.

**Already configured:** `server.shutdown: graceful` and `lifecycle.timeout-per-shutdown-phase: 30s`
in all services. This is correct and applies to all environments.

| | ALL ENVIRONMENTS |
|---|---|
| **Config** | `server.shutdown: graceful` + `lifecycle.timeout-per-shutdown-phase: 30s` |
| **K8s terminationGracePeriodSeconds** | 60s (must be > lifecycle timeout) |
| **K8s preStop hook** | `sleep 5` — gives time for ALB/K8s to route traffic away before shutdown begins |

---

### Concern 19 — CORS

**What it is:** Cross-Origin Resource Sharing — browser security policy that blocks
JavaScript on one domain from calling APIs on another domain.

**Already configured** in api-gateway `application.yml`. Applies to all environments via
different `ALLOWED_ORIGIN` env var.

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Allowed origin** | http://localhost:3000 | http://localhost:3000 | https://stage.retailstore.com | https://shop.retailstore.com |
| **Allowed methods** | GET POST PUT DELETE PATCH OPTIONS | Same | Same | Same |
| **Credentials** | true | true | true | true |
| **Config location** | api-gateway application.yml (ALLOWED_ORIGIN env var) | Same | Same | Same |

---

### Concern 20 — TLS / HTTPS

**What it is:** Encrypting traffic between client and server. Required for production.
Optional for local/dev (developer machine is trusted network).

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **External (browser→ALB)** | None (HTTP) | None (HTTP) | ACM certificate (auto-renew) | ACM certificate (auto-renew) |
| **ALB→Gateway** | N/A | N/A | HTTP (private VPC) | HTTP (private VPC) |
| **Service–to–Service (east-west)** | HTTP | HTTP | HTTP (VPC private) | HTTP (planned mTLS via Istio) |
| **Certificate management** | N/A | N/A | AWS Certificate Manager | AWS Certificate Manager |
| **TLS version** | N/A | N/A | TLS 1.2 minimum | TLS 1.3 preferred, 1.2 minimum |
| **HSTS header** | No | No | Yes (30 days) | Yes (1 year) |

---

### Concern 21 — CDN & Static Assets

**What it is:** Content Delivery Network serves static files (HTML, CSS, JS, images) from
edge locations close to the user, reducing latency and origin server load.

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Web storefront** | Vite dev server (localhost:3000) | Vite or Nginx container | CloudFront + S3 (optional) | CloudFront + S3 |
| **Product images** | In-memory/test URLs | In-memory/test URLs | S3 + CloudFront | S3 + CloudFront |
| **Cache-Control** | N/A | N/A | `public, max-age=86400` for assets | Same |
| **Cache invalidation** | N/A | N/A | Versioned filenames (Vite does this) | Same |
| **CloudFront distribution** | N/A | N/A | Optional (cost saving) | Yes |

---

### Concern 22 — WAF & DDoS Protection

**What it is:** WAF (Web Application Firewall) blocks malicious requests (SQL injection,
XSS, bot traffic). DDoS protection absorbs volumetric attacks.

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **WAF** | None | None | AWS WAF (basic managed ruleset) | AWS WAF (OWASP Top 10 + bot control + custom rules) |
| **DDoS** | None | None | Shield Standard (free, always-on) | Shield Standard |
| **Rate-based rules** | None | None | 2000 req/5min per IP | 1000 req/5min per IP |
| **Block on** | N/A | N/A | SQLi, XSS, known bad IPs | Same + custom retailstore rules |
| **Cost** | $0 | $0 | ~$15/month (WAF) | ~$30/month (WAF + bot control) |

---

### Concern 23 — Service Mesh / mTLS

**What it is:** Infrastructure layer (sidecar proxies) that encrypts all service-to-service
traffic (mTLS) and provides traffic policies, retries, and observability without changing
application code.

**Industry reality:**
- Google: Istio on all internal services (mandatory)
- Netflix: Envoy-based mesh for internal services
- Amazon: AWS App Mesh (deprecated) → migrating to EKS Pod Identity + network policies
- Financial: Istio or Linkerd — mTLS is a compliance requirement (PCI-DSS)

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Service Mesh** | None | None | None | Planned: Istio (optional Phase 2) |
| **mTLS** | None | None | None | Planned: Istio auto-mTLS |
| **East-west security** | Trust by design | Docker network isolation | K8s NetworkPolicy | K8s NetworkPolicy + Istio PeerAuthentication |
| **NetworkPolicy** | N/A | N/A | ✅ deny-all default + selective allow | Same |

**For now:** Client Credentials tokens (already implemented) provide application-level
M2S auth. NetworkPolicy provides network-level isolation. Istio is a Phase 2 addition.

---

### Concern 24 — Autoscaling

**What it is:** Automatically adding/removing pod replicas (HPA) or nodes (Cluster Autoscaler)
based on CPU, memory, or custom metrics.

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **HPA** | N/A | N/A | Disabled (fixed 1 replica) | Enabled (2–5 per service) |
| **HPA metric** | N/A | N/A | N/A | CPU 70% + custom RPS metric |
| **VPA** | N/A | N/A | N/A | Optional (right-sizing) |
| **Cluster Autoscaler** | N/A | N/A | Disabled (fixed 2 nodes) | Enabled (3–9 nodes) |
| **KEDA (event-driven)** | N/A | N/A | N/A | SQS consumer scaling (future) |
| **Scale-to-zero** | N/A | N/A | N/A | Not for core services (latency) |

---

### Concern 25 — Deployment Strategy

**What it is:** How new versions of services reach production without downtime.

**Industry reality:**
- Rolling update (default K8s): gradual pod replacement; ~30s downtime risk on breaking changes
- Blue/Green: two identical environments, switch traffic instantly; requires 2x resources
- Canary: route 1–10% of traffic to new version; monitor; promote or rollback
- Amazon: Blue/Green for stateful services, Rolling for stateless
- Netflix: Canary for everything — 1% → 10% → 100% with automated monitoring gates

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Strategy** | N/A | Helm upgrade (k3s rolling) | K8s Rolling Update | Argo Rollouts: Canary |
| **maxUnavailable** | N/A | N/A | 0 (zero downtime) | 0 |
| **maxSurge** | N/A | N/A | 1 | 1 |
| **Canary traffic** | N/A | N/A | N/A | 10% → 25% → 50% → 100% |
| **Rollback** | N/A | `helm rollback <release>` | `kubectl rollout undo` | Argo Rollouts abort |
| **Deployment tool** | IDE / mvn | `deploy-services.sh` (Helm + ECR) | kubectl + Helm | ArgoCD + Helm |

---

### Concern 26 — CI/CD Pipeline

**What it is:** Automated pipeline that builds, tests, packages, and deploys your code on
every commit.

**Industry reality:**
- Amazon: CodePipeline + CodeBuild + CodeDeploy
- Walmart: Jenkins + custom Kubernetes operator
- Most modern companies: GitHub Actions or GitLab CI → ECR → ArgoCD (GitOps)

```
                  GitHub Actions Pipeline
                  ─────────────────────────────────────────────────────
  git push    →   1. Build (mvn package -DskipTests)
                  2. Unit + Integration Tests (mvn test)
                  3. Docker build + push to ECR
                  4. Update Helm values.yaml with new image tag
                  5. git commit + push → triggers ArgoCD sync

                  Stage:
                  6. ArgoCD auto-syncs → deploys to stage EKS
                  7. Smoke tests (curl health check + 3 API calls)
                  8. If fail → auto rollback (Argo Rollouts)

                  Prod (requires manual approval):
                  9. GitHub Actions: "Approve to deploy prod?" → team lead approves
                  10. ArgoCD syncs prod → canary 10%
                  11. Automated monitoring gate (5 min): error rate < 1%?
                  12. If pass → 100%; If fail → rollback
```

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Trigger** | Manual (IDE/mvn) | `build-push.sh` + `deploy-services.sh` | Push to `develop` branch | Push to `main` branch (after PR) |
| **Build tool** | Maven | Maven + Docker (`--platform linux/amd64`) | GitHub Actions | GitHub Actions |
| **Test gate** | Skipped or manual | mvn test | mvn test (must pass) | mvn test + integration test (must pass) |
| **Image registry** | Local Docker | AWS ECR | AWS ECR | AWS ECR |
| **Deploy tool** | Direct run | Helm (`deploy-services.sh`) | kubectl/Helm | ArgoCD |
| **Approval gate** | N/A | N/A | None (auto-deploy) | Required (manual approval) |
| **Rollback** | N/A | `helm rollback <release>` | kubectl rollout undo | ArgoCD rollback |

---

### Concern 27 — Secrets Rotation

**What it is:** Automatically replacing secrets on a schedule to limit exposure if a secret
is compromised.

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Keycloak client secrets** | Never (dev values) | Never | Manual (quarterly) | Automatic (Secrets Manager, 30 days) |
| **DB passwords** | Never | Never | Manual (quarterly) | Automatic (Secrets Manager, 30 days) |
| **JWT signing keys** | Keycloak manages | Keycloak manages | Keycloak key rotation (manual) | Keycloak key rotation (automated) |
| **TLS certs** | N/A | N/A | ACM auto-renews | ACM auto-renews |

---

### Concern 28 — Audit Logging & Compliance

**What it is:** An immutable record of "who did what and when" — essential for financial
services, healthcare (HIPAA), and payment systems (PCI-DSS).

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Auth events** | Keycloak console | Keycloak console | Keycloak → CloudWatch | Keycloak → CloudWatch |
| **Order actions** | Application log | Application log | CloudWatch Logs | CloudWatch Logs + CloudTrail |
| **Admin actions** | None | None | K8s audit log | K8s audit log + CloudTrail |
| **PII masking** | None | None | Mask in logs | Mandatory masking (email, address) |
| **Retention** | N/A | N/A | 30 days | 1 year (legal requirement) |
| **Tamper-proof** | No | No | No | CloudWatch Logs cannot be deleted |

---

### Concern 29 — Feature Flags

**What it is:** Turning features on/off per environment (or per user/percentage) without
redeployment. Used for trunk-based development and A/B testing.

**Industry reality:**
- Amazon: internal tool "Weblab" — feature experiments on % of traffic
- Walmart: LaunchDarkly
- Netflix: dynamically changing behavior via Archaius/config
- Financial: strictly no A/B on financial calculations — only UI changes

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | None (code flags) | None (code flags) | Environment variables | Unleash (open-source) or config-based |
| **Example flag** | `RETAIL_ORDER_MESSAGING_ENABLED=false` | Same | `=true` | `=true` |
| **Rollout** | All or nothing | All or nothing | All or nothing | % rollout via Unleash |
| **Recommendation** | Start with env vars; add Unleash in prod when needed | — | — | — |

---

### Concern 30 — API Documentation

**What it is:** Auto-generated interactive API docs (Swagger UI) from OpenAPI annotations.

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | springdoc-openapi Swagger UI | Swagger UI | Swagger UI | **DISABLED** |
| **URL** | /swagger-ui.html | Same | /api-docs (internal only) | Hidden (security risk to expose in prod) |
| **OpenAPI spec** | /api-docs | /api-docs | /api-docs | N/A |
| **Disable in prod** | N/A | N/A | N/A | `springdoc.api-docs.enabled=false` |

---

### Concern 31 — Contract Testing

**What it is:** Verifying that the API consumer (e.g., web-storefront calling catalog-service)
and the API provider agree on the contract — request/response shape, status codes, headers.
Catches breaking API changes before they reach stage.

**Tool: Pact** (consumer-driven contract testing)

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Consumer tests** | Run locally | Run in CI | CI gate (must pass) | CI gate (must pass) |
| **Provider verification** | Manual | Run in CI | CI gate | CI gate |
| **Pact Broker** | N/A | N/A | Pactflow (SaaS) or self-hosted | Same |
| **Break the build on** | N/A | N/A | Contract mismatch | Contract mismatch |

---

### Concern 32 — Chaos Engineering

**What it is:** Deliberately injecting failures into a system to verify that it
recovers gracefully (circuit breakers trip, retries kick in, fallbacks serve stale data).

**Industry reality:**
- Netflix: Chaos Monkey (randomly kills pods in prod), Chaos Kong (kills entire AZ), SimianArmy
- Amazon: Game Days — deliberately failing systems to test runbooks
- Financial: Limited chaos (regulatory concerns); mostly failure simulation in stage

| | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Tool** | None | None | None | AWS Fault Injection Simulator (FIS) |
| **Experiments** | N/A | N/A | N/A | Kill 1 pod, inject latency, kill AZ |
| **Frequency** | N/A | N/A | N/A | Quarterly Game Day |
| **Runbook** | N/A | N/A | N/A | Required before running FIS |

---

## 6. Testing Strategy per Environment

### 6.1 Test Pyramid

```
                    ╔══════════════╗
                    ║   E2E Tests  ║   ← few, slow, expensive — run on stage only
                    ╠══════════════╣
                  ╔══════════════════╗
                  ║ Integration Tests║  ← medium — DB, Redis, Keycloak
                  ╠══════════════════╣
              ╔══════════════════════════╗
              ║      Unit Tests          ║  ← many, fast, no Spring context
              ╚══════════════════════════╝
```

### 6.2 What to Test Where

| Test Type | LOCAL | DEV (CI) | STAGE | PROD |
|---|:---:|:---:|:---:|:---:|
| **Unit tests** | ✅ mvn test | ✅ CI gate | — | — |
| **Spring Boot slice tests** | ✅ @WebMvcTest, @DataJpaTest | ✅ CI gate | — | — |
| **Integration tests (Testcontainers)** | ✅ local run | ✅ CI gate | — | — |
| **Contract tests (Pact)** | Optional | ✅ CI gate | ✅ CI gate | — |
| **API smoke tests** | Manual (curl) | Post-deploy curl | ✅ Automated | ✅ Automated (synthetic) |
| **Load tests (Gatling/k6)** | Never | Never | ✅ weekly run | ❌ never on prod |
| **Security scan (OWASP ZAP)** | Never | Optional | ✅ weekly | ❌ |
| **Chaos tests** | Never | Never | Never | Quarterly |
| **Manual exploratory** | ✅ | ✅ | ✅ | ❌ (changes only via CI/CD) |

### 6.3 Integration Test Strategy (Testcontainers)

```java
@SpringBootTest
@Testcontainers
class OrderServiceIntegrationTest {

    @Container
    static MySQLContainer<?> mysql = new MySQLContainer<>("mysql:8.0")
        .withDatabaseName("ordersdb")
        .withUsername("orders_user")
        .withPassword("orders_pass");

    @Container
    static KeycloakContainer keycloak = new KeycloakContainer("quay.io/keycloak/keycloak:25.0.6")
        .withRealmImportFile("retailstore-realm.json");

    // Tests run against real MySQL 8 and real Keycloak
    // No mocks — this catches the real integration bugs
    // MySQL 8 matches stage (RDS MySQL) and dev (k3s Bitnami MySQL)
}
```

### 6.4 Stage Smoke Test Script (post-deploy)

```bash
#!/bin/bash
BASE=https://stage.retailstore.com

# 1. Health check
curl -sf $BASE/api/v1/catalog/actuator/health | jq .status

# 2. Get token
TOKEN=$(curl -s -X POST $BASE/realms/retailstore/... | jq -r .access_token)

# 3. Catalog
curl -sf -H "Authorization: Bearer $TOKEN" $BASE/api/v1/catalog/products | jq '.content | length'

# 4. Cart
curl -sf -H "Authorization: Bearer $TOKEN" $BASE/api/v1/carts/smoke-test-user

# 5. Check circuit breakers
curl -sf $BASE/actuator/circuitbreakers | jq '.circuitBreakers[] | {name, state}'

echo "Smoke test: PASS"
```

---

## 7. CI/CD Pipeline Design

### 7.1 GitHub Actions Workflow (per service)

```
.github/workflows/catalog-service.yml

Trigger: push to main/develop, PR to main

Jobs:
  ┌──────────┐    ┌──────────┐    ┌──────────────┐    ┌──────────────┐
  │  build   │───►│   test   │───►│ docker-build │───►│ deploy-stage │
  │          │    │          │    │  push-to-ECR │    │  (auto)      │
  └──────────┘    └──────────┘    └──────────────┘    └──────┬───────┘
  mvn package    mvn test          docker build                │
  -DskipTests    +Testcontainers   tag: commit-sha             │ smoke tests
                 contract tests    push to ECR                 │
                                                    ┌──────────▼───────┐
                                                    │  deploy-prod      │
                                                    │  (manual gate)    │
                                                    └───────────────────┘
                                                     requires: team lead
                                                     approval in GitHub
```

### 7.2 Environment Branch Strategy

```
feature/xxx  →  develop  →  main
                  │              │
                  ▼              ▼
                DEV          STAGE → (approval) → PROD
              auto-deploy   auto-deploy
```

### 7.3 Image Tagging Strategy

```
ECR image tags:
  latest           ← tracks main branch (not recommended for prod)
  commit-abc1234   ← immutable, used in Helm values.yaml
  stage-2024-01-15 ← human-readable stage release tag
  v1.2.3           ← semantic version for prod releases
```

---

## 8. Infrastructure Design

### 8.1 Terraform Module Structure

```
retailstore-platform/terraform/
├── modules/                      ← reusable components
│   ├── vpc/                      ← VPC, subnets, IGW, NAT, route tables
│   ├── eks/                      ← EKS cluster, node groups, OIDC provider
│   ├── rds/                      ← MySQL 8.0, parameter groups, subnet groups
│   ├── elasticache/              ← Redis, subnet groups, parameter groups
│   ├── dynamodb/                 ← tables, TTL, PITR, backup
│   ├── sqs/                      ← queues, DLQ, access policies
│   ├── ecr/                      ← image repositories, lifecycle rules
│   ├── secrets/                  ← Secrets Manager secrets, rotation lambdas
│   └── monitoring/               ← CloudWatch dashboards, alarms, log groups
│
├── environments/
│   ├── stage/
│   │   ├── main.tf               ← calls modules with stage sizing
│   │   ├── variables.tf
│   │   ├── outputs.tf
│   │   └── terraform.tfvars      ← stage-specific values (committed, no secrets)
│   └── prod/
│       ├── main.tf               ← calls modules with prod sizing
│       ├── variables.tf
│       ├── outputs.tf
│       └── terraform.tfvars
│
└── state/
    └── backend.tf                ← S3 + DynamoDB for remote state
```

### 8.2 Stage vs Prod Infrastructure Sizing

| Resource | STAGE | PROD |
|---|---|---|
| **EKS nodes** | 2x t3.large (2 vCPU, 8 GB) | 3–9x m5.large (2 vCPU, 8 GB) |
| **EKS node AZs** | 1 AZ | 3 AZs |
| **RDS engine** | MySQL 8.0 | MySQL 8.0 (Aurora) |
| **RDS instance** | db.t3.medium (1 vCPU, 4 GB) | db.r5.large (2 vCPU, 16 GB) |
| **RDS Multi-AZ** | No | Yes |
| **RDS read replica** | No | 1 |
| **ElastiCache** | cache.t3.micro (0.5 GB) | cache.r6g.large (13 GB) |
| **ElastiCache nodes** | 1 (no HA) | 2 (Multi-AZ) |
| **DynamoDB** | On-demand | On-demand |
| **DynamoDB PITR** | No | Yes (35 days) |
| **SQS** | Standard | Standard + DLQ |
| **Keycloak pods** | 1 | 2 |
| **Gateway pods** | 1 | 2 |
| **Service pods** | 1 each | 2 each (HPA 2–5) |
| **WAF** | Basic managed rules | Full OWASP + custom |
| **CloudFront** | No | Yes |

### 8.3 Helm Values Per Environment

```
retailstore-platform/helm/
├── dev/                          ← dev Helm value overrides (one file per service)
│   ├── gateway.yaml              ← appEnv: SPRING_PROFILES_ACTIVE=dev, Keycloak host, routes
│   ├── catalog.yaml              ← MySQL creds, Redis host, Keycloak host
│   ├── carts.yaml                ← DynamoDB endpoint, AWS region
│   ├── checkout.yaml             ← Redis, Keycloak, order URL
│   ├── orders.yaml               ← MySQL, SQS queue URL, LocalStack endpoint
│   └── experience.yaml           ← downstream service URLs, Keycloak client secret
│
├── stage/                        ← stage overrides (2 replicas, autoscaling 2–4)
│   └── (same 6 files, AWS endpoints via # Replace: placeholders)
│
├── prod/                         ← prod overrides (3 replicas, autoscaling 3–8)
│   └── (same 6 files, AWS endpoints via # Replace: placeholders)
│
└── infra/                        ← Bitnami chart value files for dev infra
    ├── mysql-values.yaml         ← Bitnami MySQL 11.1.14 (MySQL 8.0); init SQL for 3 DBs
    ├── redis-values.yaml         ← Bitnami Redis 20.1.7; standalone, no auth
    └── keycloak-values.yaml      ← Bitnami Keycloak 22.2.1; external MySQL; realm import

Each service's Helm chart lives IN the service directory:
  <service-dir>/chart/
  ├── Chart.yaml
  ├── values.yaml                 ← base defaults (image, port, probes, resource limits)
  └── templates/
      ├── deployment.yaml         ← uses ConfigMap ref + optional Secret ref
      ├── service.yaml            ← ClusterIP; fullnameOverride sets DNS name
      ├── configmap.yaml          ← loads appEnv from values.yaml
      ├── hpa.yaml                ← enabled when autoscaling.enabled=true (prod)
      ├── pdb.yaml                ← enabled when pdb.enabled=true (prod)
      └── serviceaccount.yaml
```

---

## 9. Spring Boot Profile Strategy

### 9.1 Profile Naming Convention

| Spring Profile | Activated By | Applies To |
|---|---|---|
| `default` | No `SPRING_PROFILES_ACTIVE` set | Developer's local machine, IDE (H2 in-memory) |
| `dev` | `SPRING_PROFILES_ACTIVE=dev` | k3s on EC2 (MySQL, Redis, Zipkin) |
| `stage` | `SPRING_PROFILES_ACTIVE=stage` | AWS EKS stage (RDS MySQL, ElastiCache) |
| `prod` | `SPRING_PROFILES_ACTIVE=prod` | AWS EKS prod (tighter limits, Swagger disabled) |

### 9.2 File Layout per Service

```
src/main/resources/
├── application.yml              ← base config (H2 defaults, localhost env var defaults)
├── application-dev.yml          ← dev overrides (MySQL k3s, Redis k3s, Zipkin, 100% tracing)
├── application-stage.yml        ← stage overrides (RDS MySQL, ElastiCache, JSON logs, 10% tracing)
└── application-prod.yml         ← prod overrides (tighter limits, Swagger disabled, 5% tracing)
```

### 9.3 What Goes in Each Layer

**`application.yml` (base — already done):**
- Server port, graceful shutdown
- Actuator endpoints
- Spring Security JWKS URI with localhost default
- Service-level config with env var placeholders
- H2 datasource (local default)
- `spring.cache.type: simple` (local default)

**`application-dev.yml` (per service — created for all 6 services):**
```yaml
spring:
  datasource:
    url: jdbc:mysql://${MYSQL_HOST:localhost}:${MYSQL_PORT:3306}/catalogdb?useSSL=false&...
    driver-class-name: com.mysql.cj.jdbc.Driver
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQL8Dialect
  flyway:
    enabled: true
  cache:
    type: redis
  data:
    redis:
      host: ${REDIS_HOST:localhost}    ← localhost = port-forward to k3s redis
      port: ${REDIS_PORT:6379}
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}/realms/retailstore/...
management:
  tracing:
    sampling:
      probability: 1.0           ← 100% in dev
  zipkin:
    tracing:
      endpoint: http://${ZIPKIN_HOST:localhost}:9411/api/v2/spans
logging:
  level:
    com.retailstore: DEBUG
```

**Pattern:** All dev configs use `${VAR:localhost}` defaults. IntelliJ connects via
`kubectl port-forward` (localhost resolves to k3s infra). k3s pods get real DNS names
injected as env vars via Helm dev values (`MYSQL_HOST=mysql`, `REDIS_HOST=redis-master`, etc.).

**`application-stage.yml` (add per service):**
```yaml
spring:
  jpa:
    hibernate:
      ddl-auto: validate
    properties:
      javax.persistence.query.timeout: 5000
  flyway:
    enabled: true
  cache:
    type: redis

logging:
  level:
    com.retailstore: INFO
  # JSON format via logback-spring.xml profile block

management:
  tracing:
    sampling:
      probability: 0.1   # 10% sampling
```

**`application-prod.yml` (add per service):**
```yaml
spring:
  jpa:
    properties:
      javax.persistence.query.timeout: 3000
  flyway:
    enabled: true

springdoc:
  api-docs:
    enabled: false
  swagger-ui:
    enabled: false

management:
  tracing:
    sampling:
      probability: 0.05  # 5% sampling
  endpoint:
    health:
      show-details: never   # don't expose internals in prod

resilience4j:
  circuitbreaker:
    instances:
      catalog-cb:
        failure-rate-threshold: 40     # tighter in prod
        wait-duration-in-open-state: 20s
```

---

## 10. Cost Estimates

### Monthly AWS Cost (approximate, us-east-1)

| Resource | DEV | STAGE | PROD |
|---|---|---|---|
| EC2 t3.xlarge (k3s, 8hr/day 20days/mo) | ~$27 | — | — |
| EC2 EBS 30GB gp3 | ~$2 | — | — |
| ECR storage (all images) | ~$1 | ~$2 | ~$5 |
| EKS cluster control plane | — | $73 | $73 |
| EC2 nodes (t3.large x2 / m5.large x3) | — | $120 | $270 |
| RDS MySQL (t3.medium / r5.large) | — | $55 | $200 |
| ElastiCache (t3.micro / r6g.large) | — | $12 | $185 |
| DynamoDB (on-demand, low traffic) | — | $5 | $25 |
| SQS | — | $1 | $5 |
| ALB | — | $20 | $20 |
| CloudFront | — | $0 | $15 |
| WAF | — | $15 | $30 |
| Secrets Manager (8 secrets) | — | $3 | $3 |
| CloudWatch logs + metrics | — | $10 | $30 |
| X-Ray / tracing | — | $2 | $8 |
| NAT Gateway | — | $32 | $32 |
| **Total** | **~$30/month** | **~$350/month** | **~$901/month** |

**Cost control tips:**
- Stage: run during business hours only (cron to scale to 0 at night) → saves ~50%
- Prod: Reserved instances for RDS and ElastiCache → saves 30–40%
- Use `karpenter` instead of Cluster Autoscaler → more aggressive scale-down

---

## 11. Implementation Order

### Phase 1: Auth + Local Profile ✅ DONE
1. Replace `identity-service` with Keycloak 25
2. Add `GlobalJwtFilter` to api-gateway (JWKS validation)
3. Add `spring-security-oauth2-resource-server` to all 5 services
4. Add `ServiceTokenProvider` (Client Credentials) to experience-service and checkout-service
5. All services run locally with `SPRING_PROFILES_ACTIVE` unset (H2 + localhost Keycloak)

### Phase 2: Spring Profiles + k3s Dev Environment ✅ DONE
1. Create `application-dev.yml` for all 6 services (MySQL, Redis, Zipkin)
2. Create `application-stage.yml` for all 6 services (RDS MySQL, ElastiCache, no defaults)
3. Create `application-prod.yml` for all 6 services (tighter limits, Swagger disabled)
4. Add `logback-spring.xml` with profile-based JSON vs console output
5. Add Flyway MySQL migrations for catalog and orders
6. Create Helm charts in `<service-dir>/chart/` for all 6 services
7. Create Helm dev values (`helm/dev/*.yaml`) and infra values (`helm/infra/*.yaml`)
8. Create Helm stage and prod values (`helm/stage/*.yaml`, `helm/prod/*.yaml`)
9. Create k8s manifests for dev infra (`k8s/dev/`: namespace, DynamoDB Local, LocalStack, Zipkin)
10. Create automation scripts: `start-dev.sh`, `stop-dev.sh`, `install-infra.sh`,
    `deploy-services.sh`, `build-push.sh`, `port-forward.sh`
11. Fix all 6 Dockerfiles (Maven Docker image, no mvnw)
12. Update `SqsConfig.java` to support LocalStack via `SQS_ENDPOINT` env var

### Phase 3: Stage Infrastructure (3–5 days) — TODO
1. Write Terraform for network module (VPC, subnets, NAT)
2. Write Terraform for EKS module
3. Write Terraform for RDS MySQL, ElastiCache, DynamoDB, SQS
4. Write GitHub Actions CI pipeline (build → ECR → deploy to stage EKS)
5. Fill in `# Replace:` placeholders in `helm/stage/*.yaml` with real AWS endpoints
6. Deploy to stage; run smoke tests

### Phase 4: Prod Infrastructure (3–5 days) — TODO
1. Write Terraform prod module (based on stage, HA sizing)
2. Install ArgoCD on prod cluster
3. Configure WAF rules
4. Configure CloudFront
5. Set up PDB, HPA, topology spread constraints
6. Configure Secrets Manager + External Secrets Operator
7. Deploy to prod; run smoke tests

### Phase 5: Observability (2–3 days) — TODO
1. Configure CloudWatch Log Groups per service
2. Build CloudWatch Dashboard (RED metrics: rate, errors, duration)
3. Add CloudWatch Alarms (error rate > 5%, circuit breaker OPEN, DLQ depth > 0)
4. Wire Micrometer → X-Ray for stage/prod (replace Zipkin exporter)
5. Set up Grafana dashboard

### Phase 6: Advanced (optional, as needed) — TODO
- Unit tests + Testcontainers integration tests per service
- Contract tests with Pact
- Canary deployments with Argo Rollouts
- Feature flags with Unleash
- Chaos experiments with AWS FIS
- Istio service mesh (mTLS Tier 2)

---

## Quick Reference: "Do I need X in environment Y?"

| "Should I enable X in LOCAL?" | Answer |
|---|---|
| Rate limiting | No — no Redis without extra Docker; wastes dev time |
| Circuit breaker | Yes — catch config bugs early |
| TLS | No — localhost is trusted |
| Flyway | Optional — H2 uses ddl-auto:update |
| Distributed tracing | No — adds noise; use debug logs instead |
| Metrics/Prometheus | Yes — good to see actuator metrics |
| Swagger UI | Yes — essential for testing |
| WAF | No — not applicable |
| Autoscaling | No — not applicable |

| "Should I enable X in STAGE?" | Answer |
|---|---|
| Rate limiting | Yes — test it actually works |
| Circuit breaker | Yes — tune thresholds here, not in prod |
| TLS | Yes — your stage should mirror prod |
| Flyway | Yes — test migrations before prod |
| Distributed tracing | Yes — find cross-service bugs here |
| Swagger UI | Yes — but on internal URLs only |
| WAF | Yes — with basic rules |
| HPA | No — wastes money; fixed 1 replica is fine |
| Load tests | Yes — weekly, with k6 or Gatling |

| "Should I enable X in PROD?" | Answer |
|---|---|
| Rate limiting | Yes — protect the business |
| Circuit breaker | Yes — with tight thresholds |
| TLS | Yes — mandatory |
| Flyway | Yes — validate mode only |
| Swagger UI | No — disable, security risk |
| WAF | Yes — full ruleset |
| HPA | Yes — you need to scale under traffic |
| Chaos engineering | Yes — quarterly, with runbooks |
| Feature flags | Yes — for safe rollouts |
