# checkout-service — Authentication Guide

> **Platform context:** Read [`documents/auth-design.md`](../../documents/auth-design.md) first
> to understand the full authentication architecture. This document covers the implementation
> details specific to checkout-service.

---

## 1. Responsibility

checkout-service sits at two authentication boundaries:

| Direction | What it does |
|-----------|-------------|
| **Inbound** (from api-gateway) | Validates the user JWT forwarded by the gateway using Spring Security oauth2ResourceServer + JWKS |
| **Outbound** (to order-service) | Fetches a service token from Keycloak via Client Credentials, attaches it when calling order-service to place the order |

```
api-gateway
    │  Authorization: Bearer <user_token>  ← validated here
    │  X-User-Id, X-User-Role              ← available for business logic
    ▼
checkout-service
    │  Authorization: Bearer <service_token>  ← obtained & attached here
    └──────────────────────────────────────► order-service  (on submit only)
```

Unlike experience-service (which calls downstream on every request), checkout-service only
calls order-service at the **final submit step** — `POST /api/v1/checkout/sessions/{id}/submit`.
All other operations (create, get, update shipping, abandon) are self-contained, working
only with Redis for session storage.

---

## 2. Components

| File | Auth role |
|------|----------|
| `infrastructure/config/SecurityConfig.java` | Configures Spring Security to validate incoming JWTs via JWKS |
| `infrastructure/security/ServiceTokenProvider.java` | Fetches and caches a Client Credentials token for calling order-service |
| `infrastructure/client/OrderServiceClient.java` | Calls order-service with service token to place the order |
| `application/service/CheckoutService.java` | Orchestrates the checkout flow; triggers `OrderServiceClient.placeOrder()` on submit |
| `api/rest/v1/controller/CheckoutController.java` | Entry point; all endpoints require a valid JWT |

---

## 3. Inbound Authentication — Validating User Tokens

### SecurityConfig

```java
// infrastructure/config/SecurityConfig.java
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
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}))
            .build();
    }
}
```

checkout-service is **servlet-based** (Spring MVC, not WebFlux), so it uses `HttpSecurity`
and `@EnableWebSecurity`. The `oauth2ResourceServer` wires up a `NimbusJwtDecoder`
automatically from `spring.security.oauth2.resourceserver.jwt.jwk-set-uri`.

On every inbound request Spring Security:
1. Reads the `Authorization: Bearer <token>` header
2. Fetches JWKS from Keycloak (lazy, cached in JVM)
3. Verifies the RS256 signature and expiry
4. Returns `401` before the request reaches the controller if the token is invalid or missing

`/actuator/**` is the only public path — health and metrics endpoints are always accessible.

### What the controller sees

The `CheckoutController` does not read `X-User-*` headers directly. The `customerId` comes
from the **request body** (`CreateSessionRequest.customerId`), set by the frontend from the
user's JWT `sub` claim.

```java
// CheckoutController.java
@PostMapping
public ResponseEntity<CheckoutSessionResponse> createSession(
        @Valid @RequestBody CreateSessionRequest request) {
    // request.getCustomerId() = the customerId the frontend passed in the body
    // Spring Security has already validated the JWT before this method is called
    return ResponseEntity.status(HttpStatus.CREATED)
        .body(checkoutService.createSession(request));
}
```

A future hardening step would be to extract `customerId` from the `X-User-Id` header (injected
by the gateway) and verify it matches `request.getCustomerId()` — preventing a user from
creating a checkout session for a different customer's ID.

---

## 4. Outbound Authentication — Service Token for order-service

### ServiceTokenProvider

