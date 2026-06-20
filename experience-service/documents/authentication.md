# experience-service — Authentication Guide

> **Platform context:** Read [`documents/auth-design.md`](../../documents/auth-design.md) first
> to understand the full authentication architecture. This document covers the implementation
> details specific to experience-service.

---

## 1. Responsibility

experience-service sits in the middle of two authentication boundaries:

| Direction | What it does |
|-----------|-------------|
| **Inbound** (from api-gateway) | Validates the user JWT forwarded by the gateway using Spring Security oauth2ResourceServer + JWKS |
| **Outbound** (to catalog, cart, orders) | Fetches a service token from Keycloak via Client Credentials and attaches it as `Authorization: Bearer` on every downstream call |

```
api-gateway
    │  Authorization: Bearer <user_token>    ← validated here
    │  X-User-Id, X-User-Role, X-User-Email  ← used in business logic
    ▼
experience-service
    │  Authorization: Bearer <service_token> ← obtained & attached here
    ├──────────────────────────────────────► catalog-service
    ├──────────────────────────────────────► cart-service
    └──────────────────────────────────────► order-service
```

experience-service is the only service in the platform that acts as **both** a resource server
(validates incoming tokens) and an OAuth2 client (fetches outgoing service tokens).

---

## 2. Components

| File | Auth role |
|------|----------|
| `infrastructure/config/SecurityConfig.java` | Configures Spring Security to validate incoming JWTs via JWKS |
| `infrastructure/security/ServiceTokenProvider.java` | Fetches and caches a Client Credentials token for outbound calls |
| `infrastructure/client/CatalogClient.java` | Calls catalog-service; attaches service token per request |
| `infrastructure/client/CartClient.java` | Calls cart-service; attaches service token per request |
| `infrastructure/config/WebClientConfig.java` | Wires up WebClient beans (catalogClient, cartClient, orderClient) with timeouts |
| `application/aggregator/HomepageAggregator.java` | Fires catalog + cart calls in **parallel** using `Mono.zip` |
| `api/rest/v1/controller/ExperienceController.java` | Uses `X-User-*` headers injected by the gateway for business logic |

---

## 3. Inbound Authentication — Validating User Tokens

### SecurityConfig

```java
// infrastructure/config/SecurityConfig.java
@Configuration
@EnableWebFluxSecurity
public class SecurityConfig {

    @Bean
    public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
        return http
            .csrf(ServerHttpSecurity.CsrfSpec::disable)
            .authorizeExchange(exchanges -> exchanges
                .pathMatchers("/actuator/**").permitAll()
                .anyExchange().authenticated()
            )
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
            .build();
    }
}
```

Spring Security's `oauth2ResourceServer` automatically:
1. Reads the `jwk-set-uri` from `application.yml`
2. Fetches Keycloak's public keys (lazily on first request, then cached)
3. Validates the RS256 signature on every incoming request
4. Validates token expiry
5. Rejects with `401` if invalid — before the request reaches the controller

experience-service is **WebFlux** (reactive), so it uses `ServerHttpSecurity` and
`@EnableWebFluxSecurity`, not the servlet equivalents.

### What the controller receives

The controller does not parse JWT claims. The api-gateway already validated the token and
injected the claims as HTTP headers. Controllers read those headers directly:

```java
// ExperienceController.java
@GetMapping("/homepage")
public Mono<ResponseEntity<HomepageResponse>> homepage(
        @RequestParam(defaultValue = "guest") String customerId,
        @RequestHeader(value = "X-Client-Channel", required = false, defaultValue = "WEB") String channel) {
    // customerId comes from the request param (set by the frontend from the JWT sub claim)
    // X-User-Id / X-User-Role headers are available but not needed at this layer —
    // experience-service aggregates data, it doesn't enforce per-user data access
}
```

The current controller uses `customerId` as a request parameter rather than reading it from
`X-User-Id`. This works because the frontend passes it explicitly. A future hardening step
would be to read `X-User-Id` from the header and ignore the query param for authenticated
calls.

---

## 4. Outbound Authentication — Service Token for Downstream Calls

### ServiceTokenProvider

```java
// infrastructure/security/ServiceTokenProvider.java
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
            return Mono.just(cachedToken);   // ← cache hit: no network call
        }
        return fetchToken();                 // ← cache miss: call Keycloak
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

**Token caching behaviour:**

```
Pod starts — cachedToken is null, tokenExpiry = Instant.EPOCH
      │
      ▼
First outbound call (e.g. catalogClient.getProducts)
      │
      │  getToken() called
      │  Cache miss → POST Keycloak /token (client_credentials)
      │  Keycloak returns: access_token, expires_in=300
      │  cachedToken = token, tokenExpiry = now + 300s
      │
      ▼
