# Authentication & Authorization — Technical Design

> **How to use this document:**  
> This is the platform-level auth reference — concepts, flows, JWT structure, Keycloak realm
> design, and how all services connect. Read this first to understand the whole picture.  
> For implementation details inside a specific service, go to that service's doc:
>
> | Service | What it covers |
> |---------|---------------|
> | [`api-gateway/documents/authentication.md`](../api-gateway/documents/authentication.md) | `GlobalJwtFilter` steps, JWKS wiring per env, public paths, troubleshooting |
> | [`web-storefront/documents/authentication.md`](../web-storefront/documents/authentication.md) | PKCE flow, `oidc-client-ts` setup, silent renewal, env config, testing |
> | [`experience-service/documents/authentication.md`](../experience-service/documents/authentication.md) | Inbound JWT validation, `ServiceTokenProvider`, parallel S2S calls to catalog/cart/orders |
> | [`checkout-service/documents/authentication.md`](../checkout-service/documents/authentication.md) | Inbound JWT validation, `ServiceTokenProvider`, checkout→order-service submit flow |

---

## Environment Quick Reference

DEV is genuinely different from STAGE/PROD. STAGE and PROD are auth-identical — they share
the same Spring config pattern, the same Keycloak deployment approach, and the same K8s Secrets
pattern. PROD only tightens non-auth settings on top.

| | LOCAL (default) | DEV (k3s on EC2) | STAGE (EKS) | PROD (EKS) |
|---|---|---|---|---|
| **Keycloak** | Bitnami k3s pod (Rancher Desktop) | Bitnami k3s pod (EC2) | Bitnami EKS pod | Bitnami EKS HA (2 pods) |
| **Keycloak DB** | MySQL `keycloakdb` on k3s | MySQL `keycloakdb` on k3s | RDS MySQL (shared) | RDS MySQL (dedicated) |
| **JWKS URI pattern** | `${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}/…` | Same `${VAR:localhost}` defaults | `${KEYCLOAK_JWKS_URI}` (no default) | Same as stage |
| **Token URI (M2S)** | Same HOST/PORT pattern | Same HOST/PORT pattern | `${KEYCLOAK_TOKEN_URI}` (no default) | Same as stage |
| **Client secrets** | Helm local values (plain text, dev only) | Helm dev values + `optional: true` k8s Secret | K8s Secret (required) | AWS Secrets Manager |
| **Realm import** | k8s ConfigMap → pod mount | k8s ConfigMap → pod mount | Manual or Terraform | Same as stage |
| **Spring profile** | `dev` | `dev` | `stage` | `prod` |
| **Image source** | Local Docker build (`imagePullPolicy: Never`) | ECR registry (`imagePullPolicy: IfNotPresent`) | ECR | ECR |
| **MySQL SSL** | No | No | Yes (`useSSL=true&requireSSL=true`) | Yes |
| **Swagger** | Enabled | Enabled | Enabled (internal only) | **Disabled** |
| **Health show-details** | `always` | `always` | `when-authorized` | `never` |
| **Tracing sampling** | None | 100% → Zipkin | 10% → tracing backend | 5% → tracing backend |
| **Issuer validation** | Disabled | Disabled | Disabled | Disabled |

> **Why issuer validation is disabled in all envs:** Keycloak inside k3s issues tokens
> with `iss = http://keycloak:8180/realms/retailstore` (cluster DNS). IntelliJ connecting
> via port-forward expects `http://localhost:8180`. Using only `jwk-set-uri` (not
> `issuer-uri`) avoids this mismatch while still validating RS256 signature and expiry.

---

## 1. System Architecture (After Refactor)

```
┌─────────────────────────────────────────────────────────────────────────────┐
│                           EXTERNAL                                           │
│                                                                              │
│   Browser (React SPA)                  Keycloak 25                          │
│        │                               (Identity Provider)                  │
│        │  1. Redirect to Keycloak       ┌──────────────────┐                │
│        │─────────────────────────────►  │  /realms/retail  │                │
│        │                               │  store            │                │
│        │  2. Login (Keycloak UI)        │                  │                │
│        │◄─────────────────────────────  │  Endpoints:      │                │
│        │  3. Authorization Code         │  /auth            │                │
│        │  4. Code → Token exchange      │  /token           │                │
│        │─────────────────────────────►  │  /certs (JWKS)   │                │
│        │◄─────────────────────────────  │  /userinfo        │                │
│        │  5. access_token (JWT RS256)   │  /logout          │                │
│        │     id_token                   │                  │                │
│        │     refresh_token              └──────────────────┘                │
└────────│──────────────────────────────────────┬──────────────────────────────┘
         │                                      │ (JWKS fetch on startup + cache)
         ▼  6. API call (Bearer token)          │
┌──────────────────────────────────────────────▼──────────────────────────────┐
│                           INTERNAL (EKS cluster)                             │
│                                                                              │
│  api-gateway (Spring Cloud Gateway)                                          │
│  ┌───────────────────────────────────────────────────────────────────────┐  │
│  │  GlobalJwtFilter (order: -3)                                          │  │
│  │  ├── Skip: /actuator/**                                               │  │
│  │  ├── Extract Bearer token from Authorization header                   │  │
│  │  ├── Validate: signature (RS256 via JWKS), expiry, issuer, audience   │  │
│  │  ├── On failure → 401 Unauthorized (short-circuit)                    │  │
│  │  └── On success → mutate request:                                     │  │
│  │       add X-User-Id:    {sub claim}                                   │  │
│  │       add X-User-Email: {email claim}                                 │  │
│  │       add X-User-Role:  {realm_access.roles[0]}                       │  │
│  │       add X-User-Name:  {preferred_username}                          │  │
│  └───────────────────────────────────────────────────────────────────────┘  │
│           │                                                                  │
│           │  Route to downstream with enriched headers                       │
│           │                                                                  │
│  ┌────────▼─────────┐   ┌───────────────────────────────────────────────┐   │
│  │ experience-      │   │  Direct routes (catalog, cart, checkout,       │   │
│  │ service (BFF)    │   │  orders) — each independently validates the    │   │
│  │                  │   │  incoming JWT via Spring Security + JWKS       │   │
│  │ Calls downstream │   └───────────────────────────────────────────────┘   │
│  │ with Client      │                                                        │
│  │ Credentials JWT  │                                                        │
│  │ (service token)  │                                                        │
│  └──────────────────┘                                                        │
│         │                                                                    │
│         │  Service-to-Service (OAuth2 Client Credentials)                   │
│         │  Authorization: Bearer <service_access_token>                     │
│         ▼                                                                    │
│  ┌──────────┐  ┌──────────┐  ┌──────────┐  ┌──────────┐                   │
│  │ catalog  │  │  cart    │  │ checkout │  │  orders  │                   │
│  │ :8081    │  │  :8082   │  │  :8083   │  │  :8084   │                   │
│  └──────────┘  └──────────┘  └──────────┘  └──────────┘                   │
└──────────────────────────────────────────────────────────────────────────────┘
```

