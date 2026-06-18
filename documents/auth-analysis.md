# Authentication & Authorization — Architecture Analysis

> **Purpose:** This document analyses the current identity-service, evaluates industry-standard
> alternatives, and answers the key architectural questions before any code changes.
> Read this before `auth-design.md`.

---

## 1. What Is Wrong With the Current identity-service

The current `identity-service` is a custom-built auth microservice. It does register/login,
issues JWTs with jjwt, BCrypt hashes passwords, and exposes refresh/profile endpoints. This
is a classic anti-pattern in modern microservice architectures. Here is why:

| Concern | Current State | Why It Is a Problem |
|---|---|---|
| **Protocol compliance** | Custom JWT issuance | Not OAuth2/OIDC compliant — clients cannot use standard libraries |
| **Token introspection** | None | No way for services to validate token metadata without calling identity-service |
| **JWKS endpoint** | None | api-gateway cannot auto-fetch public keys; secret must be shared manually |
| **Refresh token rotation** | Basic, no rotation | Susceptible to refresh token replay attacks |
| **MFA / Social login** | Not implemented | Requires significant custom code to add |
| **User federation** | Not implemented | Cannot link to LDAP, AD, or external IdPs |
| **Scopes and audiences** | Not in tokens | Cannot express fine-grained permissions or service-specific claims |
| **Logout / token revocation** | None | A compromised JWT is valid until expiry |
| **Admin UI** | None | User management requires direct DB access |
| **Standards drift** | High | Every new requirement (passwordless, device auth) needs custom code |

**Bottom line:** Any identity concern beyond "login and get a token" requires building OAuth2/OIDC
from scratch. No bank, retailer, or tech company does this — they use a standards-compliant
Identity Provider (IdP).

---

## 2. IAM Tool Comparison

### Candidates

| Tool | License | Hosting | OIDC/OAuth2 | Spring Integration | Industry Usage |
|---|---|---|---|---|---|
| **Keycloak** | Apache 2.0 (free) | Self-hosted | Full | First-class (spring-security-oauth2) | Red Hat, IBM, Deutsche Bank, Walmart-style enterprises |
| Auth0 | Commercial (free tier: 7500 MAU) | SaaS | Full | Good | Startups, SaaS products |
| Okta | Commercial | SaaS | Full | Good | Enterprise, financial |
| AWS Cognito | Pay-per-use | AWS-managed | Partial (quirks) | Moderate | AWS-native workloads |
| Spring Authorization Server | Apache 2.0 | Self-hosted | Full | Native | When you need a custom IdP as a Spring Boot service |
| Ory Hydra | Apache 2.0 | Self-hosted | Full | Good | Cloud-native teams, Kubernetes-first |

### Recommendation: **Keycloak**

Reasons:
1. **Free forever** — no user count limits, no SaaS billing surprises
2. **Most widely used** in the enterprise Java/Spring ecosystem — the de-facto standard when
   you self-host
3. **Docker-ready** — runs as a single container locally; Helm chart available for EKS
4. **Spring Boot first-class support** — `spring-boot-starter-oauth2-resource-server` works
   out of the box with Keycloak's JWKS endpoint
5. **Admin UI** — realm, client, user, role, and scope management without touching code
6. **All flows** — Authorization Code + PKCE, Client Credentials, Device, SAML, social login
7. **JWKS auto-rotation** — api-gateway fetches public keys automatically; no shared secret
8. **Token introspection + revocation** — immediate session invalidation
9. **Used by** — Deutsche Bank, Vodafone, Bosch, government systems, and most enterprises
   that run Java on Kubernetes

Auth0/Okta are excellent for pure SaaS products. Keycloak is the choice when you own the
infrastructure (which EKS is).

---

## 3. Do We Need Both api-gateway AND experience-service?

**Yes. They serve completely different concerns and must not be merged.**

### api-gateway — "North-South Traffic Controller"

Handles cross-cutting infrastructure concerns that apply to **every** request entering the cluster:

| Responsibility | Why It Belongs Here |
|---|---|
| TLS termination (via ALB) | Infrastructure, not business |
| JWT validation (token signature, expiry, audience) | Must happen before any service sees the request |
| Rate limiting | Protect all services from abuse at one chokepoint |
| Circuit breaking | Prevent cascade failures across all routes |
| CORS | Browser security policy, applies globally |
| Request correlation IDs | Observability, applied to every request |
| Routing | Traffic dispatch, infrastructure concern |

