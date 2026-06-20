# api-gateway — Authentication & Authorization Guide

> **Scope:** This document covers how the api-gateway validates JWTs, enriches requests with
> user identity, and how that wiring changes across environments. For the platform-wide auth
> design (Keycloak realm, OAuth2 flows, M2S tokens) see `documents/auth-design.md` at the
> workspace root.

---

## 1. Responsibility

The gateway is the **single auth entry point** for the entire platform. Every request from a
browser or external client passes through here first. By the time a request reaches any
downstream service (catalog, orders, etc.), authentication has already happened — downstream
services only need to read the forwarded `X-User-*` headers.

```
Browser
  │  Authorization: Bearer <JWT>
  ▼
api-gateway  ──► GlobalJwtFilter validates token, injects headers
  │              X-User-Id, X-User-Email, X-User-Name, X-User-Role
  ▼
catalog / orders / cart / checkout / experience
  └── reads X-User-Id header — no JWT re-validation needed
```

---

## 2. Components

### 2.1 GlobalJwtFilter

**File:** `src/main/java/com/retailstore/gateway/filter/GlobalJwtFilter.java`  
**Order:** `-3` (runs before `CorrelationIdFilter` at -2 and `RequestLoggingFilter` at -1)

What it does on every non-public request:

| Step | Action |
|------|--------|
| 1 | Check if path matches a public pattern (`/actuator/**`, `/fallback/**`) — if yes, skip auth |
| 2 | Read the `Authorization` header — if missing or not `Bearer ...`, return **401** |
| 3 | Call `ReactiveJwtDecoder.decode(token)` — verifies RS256 signature against Keycloak JWKS |
| 4 | If token is expired or signature invalid → **401** with JSON body |
| 5 | Extract claims: `sub` → `X-User-Id`, `email` → `X-User-Email`, `name` → `X-User-Name`, `realm_access.roles` → `X-User-Role` |
| 6 | Mutate the request with the enriched headers and pass to the route |

The filter is **non-blocking** (reactive `Mono<Void>` chain). It never touches a thread pool.

### 2.2 SecurityConfig

**File:** `src/main/java/com/retailstore/gateway/config/SecurityConfig.java`

Spring Security's built-in OAuth2 resource server is **disabled** here. Instead, the config:
- Permits all exchanges (auth is handled by `GlobalJwtFilter`, not Spring Security)
- Creates a `ReactiveJwtDecoder` bean from `jwk-set-uri` only — **no `issuer-uri`**

```java
@Bean
public ReactiveJwtDecoder reactiveJwtDecoder() {
    return NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build();
}
```

> **Why Spring Security's resource server is disabled:**  
> Spring Security's default 401 response is a redirect or HTML error page.
> `GlobalJwtFilter` returns a structured JSON `{"status":401,"error":"Unauthorized","path":"..."}`,
> which is what API clients expect.

> **Why `issuer-uri` is absent:**  
> Keycloak inside k3s issues tokens with `iss = http://keycloak:8180/realms/retailstore`
> (cluster-internal DNS). IntelliJ connecting via `kubectl port-forward` reaches the same
> Keycloak at `http://localhost:8180`. Issuer validation would fail because the two URLs
> don't match. Using `jwk-set-uri` only still validates the RS256 signature and expiry —
> the security guarantee is intact.

### 2.3 JWKS Caching

Keycloak exposes its RS256 public keys at the JWKS endpoint
(`/realms/retailstore/protocol/openid-connect/certs`). The gateway uses these keys to verify
every token's signature — it never holds the private key, only the public one.

**Lifecycle of the cached keys:**

```
Pod starts
    │
    │  No keys fetched yet — the cache is empty.
    │  NimbusReactiveJwtDecoder is lazy: it does NOT call Keycloak on startup.
    │
    ▼
First incoming request with a JWT
    │
    │  Cache miss (empty) → fetch JWKS from Keycloak
    │  Keycloak returns: [ { kid: "abc123", kty: "RSA", n: "...", e: "AQAB" } ]
    │  Keys stored in memory (JVM heap) inside the decoder
    │
    ▼
All subsequent requests
    │
    │  Token header contains kid: "abc123"
    │  kid found in cache → use cached public key → verify signature
    │  No network call to Keycloak — validation is pure in-process math
    │
    ▼
Pod running normally — keys stay cached indefinitely
    │
    │  Cache is in-memory only.
    │  It lives for the entire lifetime of the pod.
    │  If the pod restarts or crashes, the cache is gone.
    │  On the next startup the same lazy-fetch happens on the first request.
    │
    ▼  (only if Keycloak rotates its signing key)
Token arrives with kid: "xyz789" — not in cache
    │
    │  Cache miss on the new kid → re-fetch JWKS from Keycloak
    │  New key set loaded into cache (old key may still be in the set too,
    │  Keycloak keeps previous keys for a grace period)
    │  Token validated with the new key
    │
    ▼
Back to normal — cache now has both old and new keys
```

