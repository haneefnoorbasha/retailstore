# api-gateway

**Role**: Single entry point for all clients. Built on Spring Cloud Gateway (reactive, non-blocking).

## Responsibilities

| Concern | Implementation |
|---------|---------------|
| **JWT Authentication** | `GlobalJwtFilter` (order -3) — validates RS256 JWT via Keycloak JWKS; rejects with 401 if missing or invalid |
| **Identity propagation** | `GlobalJwtFilter` — extracts claims and injects `X-User-Id`, `X-User-Email`, `X-User-Name`, `X-User-Role` headers for downstream services |
| Routing | `application.yml` route predicates — path-based forwarding |
| CORS | Global CORS config — allows web-storefront origin |
| Rate limiting | Redis-backed `RequestRateLimiter` filter per route |
| Circuit breaker | Resilience4j per route — prevents cascade failures |
| Request tracing | `CorrelationIdFilter` — attaches `X-Correlation-Id` to all requests |
| Logging | `RequestLoggingFilter` — method, path, status, duration per request |
| Security headers | `X-Content-Type-Options`, `X-Frame-Options` on all responses |

Authentication is the gateway's most critical responsibility. See [`documents/authentication.md`](documents/authentication.md) for the full design, environment-wise configuration, and troubleshooting guide.

## Route table

| Path prefix | Forwarded to | Rate limit |
|-------------|-------------|------------|
| `/api/v1/experience/**` | experience-service | 100 req/s |
| `/api/v1/catalog/**` | catalog-service | 200 req/s |
| `/api/v1/carts/**` | cart-service | — |
| `/api/v1/orders/**` | order-service | — |

## Adding a new service

```yaml
# In application.yml routes:
- id: my-new-service
  uri: ${RETAIL_GATEWAY_ROUTES_MYNEW:http://my-new-service}
  predicates:
    - Path=/api/v1/my-new/**
  filters:
    - name: CircuitBreaker
      args:
        name: mynew-cb
        fallbackUri: forward:/fallback/mynew
```
That's it — no code changes required.