**Key rule:** api-gateway knows NOTHING about the business domain. It only knows about
infrastructure and security tokens.

### experience-service (BFF) — "Business Aggregation Layer"

Handles business-level aggregation concerns that are specific to the frontend channel:

| Responsibility | Why It Belongs Here |
|---|---|
| Parallel service calls (`Mono.zip`) | Reduces client round-trips |
| Response shaping per channel | Mobile gets lean payload, Web gets full |
| Business-level fallbacks | If catalog fails, return partial data |
| Page-level data contracts | Owns the "homepage" shape — not any single domain service |
| GraphQL gateway (future) | Aggregators become resolvers |

**Key rule:** experience-service knows about the business domain but has no auth logic.
It receives pre-validated user identity from the gateway via forwarded headers.

### Where Does Auth Live?

```
                    ┌─────────────────────────────────────────────┐
                    │              AUTH BOUNDARIES                  │
                    │                                               │
Browser ──────────► │  api-gateway                                  │
  (Bearer token)    │  ┌─────────────────────────────────────────┐ │
                    │  │ GlobalJwtFilter                          │ │
                    │  │  1. Verify token signature (JWKS)        │ │
                    │  │  2. Verify expiry, issuer, audience      │ │
                    │  │  3. Extract claims                       │ │
                    │  │  4. Inject X-User-Id, X-User-Email,      │ │
                    │  │     X-User-Role, X-User-Scope headers    │ │
                    │  │  5. Reject (401) if invalid              │ │
                    │  └─────────────────────────────────────────┘ │
                    │         │                                     │
                    │         ▼                                     │
                    │  Route to downstream (with enriched headers)  │
                    └─────────────────────────────────────────────┘
                              │
                ┌─────────────┴──────────────┐
                │                            │
                ▼                            ▼
       experience-service            catalog-service
       (BFF — trusts gateway         (trusts gateway
        headers, no re-              headers, extracts
        validation needed)           user context for
                │                    logging/audit)
                │ service-to-service
                ▼ (Client Credentials JWT)
       catalog / cart / orders
```

**Authentication happens ONCE at the gateway.** Downstream services trust the forwarded headers.

---

## 4. Microservice-to-Microservice (M2S) Authentication

### Do We Need It?

**Yes, if you follow zero-trust principles. Here is the reasoning:**

- In a traditional perimeter model: once inside the cluster, services trust each other implicitly.
- In a zero-trust model: every service-to-service call is authenticated, regardless of network location.

The question is: **what level of trust model is right for this platform?**

### M2S Authentication Options

| Option | How It Works | Pros | Cons |
|---|---|---|---|
| **No M2S auth** (network trust) | Services inside the cluster trust any caller; Kubernetes NetworkPolicy restricts which pods can talk | Simple, zero latency overhead | Compromised pod can impersonate any service |
| **OAuth2 Client Credentials** | Each service has a `client_id`/`client_secret` in Keycloak; gets a short-lived token before calling another service | Explicit, auditable, industry standard | Small latency for token fetch (cached); configuration overhead |
| **mTLS (mutual TLS)** | Each service has a certificate; TLS handshake proves identity both ways | Cryptographically strongest | Requires service mesh (Istio/Linkerd) or cert management |
| **mTLS via Istio service mesh** | Istio sidecar proxies handle mTLS transparently; apps see plain HTTP | Transparent to application code; Google/Netflix model | Istio complexity; EKS add-on required |
| **HMAC request signing** | Shared secret; caller signs the request body | No token server needed | Shared secret management; not standards-based |

### What Do Industry Leaders Use?

| Company | North-South (Browser → Cluster) | East-West (Service → Service) |
|---|---|---|
| **Netflix** | Zuul / Spring Cloud Gateway + OAuth2 JWT validation | Service mesh (Envoy/mTLS) for internal; service tokens for external-facing APIs |
| **Amazon (AWS internally)** | ALB + Cognito / custom JWT | IAM roles (IRSA on EKS) + VPC security groups; no app-level M2S auth within a bounded service domain |
| **Walmart** | API Gateway + OAuth2/OIDC (Keycloak-style IdP) | OAuth2 Client Credentials for cross-domain service calls; network policy for intra-domain |
| **Zalando** | Skipper gateway + tokens (their open-source token validation library) | OAuth2 Client Credentials via their internal Planck token service |
| **Google** | BeyondCorp — zero trust at every layer | mTLS everywhere (gRPC + mTLS); no implicit network trust |
| **Airbnb** | API gateway + JWT | Service mesh (Envoy) for mTLS, supplemented by service-level OAuth2 tokens |