**Key points:**

| Question | Answer |
|----------|--------|
| When are keys first fetched? | Lazily — on the first token validation request, not on pod startup |
| How long do they stay? | For the lifetime of the pod / JVM process |
| What clears the cache? | Pod restart, crash, or OOM kill — nothing else |
| What triggers a re-fetch? | A token arrives whose `kid` is not in the current cache (Keycloak key rotation) |
| Is the cache shared across pods? | No — each pod has its own in-memory cache |
| What if Keycloak is down at startup? | Pod starts fine; the first token validation fails with a network error until Keycloak is reachable |
| What if Keycloak is down mid-operation? | Tokens whose `kid` is already cached continue to validate normally; only requests needing a re-fetch will fail |

**Implication for stage/prod (multiple gateway replicas):** Each pod independently fetches
and caches its own copy of the JWKS. During a Keycloak key rotation, different pods may
briefly hold different key sets — pods that have already re-fetched will accept new-key tokens
while others haven't yet. This self-resolves within one request cycle per pod, and Keycloak's
grace period (keeping old keys active for a while) ensures no tokens are wrongly rejected.

---

## 3. Request Flow (Step by Step)

```
1. Browser sends:
   GET /api/v1/catalog/products
   Authorization: Bearer eyJhbGci...

2. GlobalJwtFilter (order -3):
   ├── path /api/v1/catalog/products → not public → proceed
   ├── header "Bearer eyJhbGci..." → strip "Bearer ", get raw token
   ├── ReactiveJwtDecoder.decode(token)
   │     └── fetch JWKS from Keycloak (cached after first call)
   │     └── verify RS256 signature
   │     └── verify exp claim (not expired)
   │     └── return Jwt object
   └── mutate request:
         X-User-Id:    550e8400-e29b-41d4-a716-446655440000  (sub)
         X-User-Email: user@example.com                      (email)
         X-User-Name:  John Doe                              (name)
         X-User-Role:  CUSTOMER                              (realm_access.roles)

3. CorrelationIdFilter (order -2):
   └── attach X-Correlation-Id: abc123 (from request or generate new)

4. RequestLoggingFilter (order -1):
   └── log: GET /api/v1/catalog/products → will log response status + duration

5. Route matched: catalog-service
   └── forward to http://catalog:8080/api/v1/catalog/products
       with all enriched headers

6. catalog-service receives:
   X-User-Id:    550e8400-...
   X-User-Email: user@example.com
   X-User-Name:  John Doe
   X-User-Role:  CUSTOMER
   X-Correlation-Id: abc123
   ← no Authorization header forwarded downstream (gateway strips it by default)
```

### 401 Response Shape

```json
{
  "status": 401,
  "error": "Unauthorized",
  "message": "Missing or invalid Authorization header",
  "path": "/api/v1/catalog/products"
}
```

---

## 4. Public Paths (No Auth Required)

Defined in `GlobalJwtFilter.PUBLIC_PATHS`:

| Pattern | Purpose |
|---------|---------|
| `/actuator/**` | Spring Boot health, metrics, readiness probes — must be accessible without a token so k3s/EKS can probe them |
| `/fallback/**` | Circuit breaker fallback responses — called internally by the gateway itself |

To add a new public path (e.g. a webhook endpoint):
```java
// GlobalJwtFilter.java
private static final List<String> PUBLIC_PATHS = List.of(
    "/actuator/**",
    "/fallback/**",
    "/webhooks/**"   // ← add here
);
```

---

## 5. Role Extraction Logic

Keycloak puts roles in `realm_access.roles[]`. The filter picks the first role that matches
the platform's known roles, in priority order:

```
ADMIN > CUSTOMER > SUPPORT > service-role
```

If none of the known roles match, the first role in the array is used as a fallback.
Service-to-service tokens (Client Credentials) will have `service-role` in `X-User-Role`.

---

## 6. Environment Configuration

### 6.1 LOCAL (default profile — no `SPRING_PROFILES_ACTIVE`)

Keycloak runs as a standalone Docker container on your laptop.