---

## 2. Keycloak Realm Design

### Realm: `retailstore`

```
Realm: retailstore
│
├── Clients (OAuth2 applications)
│   ├── web-storefront                 ← Public client (SPA — no secret)
│   │   ├── Flow: Authorization Code + PKCE
│   │   ├── Valid redirect URIs: http://localhost:3000/*, https://shop.retailstore.com/*
│   │   ├── PKCE: S256 required
│   │   └── Scopes: openid, profile, email, roles
│   │
│   ├── experience-service             ← Confidential client (server-side)
│   │   ├── Flow: Client Credentials
│   │   ├── Client secret: (managed via Kubernetes secret)
│   │   └── Service account roles: service-role
│   │
│   ├── checkout-service               ← Confidential client (server-side)
│   │   ├── Flow: Client Credentials
│   │   └── Service account roles: service-role
│   │
│   └── api-gateway                    ← Resource server (validates tokens only)
│       └── No flow; just validates tokens issued to other clients
│
├── Roles (Realm Roles)
│   ├── CUSTOMER                       ← Default role for registered users
│   ├── ADMIN                          ← Admin users
│   ├── SUPPORT                        ← Support users
│   └── service-role                   ← Service accounts (M2S)
│
└── Users
    └── (Managed via Keycloak Admin UI or API)
```

### Token Claims (access_token payload)

```json
{
  "sub": "550e8400-e29b-41d4-a716-446655440000",   ← userId
  "iss": "http://keycloak:8180/realms/retailstore",
  "aud": ["web-storefront", "account"],
  "exp": 1735689600,
  "iat": 1735603200,
  "email": "user@example.com",
  "preferred_username": "john.doe",
  "given_name": "John",
  "family_name": "Doe",
  "name": "John Doe",
  "realm_access": {
    "roles": ["CUSTOMER", "default-roles-retailstore"]
  },
  "email_verified": true
}
```

### Service Token Claims (Client Credentials)

```json
{
  "sub": "experience-service",              ← clientId
  "iss": "http://keycloak:8180/realms/retailstore",
  "aud": "account",
  "exp": 1735603500,
  "realm_access": {
    "roles": ["service-role"]
  },
  "azp": "experience-service"
}
```

---

## 3. OAuth2 Flows

### Flow 1: Browser Login (Authorization Code + PKCE)

```
Browser                   Keycloak                  api-gateway
   │                          │                          │
   │  1. GET /                │                          │
   │─────────────────────────────────────────────────►  │
   │                          │           401 (no token) │
   │◄────────────────────────────────────────────────── │
   │                          │                          │
   │  2. Redirect to Keycloak /auth?                     │
   │     client_id=web-storefront                        │
   │     redirect_uri=http://localhost:3000/callback     │
   │     response_type=code                              │
   │     code_challenge=<S256_hash>                      │
   │     code_challenge_method=S256                      │
   │─────────────────────►    │                          │
   │                          │                          │
   │  3. Login form (Keycloak hosted)                    │
   │◄─────────────────────    │                          │
   │  email + password        │                          │
   │─────────────────────►    │                          │
   │                          │                          │
   │  4. Redirect back with code                         │
   │◄─────────────────────    │                          │
   │  http://localhost:3000/callback?code=xyz            │
   │                          │                          │
   │  5. POST /token          │                          │
   │     grant_type=authorization_code                   │
   │     code=xyz                                        │
   │     code_verifier=<original_random>                 │
   │─────────────────────►    │                          │
   │                          │                          │
   │  6. access_token (JWT RS256)                        │
   │     id_token                                        │
   │     refresh_token                                   │
   │◄─────────────────────    │                          │
   │                          │                          │
   │  7. API calls with Authorization: Bearer <token>    │
   │─────────────────────────────────────────────────►  │
   │                          │   Validate (JWKS cache)  │
   │                          │─────────────────────►    │
   │                          │◄─────────────────────    │
   │  8. Response             │                          │
   │◄────────────────────────────────────────────────── │
```