All calls within next ~270s (300 - 30s buffer)
      │
      │  getToken() called
      │  Instant.now() < tokenExpiry - 30s → cache hit
      │  Returns Mono.just(cachedToken) — no network
      │
      ▼
At ~270s mark (30s before actual expiry)
      │
      │  Instant.now() >= tokenExpiry - 30s → cache miss
      │  Fetches new token from Keycloak proactively
      │  Token updated in cache
```

The 30-second buffer prevents a race where a token is valid when fetched but expired by the
time the downstream service validates it (due to clock drift or network latency).

### How CatalogClient uses the token

```java
// infrastructure/client/CatalogClient.java
@Component
public class CatalogClient {

    @Qualifier("catalogClient")
    private final WebClient webClient;
    private final ServiceTokenProvider serviceTokenProvider;

    public Mono<Map> getProducts(int page, int size, String tags) {
        return serviceTokenProvider.getToken()                   // 1. get/refresh token
            .flatMap(token -> webClient.get()                   // 2. make the call with it
                .uri(u -> u.path("/api/v1/catalog/products")
                    .queryParam("page", page)
                    .queryParam("size", size)
                    .queryParamIfPresent("tags", Optional.ofNullable(tags))
                    .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class)
                .onErrorReturn(Map.of("products", List.of())));  // 3. empty fallback on error
    }

    public Mono<Map> getProduct(String id) { ... }
    public Mono<List> getTags() { ... }
}
```

`CartClient` follows the identical pattern for its `getCart` and `addItem` methods.

### WebClientConfig — how beans are wired

```java
// infrastructure/config/WebClientConfig.java
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)   // 3s connect timeout
            .responseTimeout(Duration.ofSeconds(5))               // 5s response timeout
            .doOnConnected(conn ->
                conn.addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS)));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .filter(ExchangeFilterFunction.ofRequestProcessor(req -> {
                log.debug("→ {} {}", req.method(), req.url());
                return Mono.just(req);
            }));
    }

    @Bean("catalogClient")
    public WebClient catalogClient(WebClient.Builder builder,
            @Value("${retail.experience.endpoints.catalog:http://catalog}") String url) {
        return builder.baseUrl(url).build();
    }

    @Bean("cartClient")
    public WebClient cartClient(WebClient.Builder builder,
            @Value("${retail.experience.endpoints.carts:http://carts}") String url) {
        return builder.baseUrl(url).build();
    }

    @Bean("orderClient")
    public WebClient orderClient(WebClient.Builder builder,
            @Value("${retail.experience.endpoints.orders:http://orders}") String url) {
        return builder.baseUrl(url).build();
    }
}
```

Each client bean has its own base URL. The `ServiceTokenProvider` uses a **separate** WebClient
instance built directly from `token-uri` — it is not one of these named beans.

---

## 5. Parallel Downstream Calls — HomepageAggregator

The homepage endpoint fires three downstream calls **simultaneously** using `Mono.zip`:

```java
// application/aggregator/HomepageAggregator.java
public Mono<HomepageResponse> aggregate(String customerId, int featuredCount) {
    Mono<Map>  productsMono = catalogClient.getProducts(0, featuredCount, null);
    Mono<List> tagsMono     = catalogClient.getTags();
    Mono<Map>  cartMono     = cartClient.getCart(customerId);

    // All three fire at the same time — total latency = slowest call, not sum of all
    return Mono.zip(productsMono, tagsMono, cartMono)
        .map(tuple -> buildHomepageResponse(tuple));
}
```

Each of those three calls internally calls `serviceTokenProvider.getToken()`. Because the
token is cached, all three calls get the same cached token — only one Keycloak fetch happens
on the first request.

**Timeline with caching:**
```
t=0ms  getToken() → cache miss → fetch from Keycloak (happens once before zip fires)

        Actually: Mono.zip fires all three simultaneously, each calls getToken()
        First one that executes fetchToken() wins the race.
        The other two also call fetchToken() but the volatile field means they
        see the result shortly after. Worst case: two fetches on the very first request.
        All subsequent requests: cache hit, no race.

t=0ms  → catalogClient.getProducts (service token attached)
t=0ms  → catalogClient.getTags     (service token attached)
t=0ms  → cartClient.getCart        (service token attached)
         (all three fire in parallel)