**How JWKS URI is resolved:**
```yaml
# application.yml
spring.security.oauth2.resourceserver.jwt.jwk-set-uri:
  ${KEYCLOAK_JWKS_URI:http://localhost:8180/realms/retailstore/protocol/openid-connect/certs}
```
`KEYCLOAK_JWKS_URI` is not set → defaults to `http://localhost:8180/...`

**Start Keycloak:**
```bash
docker run -d --name keycloak \
  -p 8180:8180 \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_HTTP_PORT=8180 -e KC_HOSTNAME_STRICT=false \
  -v $(pwd)/retailstore-platform/keycloak/realms:/opt/keycloak/data/import \
  quay.io/keycloak/keycloak:25.0.6 start-dev --import-realm
```

**Run the gateway in IntelliJ:**
- VM args: *(none — default profile)*
- The gateway connects to `localhost:8180` for JWKS

---

### 6.2 DEV (dev profile — k3s on EC2)

Keycloak runs as a Bitnami Helm chart inside k3s. Two connection scenarios exist simultaneously:

| Actor | Path to Keycloak | KEYCLOAK_HOST resolves to |
|-------|-----------------|--------------------------|
| IntelliJ (running gateway locally) | `kubectl port-forward svc/keycloak 8180:8180` | `localhost` (env var not set → default) |
| k3s gateway pod | k3s ClusterIP DNS | `keycloak` (injected by `helm/dev/gateway.yaml`) |

**JWKS URI in `application-dev.yml`:**
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}/realms/retailstore/protocol/openid-connect/certs
```

**Helm dev values (`retailstore-platform/helm/dev/gateway.yaml`):**
```yaml
appEnv:
  SPRING_PROFILES_ACTIVE: "dev"
  KEYCLOAK_HOST: "keycloak"       # k3s ClusterIP service name
  KEYCLOAK_PORT: "8180"
  REDIS_HOST: "redis-master"
  ALLOWED_ORIGIN: "http://localhost:3000"
  ZIPKIN_HOST: "zipkin"
```

**Steps to run the gateway locally against k3s:**
```bash
# 1. Start dev environment
./retailstore-platform/scripts/start-dev.sh

# 2. Port-forward infra (includes Keycloak on 8180)
export KUBECONFIG=$HOME/.kube/config-dev-k3s
./retailstore-platform/scripts/port-forward.sh start

# 3. Run gateway in IntelliJ
#    VM args: -DSPRING_PROFILES_ACTIVE=dev
#    Env vars: RETAIL_GATEWAY_ROUTES_CATALOG=http://localhost:8081
#              RETAIL_GATEWAY_ROUTES_CARTS=http://localhost:8082
#              (etc. — for whichever services you want to run locally too)
#    Or just leave them unset and routes will point to the k3s pods via cluster DNS
#    (IntelliJ gateway won't be inside the cluster, so cluster DNS won't resolve)
#    → Best practice: run all other services in k3s; only bring out the one you're working on
```

**Tracing:** 100% sampling → Zipkin at `http://${ZIPKIN_HOST:localhost}:9411`

---

### 6.3 STAGE (stage profile — AWS EKS)

**JWKS URI in `application-stage.yml`:**
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS_URI}   # no default — must be injected
```

`KEYCLOAK_JWKS_URI` has no default. If it is not set, the application will **fail to start**.
This is intentional — a misconfigured stage deployment should fail loudly.

**Where `KEYCLOAK_JWKS_URI` comes from:**
```yaml
# retailstore-platform/helm/stage/gateway.yaml
appEnv:
  SPRING_PROFILES_ACTIVE: "stage"
  KEYCLOAK_JWKS_URI: ""   # Replace: https://auth.stage.retailstore.com/realms/retailstore/protocol/openid-connect/certs
  REDIS_HOST: ""           # Replace: ElastiCache primary endpoint
  REDIS_PORT: "6379"
  ALLOWED_ORIGIN: ""       # Replace: stage CloudFront / ALB URL
  TRACING_ENDPOINT: ""     # Replace: tracing backend URL
```

**Additional stage settings:**
- Redis SSL enabled (`spring.data.redis.ssl.enabled: true`)
- Health details: `when-authorized` (not `always`)
- Circuit breaker windows widened (more traffic → larger sliding window)
- Tracing: 10% sampling

**Deploying:**
```bash
# After Terraform provisions EKS + RDS + ElastiCache + Keycloak:
# 1. Fill in all "" placeholders in helm/stage/gateway.yaml
# 2. Deploy:
helm upgrade --install gateway api-gateway/chart \
  -f retailstore-platform/helm/stage/gateway.yaml \
  -n retailstore \
  --set image.repository=<ECR_REGISTRY>/retailstore/gateway \
  --set image.tag=<IMAGE_TAG>