### Flow 2: Service-to-Service (Client Credentials)

Two services make outbound S2S calls. Both **bypass the api-gateway entirely** and call
downstream services directly over the internal cluster network:

- `experience-service` → `catalog-service`, `cart-service`, `order-service` (BFF aggregation)
- `checkout-service` → `order-service` (creates the permanent order record on submit)

**Flow 2a — experience-service → catalog-service** (same pattern for cart and order)

```
experience-service                  Keycloak            catalog-service
       │                                │                      │
       │  (first outbound call)         │                      │
       │  POST /realms/retailstore/protocol/openid-connect/token
       │  grant_type=client_credentials │                      │
       │  client_id=experience-service  │                      │
       │  client_secret=<secret>        │                      │
       │─────────────────────────►      │                      │
       │                                │                      │
       │  access_token (5-min JWT)      │                      │
       │  { sub: "experience-service",  │                      │
       │    realm_access.roles:         │                      │
       │      ["service-role"] }        │                      │
       │◄─────────────────────────      │                      │
       │                                │                      │
       │  Token cached in JVM until expiry − 30s buffer        │
       │  Subsequent calls reuse cached token (no Keycloak hit)│
       │                                │                      │
       │  GET /api/v1/catalog/products  │                      │
       │  Authorization: Bearer <service_token>                │
       │───────────────────────────────────────────────────►  │
       │                                │                      │
       │                                │  Spring Security intercepts
       │                                │  Fetches JWKS from Keycloak (cached, lazy)
       │                                │  Verifies RS256 signature ← full crypto check
       │                                │  Verifies token expiry
       │                                │  Reads realm_access.roles → [service-role]
       │                                │  ✓ Request passes to controller
       │                                │                      │
       │  200 OK                        │                      │
       │◄───────────────────────────────────────────────────  │
```

**Flow 2b — checkout-service → order-service**

```
checkout-service                  Keycloak              order-service
       │                              │                       │
       │  POST /token                 │                       │
       │  grant_type=client_credentials                       │
       │  client_id=checkout-service  │                       │
       │  client_secret=<secret>      │                       │
       │─────────────────────────►    │                       │
       │  access_token (5-min JWT)    │                       │
       │◄─────────────────────────    │                       │
       │                              │                       │
       │  POST /api/v1/orders         │                       │
       │  Authorization: Bearer <service_token>               │
       │  Body: { customerId, lineItems, shippingAddress, total, ... }
       │─────────────────────────────────────────────────►   │
       │                              │                       │
       │                              │  Verifies RS256 signature (JWKS)
       │                              │  Reads realm_access.roles → [service-role]
       │                              │  ✓ Request passes to controller
       │                              │                       │
       │  201 Created { id: "ord-..." }                        │
       │◄─────────────────────────────────────────────────   │
```

> **Independent validation at every service boundary:** Each downstream service validates the
> incoming JWT independently via its own JWKS lookup — it never trusts that a caller already
> checked it. The gateway validates user tokens; downstream services validate whatever token
> arrives at their boundary: a **user token** when the request was routed from the gateway, or
> a **service token** when called by `experience-service` or `checkout-service`. Both are
> Keycloak-issued RS256 JWTs — the receiving service can't tell the difference in the validation
> step; it just checks the signature and reads the claims. Keycloak's public key is the single
> shared trust anchor across all services.

### Flow 3: Token Refresh

```
Browser                    Keycloak
   │                           │
   │  POST /token              │
   │  grant_type=refresh_token │
   │  refresh_token=<rt>       │
   │──────────────────────►    │
   │                           │
   │  new access_token         │
   │  new refresh_token        │  ← Keycloak rotates refresh tokens
   │◄──────────────────────    │
```

---

## 4. api-gateway Changes

### 4.1 New Dependencies (pom.xml)

```xml
<!-- JWT validation without Spring Security full stack -->
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

> **Note:** Spring Cloud Gateway is reactive (WebFlux). We use the reactive resource server,
> not the servlet version.

### 4.2 GlobalJwtFilter

```java
// api-gateway/src/main/java/com/retailstore/gateway/filter/GlobalJwtFilter.java
package com.retailstore.gateway.filter;

@Component
@RequiredArgsConstructor
public class GlobalJwtFilter implements GlobalFilter, Ordered {

    private final ReactiveJwtDecoder jwtDecoder;

    private static final List<String> PUBLIC_PATHS = List.of(
        "/actuator/",
        "/fallback/"
    );

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();
        if (PUBLIC_PATHS.stream().anyMatch(path::startsWith)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing Bearer token");
        }

