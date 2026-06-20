# Microservice Authentication Patterns

> This document explains the three main industry patterns for handling authentication across
> microservices — what they are, how they work, their tradeoffs, and when each is the right
> choice. The goal is to help you understand where this project's design sits and what a
> production system at scale would look like.
>
> For how authentication is implemented in this specific project, see
> [`authentication.md`](authentication.md).

---

## The Core Problem

In a monolith, authentication is straightforward — one app, one security filter, done.
In microservices, every service is a separate process reachable over a network. Three questions
arise for every internal call:

1. **Who is calling me?** (identity)
2. **Are they allowed to?** (authorization)
3. **How do I trust that claim?** (verification)

The three patterns below are different answers to these questions.

---

## Pattern 1 — All Traffic via API Gateway

### How it works

Every request — both from external clients (browser, mobile) and between internal services —
passes through the API gateway. The gateway is the only component that performs token
validation. Services behind it are considered trusted if the request came through the gateway.

```
External
────────
Browser ──► API Gateway ──► catalog-service
Mobile  ──► API Gateway ──► cart-service

Internal (S2S)
──────────────
experience-service ──► API Gateway ──► catalog-service
checkout-service   ──► API Gateway ──► order-service
```

### Request flow

```
Browser
  │
  │  GET /api/v1/catalog/products
  │  Authorization: Bearer <user_token>
  ▼
API Gateway
  ├── Validates JWT signature (JWKS from Keycloak)
  ├── Checks expiry, roles
  ├── Injects X-User-Id, X-User-Role headers
  └── Routes to catalog-service
        │
        │  catalog-service receives:
        │  X-User-Id: <userId>         ← trusts this, no token validation
        │  X-User-Role: CUSTOMER
        ▼
      catalog-service controller
        └── Uses X-User-Id for data filtering

experience-service (internal call)
  │
  │  GET /api/v1/catalog/products
  │  X-Internal-Service: experience-service   ← or a service token, or nothing
  ▼
API Gateway
  ├── Recognises internal caller
  ├── May apply a different policy (e.g. skip JWT check, just rate limit)
  └── Routes to catalog-service
```

### What the downstream services look like

Services have **no auth code** — they trust the headers the gateway injects:

```java
@GetMapping("/products")
public ResponseEntity<Page<Product>> getProducts(
        @RequestHeader(value = "X-User-Id", required = false) String userId,
        @RequestHeader(value = "X-User-Role", required = false) String role) {
    // No JWT parsing, no JWKS, no Spring Security oauth2ResourceServer
    // Just use the headers
}
```

### Pros

- **Single security control point** — all validation, logging, and rate limiting in one place
- **Simpler services** — downstream code has zero auth dependencies
- **Easy audit trail** — every request is visible at the gateway
- **Consistent policy enforcement** — a single config change applies everywhere
- **Good for compliance** — regulators like one choke point they can inspect

### Cons

- **Gateway is a bottleneck** — all internal traffic flows through it; under high load it
  saturates first
- **Single point of failure** — if the gateway is down, the entire system is down
  (mitigated with multiple replicas, but still a risk)
- **Extra network hop on every internal call** — `experience-service → gateway → catalog`
  adds latency compared to a direct call
- **Gateway must know all internal routes** — even pure backend-to-backend routes need
  gateway config entries
- **Tight coupling to the gateway** — internal services can't move or rename without
  updating gateway routes

### When to use

- Small teams with few services (< 10)
- Companies with strong compliance/audit requirements (banks, healthcare, government)
- When operational simplicity matters more than performance
- When the internal call volume is low relative to external calls

### Real-world users

Wells Fargo, HSBC (banking), many enterprise/corporate IT systems, AWS API Gateway patterns
used by smaller SaaS companies.

---

## Pattern 2 — Direct S2S with Per-Service JWT Validation

### How it works

The API gateway handles **external** traffic (browser → services). Internal service-to-service
calls **bypass the gateway** and go directly. Each service independently validates the token
that arrives at its boundary — whether that's a user JWT (forwarded from the gateway) or a
service JWT (issued via Client Credentials by the calling service).

This is the pattern used in **this project**.