### Recommendation for RetailStore

**Two-tier approach** aligned with the platform's current EKS maturity:

**Tier 1 (implement now): OAuth2 Client Credentials for cross-service calls**
- `experience-service → catalog/cart/orders`: Client Credentials tokens
- `checkout-service → order-service`: Client Credentials token
- Each service is a Keycloak client with its own `client_id`/`client_secret`
- Tokens are cached (Keycloak token TTL 5 min, cached until expiry)
- Standard, auditable, works without a service mesh

**Tier 2 (production hardening): mTLS via Istio**
- Add Istio to EKS as the next infrastructure layer
- Istio handles mTLS transparently; application code remains unchanged
- Client Credentials tokens remain as an additional application-level layer (defence in depth)
- This is the Netflix/Zalando/Airbnb pattern

For this implementation we do **Tier 1 only** (Client Credentials). Tier 2 is an infrastructure
concern that does not change application code.

---

## 5. Where Does Authorization Live?

**Coarse-grained authorization (route-level):** api-gateway
- Example: Only `ROLE_ADMIN` can reach `/api/v1/orders/*/status`
- Implemented as a filter that checks the `X-User-Role` header

**Fine-grained authorization (resource-level):** individual service
- Example: A customer can only read their own orders (`customerId == token.sub`)
- Implemented in the service's own controller/service layer using the forwarded `X-User-Id` header

This is the industry-standard split. The gateway is not the right place to know that "a customer
can only update their own cart item" — that is business logic.

---

## 6. What Gets Deleted / Replaced

| Current Component | Fate | Replacement |
|---|---|---|
| `identity-service` (the whole service) | **Deleted** | Keycloak container |
| `identity-service` route in api-gateway | **Replaced** | Keycloak's own endpoints (no gateway proxy needed for auth) |
| `identity-service` Helm chart (doesn't exist yet) | N/A | Keycloak Helm chart (bitnami/keycloak) |
| `GatewayApplication.java` (no JWT filter) | **Extended** | Add `GlobalJwtFilter` that validates against Keycloak JWKS |
| All services: no user context propagation | **Extended** | Services read `X-User-Id`/`X-User-Role` headers forwarded by gateway |
| `JwtTokenProvider` in identity-service | **Deleted** | Keycloak issues tokens; services only validate, never issue |
| `SecurityConfig` in identity-service | **Deleted** | Each service gets `spring-security-oauth2-resource-server` |
| `docker-compose.yml` identity service | **Replaced** | Keycloak container + realm import |

---

## 7. Impact Summary by Service

| Service | Change Required | Scope |
|---|---|---|
| **identity-service** | Delete entirely | All files |
| **api-gateway** | Add JWT filter (JWKS validation), add checkout route, remove identity route | Medium |
| **experience-service** | Add Client Credentials token fetching for downstream calls, read user headers | Medium |
| **catalog-service** | Add `spring-security-oauth2-resource-server`, read `X-User-Id` for audit | Small |
| **cart-service** | Same as catalog | Small |
| **checkout-service** | Same as catalog + Client Credentials for order-service call | Small |
| **order-service** | Add GlobalExceptionHandler + SqsConfig + resource server config | Small |
| **web-storefront** | Replace login/register API calls with Keycloak Authorization Code + PKCE flow | Medium |
| **retailstore-platform** | Add Keycloak to docker-compose, add Keycloak realm init, update helm dev overrides | Medium |

---

## 8. Decision Record

| Decision | Choice | Rationale |
|---|---|---|
| IdP tool | **Keycloak 25** | Free, self-hosted, Spring first-class, most widely used in enterprise Java |
| Browser flow | **Authorization Code + PKCE** | Most secure for SPAs; replaces implicit flow (deprecated) |
| M2S flow | **OAuth2 Client Credentials** | Industry standard for service accounts; auditable; no mesh required |
| Token format | **JWT (RS256)** | Asymmetric signing; services validate with public key (JWKS); no shared secret |
| Auth entry point | **api-gateway only** | Single chokepoint; downstream services trust forwarded headers |
| Fine-grained authz | **Individual services** | Business logic must live with the domain |
| Logout | **Keycloak session invalidation + token revocation** | Immediate revocation vs waiting for expiry |
| User storage | **Keycloak internal user store** | For now; can federate to LDAP/AD later without code changes |
| Issuer validation | **Disabled (jwk-set-uri only, no issuer-uri)** | Avoids issuer mismatch between k3s internal DNS and localhost |

---

## 9. Environment / Profile-Wise Authentication Analysis

This section covers how authentication configuration changes across Spring Boot profiles.
The same code runs in all environments; only external configuration differs.

---

### 9.1 Profile Overview

| Aspect | LOCAL (default) | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Keycloak location** | Local Docker container | k3s Bitnami pod on EC2 | EKS Bitnami pod | EKS Bitnami HA (2 pods) |
| **Keycloak DB** | H2 embedded | MySQL `keycloakdb` (k3s) | RDS MySQL (shared) | RDS MySQL (dedicated) |
| **JWKS URI config** | Composed from HOST/PORT vars | Composed from HOST/PORT vars | Single `KEYCLOAK_JWKS_URI` var | Single `KEYCLOAK_JWKS_URI` var |
| **Token endpoint config** | Composed from HOST/PORT vars | Composed from HOST/PORT vars | Single `KEYCLOAK_TOKEN_URI` var | Single `KEYCLOAK_TOKEN_URI` var |
| **Client secrets location** | application.yml defaults | k8s env vars (Helm dev values) | K8s Secret (optional: true) | AWS Secrets Manager |
| **Issuer validation** | Disabled | Disabled | Disabled | Disabled |
| **Swagger UI** | Enabled | Enabled | Enabled (internal only) | **Disabled** |
| **Health show-details** | `always` | `always` | `when-authorized` | `never` |
| **Tracing sampling** | N/A | 100% | 10% | 5% |

---

### 9.2 JWKS URI Pattern Per Profile

The gateway and all downstream services validate tokens by fetching Keycloak's public keys.
The URI is never the same in every environment — here is how each profile wires it up.

#### LOCAL (default profile — `application.yml`)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: >
            http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}
            /realms/retailstore/protocol/openid-connect/certs