        String token = authHeader.substring(7);
        return jwtDecoder.decode(token)
            .flatMap(jwt -> {
                ServerHttpRequest mutated = exchange.getRequest().mutate()
                    .header("X-User-Id",    jwt.getSubject())
                    .header("X-User-Email", getClaimAsString(jwt, "email"))
                    .header("X-User-Name",  getClaimAsString(jwt, "preferred_username"))
                    .header("X-User-Role",  extractPrimaryRole(jwt))
                    .build();
                return chain.filter(exchange.mutate().request(mutated).build());
            })
            .onErrorResume(JwtException.class, e ->
                unauthorized(exchange, "Invalid or expired token"));
    }

    private String extractPrimaryRole(Jwt jwt) {
        // Keycloak puts roles in realm_access.roles
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return "CUSTOMER";
        List<?> roles = (List<?>) realmAccess.get("roles");
        return roles == null || roles.isEmpty() ? "CUSTOMER"
            : roles.stream()
                .map(Object::toString)
                .filter(r -> List.of("CUSTOMER","ADMIN","SUPPORT","service-role").contains(r))
                .findFirst()
                .orElse("CUSTOMER");
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        byte[] body = ("{\"status\":401,\"error\":\"Unauthorized\",\"message\":\"" + message + "\"}").getBytes();
        DataBuffer buffer = exchange.getResponse().bufferFactory().wrap(body);
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }

    @Override public int getOrder() { return -3; }  // Before CorrelationId(-2) and Logging(-1)
}
```

### 4.3 SecurityConfig (Gateway)

```java
// api-gateway/src/main/java/com/retailstore/gateway/config/SecurityConfig.java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain springSecurityFilterChain(ServerHttpSecurity http) {
        http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/**", "/fallback/**").permitAll()
                .anyExchange().permitAll()   // JWT validated by GlobalJwtFilter, not Spring Security
            );
        return http.build();
    }

    @Bean
    public ReactiveJwtDecoder jwtDecoder(
            @Value("${spring.security.oauth2.resourceserver.jwt.jwk-set-uri}") String jwksUri) {
        return NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build();
    }
}
```

### 4.4 application.yml additions

```yaml
# base application.yml — works with default (local) and dev profiles
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          # Composed from HOST/PORT vars so the same yaml works for local and dev.
          # issuer-uri is intentionally NOT set — see auth-analysis.md Section 9.3
          # for why issuer validation is disabled across all environments.
          jwk-set-uri: >
            http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}
            /realms/retailstore/protocol/openid-connect/certs

# Add checkout route (previously missing)
routes:
  - id: checkout-service
    uri: ${RETAIL_GATEWAY_ROUTES_CHECKOUT:http://checkout}
    predicates:
      - Path=/api/v1/checkout/**
    filters:
      - name: CircuitBreaker
        args:
          name: checkout-cb
          fallbackUri: forward:/fallback/checkout

```

**application-dev.yml** (gateway):
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
k3s pod gets `KEYCLOAK_HOST=keycloak` from `helm/dev/gateway.yaml`. IntelliJ uses localhost default.

**application-stage.yml and application-prod.yml** (gateway):
```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS_URI}    # no default — must be injected from helm/stage/gateway.yaml
```

---

## 5. Downstream Service Changes (All Services)

### 5.1 New Dependency (all services pom.xml)

```xml
<dependency>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-oauth2-resource-server</artifactId>
</dependency>
```

### 5.2 Resource Server Config (catalog, cart, checkout, order)

```java
// Pattern: infrastructure/config/SecurityConfig.java
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(oauth2 -> oauth2
                .jwt(jwt -> jwt.jwkSetUri(jwkSetUri))   // validates service-to-service tokens
            )
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/actuator/**", "/swagger-ui/**", "/api-docs/**").permitAll()
                .anyRequest().authenticated()
            );
        return http.build();
    }
}
```

### 5.3 User Context Extraction (from gateway-forwarded headers)

```java
// Shared pattern — each service's controller uses this
// No JWT re-parsing needed — gateway already validated and forwarded claims

@RestController
@RequestMapping("/api/v1/orders")
public class OrderController {

    @GetMapping("/customer/{customerId}")
    public ResponseEntity<Page<OrderResponse>> getCustomerOrders(
            @PathVariable String customerId,
            @RequestHeader(value = "X-User-Id",   required = false) String requestingUserId,
            @RequestHeader(value = "X-User-Role", required = false) String userRole,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {

        // Fine-grained authorization: customer can only see their own orders
        if (!"ADMIN".equals(userRole) && !customerId.equals(requestingUserId)) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }

        return ResponseEntity.ok(orderService.getOrdersByCustomer(customerId, page, size));
    }
}
```

### 5.4 application.yml additions (all downstream services)

Same pattern as gateway. Base `application.yml` uses composed HOST/PORT:

```yaml
# application.yml (base — default and dev profiles)
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: >
            http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}
            /realms/retailstore/protocol/openid-connect/certs
```

```yaml
# application-stage.yml / application-prod.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS_URI}    # no default — injected from Helm values
```

---

## 6. Service-to-Service Token Management

### 6.1 experience-service (reactive — Mono)

`experience-service` is WebFlux (reactive). `ServiceTokenProvider` returns `Mono<String>` and
clients chain off it with `flatMap`.

**Config properties** (`application.yml` / `application-dev.yml` / `application-stage.yml`):

```yaml
retail:
  experience:
    keycloak:
      token-uri:     ${KEYCLOAK_TOKEN_URI:http://localhost:8180/realms/retailstore/protocol/openid-connect/token}
      client-id:     ${KEYCLOAK_CLIENT_ID:experience-service}
      client-secret: ${KEYCLOAK_CLIENT_SECRET:exp-service-secret}
```

```java
// experience-service/.../infrastructure/security/ServiceTokenProvider.java
@Component
public class ServiceTokenProvider {

    private final WebClient tokenClient;
    private final String clientId;
    private final String clientSecret;
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public ServiceTokenProvider(
            WebClient.Builder webClientBuilder,
            @Value("${retail.experience.keycloak.token-uri}") String tokenUri,
            @Value("${retail.experience.keycloak.client-id}") String clientId,
            @Value("${retail.experience.keycloak.client-secret}") String clientSecret) {
        this.tokenClient = webClientBuilder.baseUrl(tokenUri).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public Mono<String> getToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(30))) {
            return Mono.just(cachedToken);
        }
        return fetchToken();
    }

    private Mono<String> fetchToken() {
        return tokenClient.post()
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                .with("client_id", clientId)
                .with("client_secret", clientSecret))
            .retrieve()
            .bodyToMono(Map.class)
            .map(response -> {
                String token = (String) response.get("access_token");
                Integer expiresIn = (Integer) response.get("expires_in");
                cachedToken = token;
                tokenExpiry = Instant.now().plusSeconds(expiresIn != null ? expiresIn : 300);
                return token;
            });
    }
}
```

```java
// experience-service/.../infrastructure/client/CatalogClient.java
@Component
public class CatalogClient {

    @Qualifier("catalogClient")
    private final WebClient webClient;
    private final ServiceTokenProvider serviceTokenProvider;

    public Mono<Map> getProducts(int page, int size, String tags) {
        return serviceTokenProvider.getToken()
            .flatMap(token -> webClient.get()
                .uri(u -> u.path("/api/v1/catalog/products")
                    .queryParam("page", page).queryParam("size", size)
                    .queryParamIfPresent("tags", Optional.ofNullable(tags))
                    .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorReturn(Map.of("products", List.of())));
    }

    public Mono<Map> getProduct(String id) {
        return serviceTokenProvider.getToken()
            .flatMap(token -> webClient.get()
                .uri("/api/v1/catalog/products/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class));
    }
}
```

The `CartClient` follows the identical pattern — `getToken().flatMap(token -> ...)` — for
`GET /api/v1/carts/{customerId}` and `POST /api/v1/carts/{customerId}/items`.

---

### 6.2 checkout-service (blocking — synchronous)

`checkout-service` is servlet-based (Spring MVC). `ServiceTokenProvider` returns a plain
`String` and uses `.block()` inside the token fetch.

**Config properties**:

```yaml
retail:
  checkout:
    keycloak:
      token-uri:     ${KEYCLOAK_TOKEN_URI:http://localhost:8180/realms/retailstore/protocol/openid-connect/token}
      client-id:     ${KEYCLOAK_CLIENT_ID:checkout-service}
      client-secret: ${KEYCLOAK_CLIENT_SECRET:checkout-service-secret}
    endpoints:
      orders: ${RETAIL_CHECKOUT_ENDPOINTS_ORDERS:http://orders}
```

```java
// checkout-service/.../infrastructure/security/ServiceTokenProvider.java
@Component
public class ServiceTokenProvider {

    private final WebClient tokenClient;
    private final String clientId;
    private final String clientSecret;
    private volatile String cachedToken;
    private volatile Instant tokenExpiry = Instant.EPOCH;

    public ServiceTokenProvider(
            WebClient.Builder webClientBuilder,
            @Value("${retail.checkout.keycloak.token-uri}") String tokenUri,
            @Value("${retail.checkout.keycloak.client-id}") String clientId,
            @Value("${retail.checkout.keycloak.client-secret}") String clientSecret) {
        this.tokenClient = webClientBuilder.baseUrl(tokenUri).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String getToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(30))) {
            return cachedToken;
        }
        return fetchToken();
    }

    private String fetchToken() {
        Map<?, ?> response = tokenClient.post()
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                .with("client_id", clientId)
                .with("client_secret", clientSecret))
            .retrieve()
            .bodyToMono(Map.class)
            .block();

        if (response == null || response.get("access_token") == null) {
            throw new IllegalStateException("Keycloak returned no access_token");
        }
        String token = (String) response.get("access_token");
        Integer expiresIn = (Integer) response.get("expires_in");
        cachedToken = token;
        tokenExpiry = Instant.now().plusSeconds(expiresIn != null ? expiresIn : 300);
        return token;
    }
}
```

```java
// checkout-service/.../infrastructure/client/OrderServiceClient.java
@Component
public class OrderServiceClient {

    private final WebClient.Builder webClientBuilder;
    private final ServiceTokenProvider serviceTokenProvider;

    @Value("${retail.checkout.endpoints.orders:http://orders}")
    private String ordersEndpoint;

    public String placeOrder(CheckoutSession session) {
        WebClient client = webClientBuilder.baseUrl(ordersEndpoint).build();
        String serviceToken = serviceTokenProvider.getToken();

        Map<String, Object> orderRequest = Map.of(
            "customerId",        session.getCustomerId(),
            "checkoutSessionId", session.getSessionId(),
            "lineItems",         buildLineItems(session),
            "shippingAddress",   buildShippingAddress(session),
            "subtotal",          session.getPriceSummary().getSubtotal().toString(),
            "shippingCost",      session.getPriceSummary().getShippingCost().toString(),
            "total",             session.getPriceSummary().getTotal().toString()
        );

        Map<?, ?> response = client.post()
            .uri("/api/v1/orders")
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(orderRequest)
            .retrieve()
            .bodyToMono(Map.class)
            .block();

        return response != null ? String.valueOf(response.get("id")) : null;
    }
}
```

---

### 6.3 Token caching — both services

| | experience-service | checkout-service |
|---|---|---|
| **Return type** | `Mono<String>` (reactive) | `String` (blocking) |
| **Cache store** | `volatile` field in JVM heap | Same |
| **Cache lifetime** | Until pod restart or cache expiry | Same |
| **Expiry buffer** | 30s before actual expiry | Same |
| **On Keycloak down** | First call fails; retry on next request | Same (throws `IllegalStateException`) |
| **Thread safety** | `volatile` prevents stale reads; not atomic — rare double-fetch on concurrent miss is harmless | Same |

---

## 7. Web Storefront Changes

### 7.1 Remove custom login/register pages

The login/register flow moves entirely to Keycloak's hosted login page.
React code triggers the PKCE redirect; Keycloak handles the UI.

### 7.2 PKCE Auth Flow (using `oidc-client-ts`)

```typescript
// web-storefront/src/services/authService.ts
import { UserManager, WebStorageStateStore } from 'oidc-client-ts'

const KEYCLOAK_URL = import.meta.env.VITE_KEYCLOAK_URL ?? 'http://localhost:8180'
const REALM        = import.meta.env.VITE_KEYCLOAK_REALM ?? 'retailstore'

export const userManager = new UserManager({
  authority:             `${KEYCLOAK_URL}/realms/${REALM}`,
  client_id:             'web-storefront',
  redirect_uri:          `${window.location.origin}/callback`,
  post_logout_redirect_uri: `${window.location.origin}/`,
  response_type:         'code',
  scope:                 'openid profile email',
  userStore:             new WebStorageStateStore({ store: localStorage }),
  automaticSilentRenew:  true,
  silent_redirect_uri:   `${window.location.origin}/silent-renew.html`,
})

export const authService = {
  login:    ()        => userManager.signinRedirect(),
  logout:   ()        => userManager.signoutRedirect(),
  getUser:  ()        => userManager.getUser(),
  callback: ()        => userManager.signinRedirectCallback(),
  getToken: async ()  => {
    const user = await userManager.getUser()
    return user?.access_token ?? null
  },
}
```

### 7.3 Axios interceptor — attach token automatically

```typescript
// web-storefront/src/services/api.ts
api.interceptors.request.use(async config => {
  const token = await authService.getToken()
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  config.headers['X-Correlation-Id'] = Math.random().toString(36).slice(2, 11)
  return config
})
```

### 7.4 New routes in App.tsx

```typescript
// Add callback and silent-renew routes
<Route path="/callback"     element={<AuthCallback />} />
<Route path="/silent-renew" element={<SilentRenew />} />
```

---

## 8. Keycloak Setup Per Environment

### LOCAL (default profile) — Developer Machine

For local development (no `SPRING_PROFILES_ACTIVE`), run Keycloak as a standalone Docker container:

```bash
docker run -d --name keycloak \
  -p 8180:8180 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_HTTP_PORT=8180 \
  -e KC_HOSTNAME_STRICT=false \
  -v $(pwd)/retailstore-platform/keycloak/realms:/opt/keycloak/data/import \
  quay.io/keycloak/keycloak:25.0.6 start-dev --import-realm
```

This is used when running services directly in IntelliJ with the `default` profile.

### DEV (dev profile) — k3s on EC2

Keycloak runs as a **Bitnami Helm chart** (chart version 22.2.1, Keycloak 25) on the k3s cluster.
It uses MySQL `keycloakdb` (same MySQL instance as catalog and orders) and imports the realm JSON
from a k8s ConfigMap at startup.

**Helm infra values** (`helm/infra/keycloak-values.yaml`):
```yaml
# Bitnami Keycloak 22.2.1 on k3s
auth:
  adminUser: admin
  adminPassword: admin
production: false
proxy: edge
extraArgs: "--import-realm"
extraEnvVars:
  - name: KC_HTTP_PORT
    value: "8180"
  - name: KC_HOSTNAME_STRICT
    value: "false"
extraVolumeMounts:
  - name: realm-config
    mountPath: /opt/keycloak/data/import
extraVolumes:
  - name: realm-config
    configMap:
      name: keycloak-realm
```

**Realm import** — `install-infra.sh` creates the ConfigMap from the realm JSON file:
```bash
kubectl create configmap keycloak-realm \
  --from-file=retailstore-realm.json=keycloak/realms/retailstore-realm.json \
  -n retailstore
```

**To update the realm** after changes:
```bash
kubectl create configmap keycloak-realm \
  --from-file=retailstore-realm.json=keycloak/realms/retailstore-realm.json \
  -n retailstore --dry-run=client -o yaml | kubectl apply -f -
kubectl rollout restart statefulset/keycloak -n retailstore
```

### STAGE / PROD — EKS Bitnami

Same Bitnami chart, deployed to EKS with:
- External RDS MySQL (not in-cluster)
- A public-facing hostname for the Keycloak URL
- HTTPS termination via ALB
- 2 replicas + PodDisruptionBudget in prod

### Realm JSON (retailstore-platform/keycloak/realms/retailstore-realm.json)

The realm JSON defines:
- Realm name: `retailstore`
- Clients: `web-storefront` (public, PKCE), `experience-service` (confidential),
  `checkout-service` (confidential)
- Realm roles: `CUSTOMER`, `ADMIN`, `SUPPORT`, `service-role`
- Default role for new users: `CUSTOMER`
- Token settings: access token 5 min, refresh token 30 min, refresh token rotation enabled
- PKCE enforced for `web-storefront`

---

## 9. Kubernetes / Helm Changes

### Helm Values Structure (actual implementation)

```
retailstore-platform/helm/
├── dev/            ← dev env overrides (one file per service)
├── stage/          ← stage env overrides
├── prod/           ← prod env overrides
└── infra/          ← Bitnami chart values for MySQL, Redis, Keycloak
    └── keycloak-values.yaml   ← Bitnami Keycloak 22.2.1 (Keycloak 25)
```

Each service's Helm chart lives in `<service-dir>/chart/`. The chart's `deployment.yaml`
loads all env vars from a ConfigMap (`appEnv` values map) plus optional Secrets for client secrets.

### ENV vars per service (actual names used in Helm values files)

| Service | ENV vars in `helm/dev/*.yaml` |
|---|---|
| api-gateway | `KEYCLOAK_HOST`, `KEYCLOAK_PORT`, `SPRING_PROFILES_ACTIVE` |
| experience-service | `KEYCLOAK_HOST`, `KEYCLOAK_PORT`, `RETAIL_EXPERIENCE_KEYCLOAK_CLIENT_ID`, `RETAIL_EXPERIENCE_KEYCLOAK_TOKEN_URI` |
| checkout-service | `KEYCLOAK_HOST`, `KEYCLOAK_PORT`, `RETAIL_CHECKOUT_KEYCLOAK_CLIENT_ID` |
| catalog, cart, orders | `KEYCLOAK_HOST`, `KEYCLOAK_PORT` |
| web-storefront | `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM` |

**Client secrets** are in k8s Secrets (`optional: true` so dev works without creating them):
```yaml
# experience-service/chart/templates/deployment.yaml (excerpt)
- name: KEYCLOAK_CLIENT_SECRET
  valueFrom:
    secretKeyRef:
      name: experience-secrets
      key: client-secret
      optional: true    # ← dev starts without this Secret
```

In dev, client secret is injected directly via Helm values as a plain env var.

### ENV vars for stage / prod (in `helm/stage/*.yaml` and `helm/prod/*.yaml`)

| Service | ENV vars |
|---|---|
| api-gateway | `KEYCLOAK_JWKS_URI` (full URL, no HOST/PORT split) |
| experience-service | `KEYCLOAK_JWKS_URI`, `RETAIL_EXPERIENCE_KEYCLOAK_TOKEN_URI` |
| checkout-service | `KEYCLOAK_JWKS_URI`, `RETAIL_CHECKOUT_KEYCLOAK_TOKEN_URI` |
| catalog, cart, orders | `KEYCLOAK_JWKS_URI` |
| web-storefront | `VITE_KEYCLOAK_URL`, `VITE_KEYCLOAK_REALM` |

---

## 10. File Change Inventory

### Files to CREATE
```
api-gateway/.../config/SecurityConfig.java
api-gateway/.../filter/GlobalJwtFilter.java
experience-service/.../infrastructure/security/ServiceTokenProvider.java
order-service/.../api/rest/v1/controller/GlobalExceptionHandler.java
order-service/.../infrastructure/config/SqsConfig.java
catalog-service/.../infrastructure/config/SecurityConfig.java
cart-service/.../infrastructure/config/SecurityConfig.java
checkout-service/.../infrastructure/config/SecurityConfig.java    (update existing)
order-service/.../infrastructure/config/SecurityConfig.java
web-storefront/src/services/authService.ts
web-storefront/src/pages/AuthCallback.tsx
web-storefront/public/silent-renew.html
retailstore-platform/keycloak/realms/retailstore-realm.json
```

### Files to MODIFY
```
api-gateway/pom.xml                              + oauth2-resource-server dep
api-gateway/src/main/resources/application.yml  + keycloak config, + checkout route
experience-service/pom.xml                       + oauth2-resource-server dep
experience-service/.../client/CatalogClient.java + service token header
experience-service/.../client/CartClient.java    + service token header
experience-service/.../config/WebClientConfig.java
checkout-service/.../client/OrderServiceClient.java + service token header
catalog-service/pom.xml                          + oauth2-resource-server dep
cart-service/pom.xml                             + oauth2-resource-server dep
order-service/pom.xml                            + oauth2-resource-server dep
web-storefront/package.json                      + oidc-client-ts
web-storefront/src/services/api.ts               + token interceptor
web-storefront/src/App.tsx                       + callback routes
web-storefront/src/store/useAppStore.ts          + user state from Keycloak
retailstore-platform/docker-compose.yml          + keycloak service
retailstore-platform/helm/dev/gateway.yaml       + keycloak env vars
retailstore-platform/helm/dev/experience.yaml    + keycloak env vars
retailstore-platform/helm/dev/orders.yaml        + exception handler (no extra env)
```

---

## 11. Testing Strategy

### Step 1 — Start Keycloak

**Local (default profile) — standalone Docker:**
```bash
docker run -d --name keycloak \
  -p 8180:8180 \
  -e KEYCLOAK_ADMIN=admin -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_HTTP_PORT=8180 -e KC_HOSTNAME_STRICT=false \
  -v $(pwd)/retailstore-platform/keycloak/realms:/opt/keycloak/data/import \
  quay.io/keycloak/keycloak:25.0.6 start-dev --import-realm
```

**Dev profile — k3s on EC2 (already installed by `start-dev.sh`):**
```bash
export KUBECONFIG=$HOME/.kube/config-dev-k3s
./retailstore-platform/scripts/port-forward.sh start
# Keycloak is now reachable at localhost:8180 (port-forwarded from k3s)
```

**Verify realm imported:**
```bash
curl http://localhost:8180/realms/retailstore/.well-known/openid-configuration
# Should return JSON with authorization_endpoint, token_endpoint, jwks_uri
```

### Step 2 — Create a Test User

**Via Keycloak Admin UI:** `http://localhost:8180/admin` → admin / admin

**Via CLI (local Docker container):**
```bash
docker exec keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8180 --realm master --user admin --password admin

docker exec keycloak /opt/keycloak/bin/kcadm.sh create users -r retailstore \
  -s username=testuser -s email=test@example.com -s enabled=true \
  -s firstName=Test -s lastName=User

docker exec keycloak /opt/keycloak/bin/kcadm.sh set-password -r retailstore \
  --username testuser --new-password password123

docker exec keycloak /opt/keycloak/bin/kcadm.sh add-roles -r retailstore \
  --uusername testuser --rolename CUSTOMER
```

**Via CLI (k3s pod):**
```bash
kubectl exec -it statefulset/keycloak -n retailstore -- \
  /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8180 --realm master --user admin --password admin
# Then run the create/set-password/add-roles commands above inside the pod
```

### Step 3 — Get a Token (Direct Grant for testing)

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=web-storefront&username=testuser&password=password123" \
  | jq -r '.access_token')

echo $TOKEN | cut -d'.' -f2 | base64 -d | jq .   # decode claims
```

### Step 4 — Call api-gateway with Token

```bash
# Should succeed (200)
curl -H "Authorization: Bearer $TOKEN" \
     http://localhost:8080/api/v1/catalog/products | jq .

# Should fail (401)
curl http://localhost:8080/api/v1/catalog/products
# {"status":401,"error":"Unauthorized","message":"Missing Bearer token"}
```

### Step 5 — Test Service-to-Service Token

```bash
# Get a service token for experience-service
SVC_TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -d "grant_type=client_credentials&client_id=experience-service&client_secret=<secret>" \
  | jq -r '.access_token')

# Call catalog directly with service token (bypasses gateway for direct test)
curl -H "Authorization: Bearer $SVC_TOKEN" \
     http://localhost:8081/api/v1/catalog/products | jq .
```

### Step 6 — Test Token Expiry / Refresh

```bash
# Decode expiry
echo $TOKEN | cut -d'.' -f2 | base64 -d | jq '.exp | todate'

# After expiry, call should return 401
# Refresh:
REFRESH_TOKEN="<from initial token response>"
curl -X POST http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -d "grant_type=refresh_token&client_id=web-storefront&refresh_token=$REFRESH_TOKEN"
```

### Step 7 — Test Frontend PKCE Flow

```bash
cd web-storefront && npm run dev
# Navigate to http://localhost:3000
# Click Login → redirected to Keycloak
# Login with testuser/password123
# Redirected back to http://localhost:3000/callback
# App should show products (cart badge, etc.)
```

---

## 12. Quick Revision Summary

### The Two Layers (Never Merge)

| Layer | Tool | Does |
|---|---|---|
| Identity Provider (IdP) | **Keycloak** | Issues tokens, manages users, sessions, MFA |
| API Gateway | **Spring Cloud Gateway** | Validates tokens, routes, rate-limits |

### Token Types in This System

| Token | Issued by | Validated by | Flow |
|---|---|---|---|
| User access_token (JWT RS256) | Keycloak | api-gateway (GlobalJwtFilter) + each downstream service Spring Security | Authorization Code + PKCE |
| User refresh_token | Keycloak | Keycloak only | Silent renew |
| Service access_token (JWT RS256) | Keycloak | Each downstream service (catalog, cart, orders) via Spring Security + JWKS | Client Credentials |

### Where Each Concern Lives

| Concern | Location |
|---|---|
| Token issuance | Keycloak |
| User token validation (browser requests) | api-gateway `GlobalJwtFilter` — validates RS256 signature + expiry, then injects X-User-* headers |
| User identity propagation | api-gateway → X-User-* headers forwarded to all downstream services |
| Service token validation (S2S requests) | Each receiving service independently — Spring Security oauth2ResourceServer fetches JWKS and verifies RS256 signature |
| Fine-grained authorization (your data only) | Individual service controllers (read X-User-Id / X-User-Role headers) |
| Service-to-service token acquisition | `ServiceTokenProvider` in experience-service and checkout-service |
| Rate limiting | api-gateway |
| CORS | api-gateway |
| Business aggregation | experience-service (BFF) |

### Key OAuth2 Concepts

| Term | Meaning in This System |
|---|---|
| `Authorization Code + PKCE` | Login flow for browser/SPA — most secure, no client secret needed |
| `Client Credentials` | Service-to-service login — like a service account |
| `JWKS endpoint` | Keycloak's URL for public keys — gateway fetches and caches these |
| `Realm` | Keycloak's tenant — `retailstore` realm isolates our users/clients |
| `Client` | An application registered in Keycloak (web-storefront, experience-service, etc.) |
| `Scope` | What the token grants access to (`openid`, `profile`, `email`) |
| `RS256` | RSA asymmetric signing — private key signs, public key verifies (no shared secret) |
| `sub` | Subject claim — the userId, used as customerId throughout the platform |

### What Got Removed and Why

| Removed | Why |
|---|---|
| Shared JWT secret | RS256 asymmetric signing replaces symmetric HMAC — no secret to share |
| Custom register/login endpoints | Keycloak hosts these with security hardening, MFA, brute-force protection |
| `UserAccount` JPA entity (as auth entity) | Users live in Keycloak; services only need `sub` (userId) from the token |