```
External
────────
Browser ──► API Gateway ──► experience-service
                        ──► catalog-service (direct browser→gateway→catalog)

Internal (S2S — direct, no gateway)
────────────────────────────────────
experience-service ──────────────────────────────► catalog-service
checkout-service   ──────────────────────────────► order-service
```

### Request flow (two paths into catalog-service)

**Path A — Browser request routed by gateway:**
```
Browser
  │  Authorization: Bearer <user_token>
  ▼
API Gateway
  ├── Validates user JWT (JWKS)
  ├── Injects X-User-Id, X-User-Role
  └── Forwards to catalog-service ──► still carries Authorization: Bearer <user_token>
                                       + X-User-Id, X-User-Role headers

catalog-service (Spring Security)
  ├── oauth2ResourceServer validates user JWT signature via JWKS  ← full check
  ├── Request passes
  └── Controller reads X-User-Role for data filtering
```

**Path B — S2S call from experience-service:**
```
experience-service
  │
  │  (1) POST Keycloak /token  →  client_credentials grant
  │      client_id=experience-service, client_secret=<secret>
  │  ←   access_token (service JWT, 5-min TTL, cached in JVM)
  │
  │  (2) GET /api/v1/catalog/products
  │      Authorization: Bearer <service_token>
  ▼
catalog-service (Spring Security)
  ├── oauth2ResourceServer validates SERVICE JWT signature via JWKS  ← full check
  ├── Reads realm_access.roles → [service-role]
  ├── Request passes
  └── Controller handles the request (no X-User-* headers in this path)
```

The critical point: **catalog-service applies the same JWKS validation regardless of which
path the request came from.** It does not assume the caller is trustworthy — it verifies
the cryptographic proof.

### What the downstream services look like

Each service has a Spring Security config:

```java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))  // validates any Keycloak JWT
            .build();
    }
}
```

And each has a JWKS URI in `application.yml`:

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS_URI:http://localhost:8180/realms/retailstore/protocol/openid-connect/certs}
```

### Pros

- **No gateway bottleneck** — internal calls are direct, one less network hop
- **Resilient** — gateway outage doesn't break internal service workflows
- **Each service is self-contained** — can be deployed independently, tested independently
- **Scales horizontally** — no single choke point under high internal call volume
- **Defense in depth** — even if an attacker gains internal network access, they still need a
  valid Keycloak-issued JWT to get past any service

### Cons

- **Auth config duplicated** — every service has its own JWKS URI, Spring Security setup,
  Keycloak client config
- **Every service needs Keycloak connectivity** — JWKS fetch at startup (lazy) means all
  services depend on Keycloak being reachable
- **Harder to audit** — traffic is distributed; you need centralised log aggregation to see
  the full picture
- **Client Credentials secrets to manage** — each calling service needs its own `client_id`
  and `client_secret` in Keycloak and in Helm values
- **Risk of inconsistent policies** — if you update a security rule, you may need to update
  multiple services

### When to use

- Medium-sized teams (5–50 services)
- Cloud-native architectures where each service owns its own security
- When internal call volume is high and gateway latency matters
- When services need to be independently deployable with no gateway dependency

### Real-world users

Many AWS-based SaaS companies, Spotify (early architecture), most Spring Boot / cloud-native
shops at the mid-size stage.

---

## Pattern 3 — Service Mesh with mTLS

### How it works

A **sidecar proxy** (a small process) runs next to every service pod. All network traffic in
and out of a service goes through its sidecar — the application never talks to the network
directly. The sidecars form a **mesh** and handle mutual TLS (mTLS) transparently.

JWT tokens (like Keycloak-issued tokens) are only needed at the **external edge** (the API
gateway). Internal service communication is authenticated at the **network layer** by the mesh
— no application code needed.

```
┌─────────────────────────────────────────────────────────┐
│  k8s cluster                                            │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │  experience-service pod                          │   │
│  │  ┌──────────────────┐  ┌──────────────────────┐ │   │
│  │  │  app container   │◄─│  Envoy sidecar proxy  │ │   │
│  │  └──────────────────┘  └────────────┬─────────┘ │   │
│  └────────────────────────────────────┼────────────┘   │
│                               mTLS    │                 │
│                        (encrypted +   │                 │
│                         identity      │                 │
│                         verified)     │                 │
│  ┌────────────────────────────────────┼────────────┐   │
│  │  catalog-service pod               │             │   │
│  │  ┌──────────────────┐  ┌──────────▼───────────┐ │   │
│  │  │  app container   │◄─│  Envoy sidecar proxy  │ │   │
│  │  └──────────────────┘  └──────────────────────┘ │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  Control Plane (Istio / Linkerd)                        │
│  ├── Issues short-lived certificates to each sidecar   │
│  ├── Enforces policies (who can call whom)              │
│  └── Rotates certs automatically (no manual management) │
└─────────────────────────────────────────────────────────┘
```

### What mTLS means

Normal TLS (HTTPS): the **server** proves its identity to the client with a certificate.
**Mutual** TLS: **both** the server and the client prove their identities with certificates.

```
experience-service sidecar                catalog-service sidecar
        │                                          │
        │  "Here is my certificate.                │
        │   I am experience-service.               │
        │   Issued and signed by the mesh CA."     │
        │─────────────────────────────────────►    │
        │                                          │
        │    "Here is MY certificate.              │
        │     I am catalog-service.                │
        │     Issued and signed by the mesh CA."   │
        │◄─────────────────────────────────────    │
        │                                          │
        │  Both identities verified. Encrypted     │
        │  channel established. App traffic flows. │