t=~30ms  All three responses arrive (typical)
t=~30ms  HomepageResponse assembled and returned
```

Without `Mono.zip`, these would be sequential: ~90ms total. With parallel: ~30ms.

---

## 6. Full Request Flow

```
Browser
  │  GET /api/v1/experience/homepage?customerId=cust-123
  │  Authorization: Bearer <user_token>
  │  X-Client-Channel: WEB
  ▼
api-gateway (GlobalJwtFilter)
  ├── Validates user JWT (JWKS)
  ├── Injects X-User-Id, X-User-Role, X-User-Email, X-User-Name
  └── Routes to experience-service
        │
        ▼
experience-service (Spring Security)
  ├── oauth2ResourceServer validates user JWT again (JWKS) ← independent check
  ├── Request reaches ExperienceController.homepage()
  │
  ├── HomepageAggregator.aggregate("cust-123", 8)
  │     │
  │     ├── ServiceTokenProvider.getToken()  → cache hit or Keycloak fetch
  │     │
  │     ├── [parallel] CatalogClient.getProducts(0, 8, null)
  │     │       GET catalog-service/api/v1/catalog/products
  │     │       Authorization: Bearer <service_token>
  │     │       catalog-service validates service token → 200 OK
  │     │
  │     ├── [parallel] CatalogClient.getTags()
  │     │       GET catalog-service/api/v1/catalog/tags
  │     │       Authorization: Bearer <service_token>
  │     │       catalog-service validates service token → 200 OK
  │     │
  │     └── [parallel] CartClient.getCart("cust-123")
  │             GET cart-service/api/v1/carts/cust-123
  │             Authorization: Bearer <service_token>
  │             cart-service validates service token → 200 OK
  │
  ├── Mono.zip merges all three results
  ├── Shapes response (strips description fields for MOBILE channel)
  └── ResponseEntity.ok(HomepageResponse)
        │
        ▼
Browser receives single aggregated response
```

---

## 7. Environment Configuration

### LOCAL (default profile)

No env vars needed. All defaults point to `localhost`.

```yaml
# application.yml defaults used:
retail:
  experience:
    keycloak:
      token-uri:     http://localhost:8180/realms/retailstore/protocol/openid-connect/token
      client-id:     experience-service
      client-secret: exp-service-secret
    endpoints:
      catalog: http://localhost:8081
      carts:   http://localhost:8082
      orders:  http://localhost:8084
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://localhost:8180/realms/retailstore/protocol/openid-connect/certs
```

**IntelliJ Run Configuration:**
- Main class: `ExperienceApplication`
- Active profiles: *(leave blank)*
- No env vars needed if all services are running on their default local ports

### DEV (k3s on EC2)

`application-dev.yml` uses the same `${VAR:localhost}` pattern. IntelliJ connects via
`port-forward.sh`; k3s pods get env vars injected by Helm.

```yaml
# application-dev.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}/realms/retailstore/protocol/openid-connect/certs