```

`KEYCLOAK_HOST` and `KEYCLOAK_PORT` are not set → defaults to `localhost:8180`.
Developer runs Keycloak as a local Docker container on port 8180.

#### DEV profile (`application-dev.yml`)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: >
            http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}
            /realms/retailstore/protocol/openid-connect/certs
```

Same `${VAR:localhost}` pattern. Two scenarios:

| Actor | How it connects | KEYCLOAK_HOST resolves to |
|---|---|---|
| IntelliJ (IDE) | `kubectl port-forward svc/keycloak 8180:8180` | `localhost` (default) |
| k3s pod | Helm dev values inject `KEYCLOAK_HOST=keycloak` | `keycloak` (k3s ClusterIP DNS) |

**Key detail:** `helm/dev/gateway.yaml` and all other dev values files set:
```yaml
appEnv:
  KEYCLOAK_HOST: "keycloak"
  KEYCLOAK_PORT: "8180"
```

#### STAGE profile (`application-stage.yml`)

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS_URI}
```

`KEYCLOAK_JWKS_URI` has **no default** — it must be injected. Source: `helm/stage/*.yaml`:
```yaml
appEnv:
  KEYCLOAK_JWKS_URI: ""  # Replace: https://auth.stage.retailstore.com/realms/retailstore/protocol/openid-connect/certs
```

#### PROD profile (`application-prod.yml`)

Same as stage. `KEYCLOAK_JWKS_URI` injected from `helm/prod/*.yaml` or AWS Secrets Manager.

---

### 9.3 Issuer Validation — Why It Is Disabled

Spring Security can validate the `iss` claim in the JWT against the configured issuer URI.
This is intentionally disabled across all environments.

**The problem:**
```
Token issued inside k3s by Keycloak pod:
  iss = "http://keycloak:8180/realms/retailstore"    ← internal k3s DNS name

IntelliJ service (local dev) validates the token:
  expected iss = "http://localhost:8180/realms/retailstore"  ← port-forwarded address
  MISMATCH → 401 Unauthorized
```

**Our approach:** Use `jwk-set-uri` only (not `issuer-uri`). Spring's `NimbusReactiveJwtDecoder`
built with `withJwkSetUri(...)` validates signature, expiry, and audience — but NOT issuer.
This is a deliberate trade-off: dev ergonomics vs issuer validation strictness.

**In production**, this is acceptable because:
- The gateway is the only public entry point; tokens must come from Keycloak
- JWKS signature validation ensures tokens weren't forged
- ALB + VPC security groups prevent token injection from outside

**If you want to add issuer validation** (stage/prod only), configure a single issuer URI:
```yaml
# application-stage.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          issuer-uri: ${KEYCLOAK_ISSUER_URI}  # https://auth.stage.retailstore.com/realms/retailstore
```
This would require a public-facing Keycloak URL that is consistent from inside and outside EKS.

---

### 9.4 Client Secrets Management Per Environment

Two services use OAuth2 Client Credentials (M2S): `experience-service` and `checkout-service`.

#### Where secrets live per profile

| Profile | experience-service | checkout-service |
|---|---|---|
| **LOCAL** | `application.yml` default: `experience-service-secret` | `application.yml` default: `checkout-service-secret` |
| **DEV** | `helm/dev/experience.yaml` env var: `KEYCLOAK_CLIENT_SECRET` | `helm/dev/checkout.yaml` env var: `KEYCLOAK_CLIENT_SECRET` |
| **STAGE** | k8s Secret `experience-secrets` key `client-secret` | k8s Secret `checkout-secrets` key `client-secret` |
| **PROD** | AWS Secrets Manager → External Secrets Operator → k8s Secret | Same |

#### Helm chart Secret wiring (`optional: true`)

```yaml
# <service>/chart/templates/deployment.yaml (excerpt)
env:
  - name: KEYCLOAK_CLIENT_SECRET
    valueFrom:
      secretKeyRef:
        name: experience-secrets
        key: client-secret
        optional: true       ← dev works without creating k8s Secrets manually
```

`optional: true` means the pod starts even if the Secret doesn't exist. In dev, the value is
set directly via Helm values (as a plain env var). In stage/prod, the k8s Secret must be
created before deployment.

#### Token endpoint URI (for Client Credentials token fetch)

```yaml
# DEV — application-dev.yml (experience-service, checkout-service)
retail:
  experience:
    keycloak:
      token-uri: http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}/realms/retailstore/protocol/openid-connect/token

# STAGE / PROD — application-stage.yml, application-prod.yml
retail:
  experience:
    keycloak:
      token-uri: ${KEYCLOAK_TOKEN_URI}    # injected from helm/stage/experience.yaml
```

---

### 9.5 Security Settings That Tighten Per Environment

| Setting | LOCAL | DEV | STAGE | PROD |
|---|---|---|---|---|
| **Swagger UI** | `/swagger-ui.html` enabled | Enabled | Enabled (internal only) | `springdoc.api-docs.enabled: false` |
| **Health show-details** | `always` | `always` | `when-authorized` | `never` |
| **Actuator endpoints** | All exposed | All exposed | `health, info, metrics, prometheus` | Same (no env endpoint) |
| **HikariCP leak detection** | None | None | None | `leak-detection-threshold: 60000ms` |
| **Log level** | DEBUG | DEBUG | INFO | WARN |
| **Resilience4j failure rate** | 60% | 50% | 50% | 40% |
| **Resilience4j wait in OPEN** | 5s | 10s | 10s | 15–20s |

---

### 9.6 Keycloak Realm Across Environments

The same realm (`retailstore`) is used in all environments. It is managed as:
- **DEV**: JSON file at `retailstore-platform/keycloak/realms/retailstore-realm.json`,
  imported via a k8s ConfigMap mounted into the Keycloak pod at startup
- **STAGE/PROD**: Same realm JSON applied manually after first Keycloak deploy, or
  managed via Keycloak Admin API / Terraform Keycloak provider

#### Realm clients

| Client ID | Flow | Used By |
|---|---|---|
| `web-storefront` | Authorization Code + PKCE | React SPA (browser login) |
| `experience-service` | Client Credentials | experience-service → catalog/cart/orders M2S calls |
| `checkout-service` | Client Credentials | checkout-service → order-service M2S calls |

#### Token claims forwarded by api-gateway

After JWKS validation, `GlobalJwtFilter` extracts these claims and injects them as headers:
```
X-User-Id     ← JWT sub claim
X-User-Email  ← JWT email claim
X-User-Name   ← JWT preferred_username claim
X-User-Role   ← JWT realm_access.roles[] (first matching role)
```

Downstream services read these headers to identify the caller without re-validating the JWT.
This is the industry-standard pattern (Netflix, Zalando, Walmart) when using an API gateway
as the single auth entry point.