```

The mesh **control plane** (Istio, Linkerd) acts as the Certificate Authority — it issues
short-lived certificates (typically 24h, auto-rotated) to each sidecar. No human manages
these certs.

### What the application code looks like

```java
// catalog-service SecurityConfig in a mesh environment
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        return http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .anyRequest().permitAll()    // ← mesh handles auth; app trusts the sidecar
            )
            .build();
        // No oauth2ResourceServer — no JWKS — no Keycloak dependency
    }
}
```

The application has **zero auth code** for internal calls. Policy is managed in the mesh
control plane:

```yaml
# Istio AuthorizationPolicy — only experience-service can call catalog-service
apiVersion: security.istio.io/v1beta1
kind: AuthorizationPolicy
metadata:
  name: catalog-allow-experience
  namespace: retailstore
spec:
  selector:
    matchLabels:
      app: catalog-service
  rules:
    - from:
        - source:
            principals: ["cluster.local/ns/retailstore/sa/experience-service"]
```

### Key concepts

| Term | What it means |
|------|---------------|
| **Sidecar** | A second container in the same pod as your service; intercepts all network I/O |
| **Envoy** | The most common sidecar proxy, used by Istio |
| **Istio** | The most popular service mesh; uses Envoy sidecars + a control plane |
| **Linkerd** | A lighter alternative to Istio; simpler but fewer features |
| **mTLS** | Both sides of a connection prove identity with certificates |
| **SPIFFE** | A standard for workload identity in mesh environments (`spiffe://cluster/ns/sa/...`) |
| **Control plane** | The brain of the mesh — issues certs, distributes policy, observes traffic |
| **Data plane** | The sidecars themselves — enforce policy, encrypt traffic, collect telemetry |

### JWT + mesh — where does Keycloak fit?

In a mesh architecture, JWTs are still used at the **external boundary** (browser → gateway).
Internally, the mesh provides identity — no JWT needed for S2S calls.

```
Browser ──JWT──► API Gateway ──(validates JWT)──► experience-service
                                                        │
                                                        │  mTLS only — no JWT
                                                        ▼
                                                  catalog-service
```

Some systems combine both: the gateway validates and then passes a stripped-down JWT
internally as a "caller identity assertion" for fine-grained authz, while mTLS handles
transport security. But this is optional and adds complexity.

### Pros

- **Zero application auth code for internal calls** — services don't know or care about mTLS
- **Automatic certificate rotation** — no manual cert management, no secret expiry surprises
- **Network-level encryption everywhere** — even intra-cluster traffic is encrypted
- **Fine-grained policy without code changes** — add an AuthorizationPolicy YAML to block
  a call path, no service deployment needed
- **Full observability built in** — Istio/Linkerd give distributed tracing, metrics, and
  service graphs out of the box
- **Works for any language** — Python, Go, Node, Java — the sidecar doesn't care

### Cons