retail:
  experience:
    keycloak:
      token-uri: http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}/realms/retailstore/protocol/openid-connect/token
    endpoints:
      catalog: ${RETAIL_EXPERIENCE_ENDPOINTS_CATALOG:http://localhost:8081}
      carts:   ${RETAIL_EXPERIENCE_ENDPOINTS_CARTS:http://localhost:8082}
      orders:  ${RETAIL_EXPERIENCE_ENDPOINTS_ORDERS:http://localhost:8084}
```

| Where | `KEYCLOAK_HOST` | `KEYCLOAK_PORT` | `RETAIL_EXPERIENCE_ENDPOINTS_CATALOG` |
|-------|----------------|----------------|---------------------------------------|
| IntelliJ (via port-forward) | *(unset → localhost)* | *(unset → 8180)* | *(unset → localhost:8081)* |
| k3s pod | `keycloak` | `8180` | `http://catalog:8080` |

### STAGE / PROD

No defaults. All values must be injected via Helm / Kubernetes Secrets:

```yaml
# application-stage.yml / application-prod.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS_URI}          # no fallback

retail:
  experience:
    keycloak:
      token-uri:     ${KEYCLOAK_TOKEN_URI}            # no fallback
      client-id:     ${KEYCLOAK_CLIENT_ID:experience-service}
      client-secret: ${KEYCLOAK_CLIENT_SECRET}        # no fallback — from k8s Secret
    endpoints:
      catalog: ${RETAIL_EXPERIENCE_ENDPOINTS_CATALOG} # EKS internal DNS
      carts:   ${RETAIL_EXPERIENCE_ENDPOINTS_CARTS}
      orders:  ${RETAIL_EXPERIENCE_ENDPOINTS_ORDERS}
```

**Additional prod differences:**
- `springdoc.swagger-ui.enabled: false` (Swagger disabled)
- `management.endpoint.health.show-details: never`
- Tracing sampling: 10% (stage) / 5% (prod)

### Environment variable reference

| Variable | LOCAL default | Used for |
|----------|--------------|----------|
| `KEYCLOAK_JWKS_URI` | `http://localhost:8180/.../certs` | Inbound JWT validation (JWKS) |
| `KEYCLOAK_TOKEN_URI` | `http://localhost:8180/.../token` | Outbound token fetch |
| `KEYCLOAK_CLIENT_ID` | `experience-service` | Client Credentials client ID |
| `KEYCLOAK_CLIENT_SECRET` | `exp-service-secret` | Client Credentials secret |
| `RETAIL_EXPERIENCE_ENDPOINTS_CATALOG` | `http://localhost:8081` | catalog-service base URL |
| `RETAIL_EXPERIENCE_ENDPOINTS_CARTS` | `http://localhost:8082` | cart-service base URL |
| `RETAIL_EXPERIENCE_ENDPOINTS_ORDERS` | `http://localhost:8084` | order-service base URL |

---

## 8. Testing

### Step 1 — Start prerequisites

```bash
# Keycloak (if not already running)
docker start keycloak-local

# Start catalog-service, cart-service on their default ports (8081, 8082)
# Then start experience-service on port 8080 (or whichever port it's configured to)
```

### Step 2 — Get a user token

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -d "grant_type=password&client_id=web-storefront" \
  -d "username=customer@example.com&password=Customer@1234" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
```

### Step 3 — Call homepage

```bash
curl -s \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Client-Channel: WEB" \
  "http://localhost:8080/api/v1/experience/homepage?customerId=guest" \
  | python3 -m json.tool
```

Expected: `{ "featuredProducts": [...], "availableTags": [...], "cartItemCount": 0 }`

### Step 4 — Verify parallel calls in logs

In IntelliJ console, with `logging.level.com.retailstore=DEBUG`, you should see three
`→ GET` log lines appearing within milliseconds of each other (from WebClientConfig's
request logger), not sequentially.

### Step 5 — Test missing token

```bash
curl -s http://localhost:8080/api/v1/experience/homepage
# Expected: 401 from api-gateway (token never reaches experience-service)
# If calling experience-service directly (bypassing gateway):
# Expected: 401 from Spring Security oauth2ResourceServer
```

### Step 6 — Verify the service token Keycloak fetches

```bash
# Manually fetch the same token experience-service would fetch
SVC_TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=experience-service&client_secret=exp-service-secret" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# Inspect claims
echo $SVC_TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | python3 -m json.tool
# Expected: sub=experience-service UUID, realm_access.roles=[service-role]

# Call catalog-service directly with service token
curl -s \
  -H "Authorization: Bearer $SVC_TOKEN" \
  http://localhost:8081/api/v1/catalog/products \
  | python3 -m json.tool
```

---

## 9. Troubleshooting

### 401 on incoming requests — "Invalid or expired token"

The user JWT failed validation at experience-service's Spring Security layer.

1. Check JWKS URI is reachable: `curl http://localhost:8180/realms/retailstore/protocol/openid-connect/certs`
2. Check `KEYCLOAK_JWKS_URI` env var is set correctly (or not set, using the default)
3. Token may be expired — access tokens live 5 minutes. Get a fresh one.

### Downstream calls return 401 — catalog/cart/orders reject the service token

1. Verify Keycloak has `experience-service` client with `serviceAccountsEnabled: true`
2. Verify the `service-role` is assigned to the service account
3. Check `KEYCLOAK_CLIENT_SECRET` matches the secret in Keycloak
4. Check logs for `"Failed to fetch service token from Keycloak"` — this means `ServiceTokenProvider.fetchToken()` errored

### Cart returns empty — cartMono failed silently

`CartClient.getCart()` has `onErrorReturn(Map.of(...))`. If cart-service is down, experience-service
returns an empty cart rather than failing the whole homepage. Check cart-service health separately:
`curl http://localhost:8082/actuator/health`

### Homepage takes > 1s — parallel calls aren't actually parallel

If catalog-service or cart-service is slow, `Mono.zip` waits for the slowest one. Check
individual service response times. Also check the 5-second `responseTimeout` in WebClientConfig —
if any service takes longer, the call is cancelled and the fallback empty value is used.

### `application-dev.yml` comment says "Docker Compose" — is it stale?

Yes — it was not updated when the platform moved from Docker Compose to k3s. The config values
themselves are correct; only the comment is outdated. The `${VAR:localhost}` pattern works
for both IntelliJ-with-port-forward and k3s pods.