```

---

### 6.4 PROD (prod profile — AWS EKS HA)

Auth configuration is **identical to stage** — same `${KEYCLOAK_JWKS_URI}` pattern, same
K8s env var injection, same JWKS caching behavior. The only auth-adjacent differences are
security posture settings:

| Setting | STAGE | PROD |
|---------|-------|------|
| `health.show-details` | `when-authorized` | **`never`** |
| Circuit breaker failure rate | 50% | **40%** |
| Circuit breaker wait open | 10s | **15s** |
| Slow call threshold | Not configured | **3s / 2s per route** |
| Tracing sampling | 10% | **5%** |
| Redis timeout | 2000ms | **1500ms** |

**`helm/prod/gateway.yaml`** follows the same structure as stage. Replace the same set of
`""` placeholders with prod AWS resource endpoints.

---

## 7. Environment Variable Reference

| Variable | Profiles | Default | Description |
|----------|----------|---------|-------------|
| `KEYCLOAK_JWKS_URI` | local, (base fallback) | `http://localhost:8180/realms/retailstore/protocol/openid-connect/certs` | Full JWKS URL (used in stage/prod directly) |
| `KEYCLOAK_HOST` | dev | `localhost` | Keycloak hostname — `keycloak` in k3s, `localhost` when port-forwarding |
| `KEYCLOAK_PORT` | dev | `8180` | Keycloak HTTP port |
| `REDIS_HOST` | all | `localhost` | Redis host for rate limiting |
| `REDIS_PORT` | all | `6379` | Redis port |
| `ALLOWED_ORIGIN` | all | `https://shop.retailstore.com` | CORS allowed origin for web-storefront |
| `ZIPKIN_HOST` | dev | `localhost` | Zipkin host for traces |
| `TRACING_ENDPOINT` | stage, prod | — | Full tracing backend URL (no default in prod) |
| `RETAIL_GATEWAY_ROUTES_EXPERIENCE` | all | `http://experience` | Upstream URL for experience-service |
| `RETAIL_GATEWAY_ROUTES_CATALOG` | all | `http://catalog` | Upstream URL for catalog-service |
| `RETAIL_GATEWAY_ROUTES_CARTS` | all | `http://carts` | Upstream URL for cart-service |
| `RETAIL_GATEWAY_ROUTES_CHECKOUT` | all | `http://checkout` | Upstream URL for checkout-service |
| `RETAIL_GATEWAY_ROUTES_ORDERS` | all | `http://orders` | Upstream URL for order-service |

---

## 8. Troubleshooting

### 401 on every request — gateway running in IntelliJ

Most likely cause: Keycloak is not reachable on `localhost:8180`.

```bash
# Check Keycloak is reachable
curl http://localhost:8180/realms/retailstore/.well-known/openid-configuration

# If no response:
# LOCAL  → Docker container not started; run the docker run command from Section 6.1
# DEV    → port-forward not running; run: ./retailstore-platform/scripts/port-forward.sh start
```

### 401 — "Token validation failed: …"

The token signature check failed. Possible causes:

| Cause | Fix |
|-------|-----|
| Token issued by a different Keycloak realm or instance | Ensure JWKS URI points to the same Keycloak that issued the token |
| Token expired | Get a fresh token; check system clock skew |
| JWKS cache has stale keys after Keycloak key rotation | Restart the gateway to force JWKS re-fetch |

### Gateway pod fails to start (stage/prod) — "Could not fetch JWKS"

`KEYCLOAK_JWKS_URI` is empty or wrong. Check the Helm values:
```bash
kubectl describe pod <gateway-pod> -n retailstore
# Look for: "IllegalArgumentException: JWK Set URI must not be empty"
kubectl get configmap gateway-config -n retailstore -o yaml
# Verify KEYCLOAK_JWKS_URI value
```

### Downstream service gets empty X-User-Id

The JWT `sub` claim was null. This should not happen with user tokens. If a service receives
a Client Credentials token (M2S) at an endpoint intended for users, the `sub` will be the
client ID, not a user UUID. Downstream services should check `X-User-Role: service-role` to
detect M2S tokens.

### How to decode a JWT locally

```bash
# Get a test token (direct grant — dev/local only)
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -d "grant_type=password&client_id=web-storefront&username=testuser&password=password123" \
  | jq -r '.access_token')

# Decode claims (no signature check)
echo $TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | jq .

# Test against the gateway
curl -v -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/catalog/products
```