checkout-service is servlet-based (synchronous). `ServiceTokenProvider` returns a plain
`String` and uses a blocking `.block()` call internally.

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
            @Value("${retail.checkout.keycloak.token-uri}") String tokenUri,
            @Value("${retail.checkout.keycloak.client-id}") String clientId,
            @Value("${retail.checkout.keycloak.client-secret}") String clientSecret) {
        this.tokenClient = webClientBuilder.baseUrl(tokenUri).build();
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    public String getToken() {
        if (cachedToken != null && Instant.now().isBefore(tokenExpiry.minusSeconds(30))) {
            return cachedToken;    // ← cache hit: no network call
        }
        return fetchToken();       // ← cache miss: call Keycloak (blocks thread)
    }

    private String fetchToken() {
        Map<?, ?> response = tokenClient.post()
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(BodyInserters.fromFormData("grant_type", "client_credentials")
                .with("client_id", clientId)
                .with("client_secret", clientSecret))
            .retrieve()
            .bodyToMono(Map.class)
            .block();                // ← blocking call — acceptable in servlet context

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

> **Why `.block()` is used here:** experience-service is reactive (WebFlux) and uses
> `Mono<String>` to avoid blocking threads. checkout-service is servlet-based (Spring MVC)
> with a thread-per-request model. Blocking a servlet thread during a token fetch is
> acceptable — the thread is already blocked waiting for the response. Using `.block()` in
> a WebFlux context would be a bug; here it is intentional.

**Token caching behaviour:**

```
Pod starts — cachedToken=null, tokenExpiry=Instant.EPOCH
      │
      ▼
First POST /sessions/xxx/submit
      │
      │  getToken() → cache miss → blocking call to Keycloak /token
      │  Response: { access_token: "...", expires_in: 300 }
      │  cachedToken = token, tokenExpiry = now + 300s
      │
      ▼
Subsequent submits within ~270s (300 - 30s buffer)
      │
      │  getToken() → cache hit → returns cached token immediately
      │
      ▼
At ~270s mark
      │
      │  tokenExpiry - 30s reached → cache miss → fetch new token
```

### OrderServiceClient

```java
// infrastructure/client/OrderServiceClient.java
@Component
public class OrderServiceClient {

    private final WebClient.Builder webClientBuilder;
    private final ServiceTokenProvider serviceTokenProvider;

    @Value("${retail.checkout.endpoints.orders:http://orders}")
    private String ordersEndpoint;

    public String placeOrder(CheckoutSession session) {
        WebClient client = webClientBuilder.baseUrl(ordersEndpoint).build();
        String serviceToken = serviceTokenProvider.getToken();   // ← get/refresh token

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
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + serviceToken)  // ← attach token
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(orderRequest)
            .retrieve()
            .bodyToMono(Map.class)
            .block();                                              // ← blocking

        return response != null ? String.valueOf(response.get("id")) : null;
    }
}
```

---

## 5. Checkout Session Lifecycle

Understanding when the service token is needed:

```
POST   /sessions             → createSession()   — no S2S call, Redis only
GET    /sessions/{id}        → getSession()      — no S2S call, Redis only
PUT    /sessions/{id}/shipping → updateShipping() — no S2S call, Redis only
DELETE /sessions/{id}        → abandonSession()  — no S2S call, Redis only

POST   /sessions/{id}/submit → submitCheckout()  ← ONLY HERE is order-service called
                                                    service token fetched/used here
```

### submitCheckout flow

```
POST /api/v1/checkout/sessions/{sessionId}/submit
      │
      ▼
Spring Security
  └── Validates user JWT → passes

CheckoutController.submitCheckout(sessionId)
      │
      ▼
CheckoutService.submitCheckout(sessionId)
  ├── loadActiveSession(sessionId)
  │     ├── Redis lookup → session found
  │     ├── Status == ACTIVE? → yes
  │     └── session.isExpired()? → no
  │
  ├── session.isSubmittable()?
  │     ├── shippingDetails != null? → yes
  │     └── status == ACTIVE? → yes
  │
  ├── OrderServiceClient.placeOrder(session)
  │     ├── ServiceTokenProvider.getToken() → cached or Keycloak fetch
  │     ├── POST order-service/api/v1/orders
  │     │     Authorization: Bearer <service_token>
  │     │     Body: { customerId, lineItems, shippingAddress, totals }
  │     │
  │     │   order-service: validates service token (JWKS) → 201 Created
  │     │
  │     └── returns orderId ("ord-uuid-...")
  │
  ├── session.setStatus(SUBMITTED)
  ├── session.setSubmittedOrderId(orderId)
  ├── sessionRepository.save(session) → Redis updated
  │
  └── return CheckoutSessionResponse (with submittedOrderId, status=SUBMITTED)
```

**Important:** If `placeOrder()` throws (order-service is down, token invalid, etc.), the
`CheckoutService` does not catch it — the exception propagates up, the session remains in
`ACTIVE` status, and the client receives a `500`. The client can safely retry the submit
because the session is still `ACTIVE`. This is an intentional idempotency design: the
session is only marked `SUBMITTED` after a confirmed order ID is received.

---

## 6. Pricing Rules

checkout-service calculates all pricing independently — no calls to external services for
price data (prices come in the `CreateSessionRequest`):

| Rule | Config property | Default |
|------|----------------|---------|
| Tax rate | `retail.checkout.pricing.tax-rate` | `0.20` (20% VAT) |
| Shipping cost | `retail.checkout.pricing.shipping-cost` | `£5.99` |
| Free shipping threshold | `retail.checkout.pricing.free-shipping-threshold` | `£50.00` |
| Session TTL | `retail.checkout.session.ttl-minutes` | `30` minutes |

These are configurable per environment via Helm values.

---

## 7. Environment Configuration

### LOCAL (default profile)

No env vars needed. `application.yml` has defaults for everything:

```yaml
# application.yml defaults used locally:
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://localhost:8180/realms/retailstore/protocol/openid-connect/certs
  data:
    redis:
      host: localhost
      port: 6379

retail:
  checkout:
    keycloak:
      token-uri:     http://localhost:8180/realms/retailstore/protocol/openid-connect/token
      client-id:     checkout-service
      client-secret: checkout-service-secret
    endpoints:
      orders: http://orders   # ← NOTE: no localhost default here, see below
```

> **Redis required locally:** checkout-service uses Redis for session storage. Start Redis
> before running checkout-service locally:
> ```bash
> docker run -d --name redis-local -p 6379:6379 redis:7-alpine
> ```

> **`http://orders` default:** The `orders` endpoint has no localhost default in
> `application.yml` — it points to `http://orders` (the k3s DNS name). When running
> locally, set `RETAIL_CHECKOUT_ENDPOINTS_ORDERS=http://localhost:8085` as an env var in
> IntelliJ to point to your locally running order-service.

### DEV (k3s on EC2)

```yaml
# application-dev.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}/realms/retailstore/protocol/openid-connect/certs
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}

retail:
  checkout:
    keycloak:
      token-uri: http://${KEYCLOAK_HOST:localhost}:${KEYCLOAK_PORT:8180}/realms/retailstore/protocol/openid-connect/token
```

| Where | KEYCLOAK_HOST | REDIS_HOST | RETAIL_CHECKOUT_ENDPOINTS_ORDERS |
|-------|--------------|------------|----------------------------------|
| IntelliJ (port-forward) | *(unset → localhost)* | *(unset → localhost)* | `http://localhost:8085` |
| k3s pod | `keycloak` | `redis-master` | `http://orders` |

### STAGE / PROD

All values injected via Helm / Kubernetes Secrets. No fallback defaults:

```yaml
# application-stage.yml / application-prod.yml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS_URI}

  data:
    redis:
      host: ${REDIS_HOST}           # ElastiCache primary endpoint
      ssl:
        enabled: true               # ElastiCache requires SSL in stage/prod

retail:
  checkout:
    keycloak:
      token-uri:     ${KEYCLOAK_TOKEN_URI}
      client-id:     ${KEYCLOAK_CLIENT_ID:checkout-service}
      client-secret: ${KEYCLOAK_CLIENT_SECRET}    # from k8s Secret
    endpoints:
      orders: ${RETAIL_CHECKOUT_ENDPOINTS_ORDERS}
```

**Additional prod differences:**
- `springdoc.swagger-ui.enabled: false`
- `management.endpoint.health.show-details: never`
- Redis connection pool: larger (min-idle: 5, max-idle: 20, max-active: 50 in prod vs 2/10/20 in stage)
- Redis SSL: enabled in both stage and prod, not in local/dev

### Environment variable reference

| Variable | LOCAL default | Used for |
|----------|--------------|----------|
| `KEYCLOAK_JWKS_URI` | `http://localhost:8180/.../certs` | Inbound JWT validation |
| `KEYCLOAK_TOKEN_URI` | `http://localhost:8180/.../token` | Outbound token fetch |
| `KEYCLOAK_CLIENT_ID` | `checkout-service` | Client Credentials client ID |
| `KEYCLOAK_CLIENT_SECRET` | `checkout-service-secret` | Client Credentials secret |
| `RETAIL_CHECKOUT_ENDPOINTS_ORDERS` | `http://orders` (no localhost fallback) | order-service URL |
| `REDIS_HOST` | `localhost` | Session storage |
| `REDIS_PORT` | `6379` | Redis port |

---

## 8. Testing

### Step 1 — Start prerequisites

```bash
# Keycloak
docker start keycloak-local

# Redis
docker run -d --name redis-local -p 6379:6379 redis:7-alpine

# order-service must be running for submit to work
# Start checkout-service in IntelliJ with:
#   RETAIL_CHECKOUT_ENDPOINTS_ORDERS=http://localhost:8085
```

### Step 2 — Get a user token

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -d "grant_type=password&client_id=web-storefront" \
  -d "username=customer@example.com&password=Customer@1234" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")
```

### Step 3 — Create a checkout session

```bash
SESSION=$(curl -s -X POST http://localhost:8080/api/v1/checkout/sessions \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "customerId": "test-customer-123",
    "lineItems": [
      {
        "productId": "prod-1",
        "productName": "Blue Sneakers",
        "quantity": 2,
        "unitPrice": "49.99"
      }
    ]
  }')

echo $SESSION | python3 -m json.tool
# Expected: sessionId, status=ACTIVE, priceSummary with subtotal/shipping/tax/total
SESSION_ID=$(echo $SESSION | python3 -c "import sys,json; print(json.load(sys.stdin)['sessionId'])")
```

### Step 4 — Add shipping address

```bash
curl -s -X PUT "http://localhost:8080/api/v1/checkout/sessions/$SESSION_ID/shipping" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "fullName": "Test Customer",
    "addressLine1": "123 Test Street",
    "city": "London",
    "state": "England",
    "postalCode": "SW1A 1AA",
    "country": "GB"
  }' | python3 -m json.tool
```

### Step 5 — Submit checkout (triggers S2S call to order-service)

```bash
curl -s -X POST "http://localhost:8080/api/v1/checkout/sessions/$SESSION_ID/submit" \
  -H "Authorization: Bearer $TOKEN" \
  | python3 -m json.tool
# Expected: status=SUBMITTED, submittedOrderId="ord-..."
```

In the IntelliJ console you should see a log line:
```
INFO  CheckoutService  - Checkout submitted: sessionId=... orderId=ord-... customerId=...
```

### Step 6 — Test missing token (no auth)

```bash
curl -s -X POST http://localhost:8080/api/v1/checkout/sessions \
  -H "Content-Type: application/json" \
  -d '{ "customerId": "x", "lineItems": [] }'
# Expected: 401 (from Spring Security before reaching controller)
```

### Step 7 — Verify service token independently

```bash
# Manually fetch the same token checkout-service uses for order-service calls
SVC_TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -d "grant_type=client_credentials" \
  -d "client_id=checkout-service&client_secret=checkout-service-secret" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# Inspect claims — should show service-role
echo $SVC_TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | python3 -m json.tool

# Call order-service directly with this service token
curl -s -X POST http://localhost:8085/api/v1/orders \
  -H "Authorization: Bearer $SVC_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "customerId": "test", "checkoutSessionId": "sess-1", "lineItems": [], ... }'
```

---

## 9. Troubleshooting

### 401 on all requests — Spring Security rejecting token

1. Check JWKS endpoint is reachable:
   `curl http://localhost:8180/realms/retailstore/protocol/openid-connect/certs`
2. Check `KEYCLOAK_JWKS_URI` is pointing to the right URL
3. Token may be expired — access tokens live 5 minutes

### Submit returns 500 — order-service call failing

1. Is order-service running? `curl http://localhost:8085/actuator/health`
2. Check `RETAIL_CHECKOUT_ENDPOINTS_ORDERS` is set to `http://localhost:8085` locally
3. Check logs for `IllegalStateException: Keycloak returned no access_token` — means
   the service token fetch failed. Verify `KEYCLOAK_CLIENT_SECRET=checkout-service-secret`

### Session not found (404) after creating it

Redis is not running or not reachable. Start Redis:
```bash
docker start redis-local
# or
docker run -d --name redis-local -p 6379:6379 redis:7-alpine
```

### Submit succeeds but status stays ACTIVE on next GET

The order-service returned a response but without a valid `id` field. Check order-service
logs. The session is saved as SUBMITTED only after `response.get("id")` is non-null.

### "Keycloak returned no access_token" on submit

The `checkout-service` client in Keycloak is misconfigured. Verify:
- `serviceAccountsEnabled: true` on the client
- `service-role` is assigned to the service account (Clients → checkout-service → Service accounts roles)
- Client secret matches `KEYCLOAK_CLIENT_SECRET`

### Redis SSL error in stage/prod

Stage and prod configs have `spring.data.redis.ssl.enabled: true`. If you see SSL
handshake errors, the ElastiCache endpoint must be the TLS endpoint (port 6380 in some
configs). Verify `REDIS_HOST` and `REDIS_PORT` point to the correct ElastiCache endpoint.