- **Significant operational complexity** — Istio is notoriously hard to operate correctly
- **Resource overhead** — each sidecar uses memory and CPU (typically 50–100MB RAM per pod)
- **Debugging is harder** — network issues are inside the sidecar, not the app
- **Requires a platform team** — someone needs to own the mesh, the CA, the policies
- **Slow to adopt** — migrating an existing system to a mesh is a large effort

### When to use

- Large teams with 20+ services
- When you have (or are building) a dedicated platform/infrastructure team
- When cross-cutting concerns (observability, security policy, traffic management) need to
  be consistent across services written in different languages
- Kubernetes-native environments where the mesh integrates naturally

### Real-world users

Netflix (Envoy), Uber (internal mesh), Airbnb, Google (Traffic Director / Envoy), Lyft
(invented Envoy), LinkedIn, Twitter.

---

## Pattern Comparison

| | Pattern 1: All via Gateway | Pattern 2: Direct S2S + JWT | Pattern 3: Service Mesh |
|---|---|---|---|
| **S2S path** | Through gateway | Direct | Direct (mTLS via sidecar) |
| **Who validates tokens** | Gateway only | Each service (JWKS) | Gateway for user tokens; mesh for S2S |
| **Application auth code** | None in downstream services | Every service (Spring Security) | None (mesh handles it) |
| **Internal latency** | Higher (extra hop) | Lower | Lowest |
| **Bottleneck risk** | High (gateway) | Low | None |
| **Security model** | Perimeter trust | Zero trust (verify at every boundary) | Zero trust (network layer) |
| **Operational complexity** | Low | Medium | High |
| **Best for** | Small teams, compliance | Mid-size cloud-native | Large scale, polyglot |
| **Audit / observability** | Easy (one place) | Needs central log aggregation | Built-in (mesh telemetry) |
| **Secret management** | Gateway config only | Keycloak client secrets per service | Mesh-managed certs (auto-rotated) |

---

## Zero Trust — The Concept Behind It All

All three patterns are implementations of a security philosophy called **zero trust**:

> *Never trust, always verify.* Assume the network is hostile. Do not trust a request just
> because it came from inside the cluster.

- Pattern 1 trusts the gateway perimeter — requests that get past the gateway are trusted.
  This is **perimeter security** (not true zero trust).
- Pattern 2 trusts nothing — every service verifies the token, even on internal calls.
  This is **application-level zero trust**.
- Pattern 3 trusts nothing at the network level — every connection is mutually authenticated.
  This is **network-level zero trust**.

Pattern 3 is the strongest model. Pattern 2 (this project) is a practical and common middle
ground. Pattern 1 is the simplest but weakest from a zero trust perspective.

---

## Where This Project Sits — and What's Missing

This project uses **Pattern 2**. The design is correct and production-grade for a small-to-medium
team. The one gap worth noting:

**No Kubernetes NetworkPolicy.** Right now, any pod inside the cluster can send a raw HTTP
request to `catalog-service:8080` without a JWT. Spring Security will reject it (401), but the
request still reaches the application. A `NetworkPolicy` closes this at the network level:

```yaml
# Only experience-service pods can reach catalog-service
apiVersion: networking.k8s.io/v1
kind: NetworkPolicy
metadata:
  name: catalog-ingress
  namespace: retailstore
spec:
  podSelector:
    matchLabels:
      app: catalog-service
  ingress:
    - from:
        - podSelector:
            matchLabels:
              app: api-gateway          # browser → gateway → catalog
        - podSelector:
            matchLabels:
              app: experience-service   # S2S from experience
  policyTypes:
    - Ingress
```

With this in place, the security model is: network blocks unauthorized callers first, JWT
validation is the second line of defence. That is defence in depth — the approach real
production systems use.

---

## Evolution Path

If this project grows, here is the natural upgrade path:

```
Current (Pattern 2)
  Direct S2S + per-service JWT

  ↓ Add NetworkPolicy
  Direct S2S + per-service JWT + network isolation
  (good for production at this scale)

  ↓ Add Istio/Linkerd (when 20+ services or polyglot)
  Direct S2S + mTLS (Pattern 3)
  Drop per-service JWT validation for internal calls
  Keep JWT at the gateway for user-facing requests

  ↓ Optional: add internal JWT assertion
  mTLS for transport + short-lived internal token for fine-grained authz per call
  (used by Google, Netflix at extreme scale)
```
