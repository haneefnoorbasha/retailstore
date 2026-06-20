# Keycloak Local Setup Guide

> **Your machine:** macOS 26.3.1, Apple Silicon (arm64)
>
> This guide covers everything needed to run Keycloak on your laptop, configure the
> `retailstore` realm, and wire up the api-gateway to validate tokens against it.
>
> **Local environment change:** Keycloak now runs as a k3s pod on Rancher Desktop — not as
> a standalone Docker container. The setup is handled by `install-infra-local.sh` which
> deploys the Bitnami Keycloak Helm chart with realm auto-import (same as dev EC2).
>
> See [`documents/local-k3s-setup.md`](../../documents/local-k3s-setup.md) for the full
> Rancher Desktop setup guide. Come back here for Keycloak Admin UI configuration details
> and api-gateway wiring.
>
> Two paths for Keycloak configuration are offered:
> - **Option A — Auto-import (already done by install-infra-local.sh):** Realm, clients,
>   users, and roles are imported automatically from the existing JSON file. No UI steps needed.
> - **Option B — Manual setup via Admin UI:** Walk through every screen in Keycloak's UI.
>   Takes longer but teaches you what each setting does.

---

## Prerequisites

### 1. Docker Desktop

Keycloak runs in Docker. If you don't have Docker Desktop installed:

1. Download from [docker.com/products/docker-desktop](https://www.docker.com/products/docker-desktop/)
   — choose the **Apple Silicon** (arm64) installer.
2. Install and start it.
3. Verify:

```bash
docker --version
# Docker version 27.x.x, build ...
```

### 2. Java 21 + IntelliJ

The api-gateway itself is a Spring Boot app. You need Java 21 on your PATH. Verify:

```bash
java -version
# openjdk version "21.x.x" ...
```

### 3. The project realm JSON

The realm JSON is already in the repo at:
```
retailstore-platform/keycloak/realms/retailstore-realm.json
```

It defines the realm, all three clients, roles, and two test users. You'll use this in
Option A. In Option B, you create everything manually and the JSON is a reference.

---

## Option A — Auto-import (Recommended)

Run this single command from the **root of the repo** (`retailstore/`):

```bash
docker run -d \
  --name keycloak-local \
  -p 8180:8180 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_HTTP_PORT=8180 \
  -e KC_HOSTNAME_STRICT=false \
  -e KC_HOSTNAME_STRICT_HTTPS=false \
  -v "$(pwd)/retailstore-platform/keycloak/realms:/opt/keycloak/data/import" \
  quay.io/keycloak/keycloak:25.0.6 \
  start-dev --import-realm
```

Wait about 20–30 seconds for Keycloak to start, then verify:

```bash
curl -s http://localhost:8180/realms/retailstore/.well-known/openid-configuration \
  | python3 -m json.tool | head -20
```

You should see a JSON response starting with `"issuer": "http://localhost:8180/realms/retailstore"`.

That's it — skip ahead to [Verify Everything Works](#verify-everything-works).

---

## Option B — Manual Setup via Admin UI

### Step 1 — Start Keycloak (no realm import)

```bash
docker run -d \
  --name keycloak-local \
  -p 8180:8180 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_HTTP_PORT=8180 \
  -e KC_HOSTNAME_STRICT=false \
  -e KC_HOSTNAME_STRICT_HTTPS=false \
  quay.io/keycloak/keycloak:25.0.6 \
  start-dev
```

Open the Admin UI: **http://localhost:8180/admin**
Login: `admin` / `admin`

---

### Step 2 — Create the Realm

1. Click the dropdown in the top-left that says **"Keycloak"** (the master realm name).
2. Click **"Create realm"**.
3. Fill in:
   - **Realm name:** `retailstore`
   - **Display name:** `RetailStore`
   - **Enabled:** ON
4. Click **Create**.

You are now inside the `retailstore` realm. All steps below happen inside this realm.

---

### Step 3 — Configure Realm Settings

Go to **Realm Settings** (left sidebar).

**General tab:**
- Display name: `RetailStore`
- HTML display name: `<b>RetailStore</b>`
- User registration: ON
- Login with email: ON
- Duplicate emails: OFF

**Tokens tab:**
| Setting | Value |
|---------|-------|
| Access Token Lifespan | 5 minutes |
| SSO Session Idle | 30 minutes |
| SSO Session Max | 10 hours |
| Refresh Token Max Reuse | 0 |
| Revoke Refresh Token | ON |

Click **Save**.

---

### Step 4 — Create Realm Roles

Go to **Realm roles** (left sidebar) → **Create role**.

Create each of these four roles one by one:

| Role name | Description |
|-----------|-------------|
| `CUSTOMER` | Standard customer account |
| `ADMIN` | Platform administrator |
| `SUPPORT` | Support team member |
| `service-role` | Internal service account — M2S calls only |

After creating all four, set the **default role**:

1. Go to **Realm roles** → click `CUSTOMER`.
2. Toggle **"Default role"** to ON.

This means every new registered user automatically gets the `CUSTOMER` role.

---

### Step 5 — Create Client: web-storefront

Go to **Clients** → **Create client**.

**General Settings:**
| Field | Value |
|-------|-------|
| Client type | OpenID Connect |
| Client ID | `web-storefront` |
| Name | `RetailStore Web Frontend` |
| Description | `React SPA — Authorization Code + PKCE` |

**Capability config:**
| Setting | Value |
|---------|-------|
| Client authentication | OFF ← makes it a public client (no secret) |
| Authorization | OFF |
| Standard flow | ON |
| Direct access grants | ON ← needed for curl testing |
| Implicit flow | OFF |
| Service accounts roles | OFF |

**Login settings:**
| Field | Value |
|-------|-------|
| Valid redirect URIs | `http://localhost:3000/*` |
| Valid post logout redirect URIs | `http://localhost:3000/` |
| Web origins | `http://localhost:3000` |

Click **Save**.

**Enable PKCE:**
1. Stay on the `web-storefront` client page.
2. Go to the **Advanced** tab.
3. Find **"Proof Key for Code Exchange Code Challenge Method"**.
4. Set it to `S256`.
5. Click **Save**.

**Add client scopes:**
1. Go to the **Client scopes** tab on the `web-storefront` client.
2. Click **Add client scope**.
3. Add `roles` to the **Default** scopes (so roles appear in the access token).

---

### Step 6 — Create Client: experience-service

Go to **Clients** → **Create client**.

**General Settings:**
| Field | Value |
|-------|-------|
| Client type | OpenID Connect |
| Client ID | `experience-service` |
| Name | `Experience Service` |
| Description | `BFF — Client Credentials for downstream M2S calls` |

**Capability config:**
| Setting | Value |
|---------|-------|
| Client authentication | ON ← confidential client, has a secret |
| Authorization | OFF |
| Standard flow | OFF |
| Direct access grants | OFF |
| Service accounts roles | ON ← enables Client Credentials grant |

Click **Next** → **Next** → **Save** (no redirect URIs needed for service clients).

**Set the client secret:**
1. Go to the **Credentials** tab.
2. Change **Client Authenticator** to `Client Id and Secret` if not already set.
3. In the **Client secret** field, click **"Regenerate"** and then manually type
   `exp-service-secret` (or leave the generated one and update `application.yml` to match).

> **Tip:** For local dev it's easiest to use the value that's already in `application.yml`
> (`exp-service-secret`) so you don't have to change any config.

**Assign service-role to the service account:**
1. Go to the **Service accounts roles** tab.
2. Click **Assign role**.
3. Filter by **Realm roles**.
4. Select `service-role`.
5. Click **Assign**.

---

### Step 7 — Create Client: checkout-service

Repeat Step 6 with these values:

| Field | Value |
|-------|-------|
| Client ID | `checkout-service` |
| Name | `Checkout Service` |
| Description | `Checkout — Client Credentials to call order-service` |
| Client secret | `checkout-service-secret` |

Assign `service-role` to its service account (same as Step 6).

---

### Step 8 — Create Test Users

Go to **Users** → **Create new user**.

**User 1 — Admin:**
| Field | Value |
|-------|-------|
| Username | `admin@retailstore.com` |
| Email | `admin@retailstore.com` |
| First name | `Platform` |
| Last name | `Admin` |
| Email verified | ON |
| Enabled | ON |

After saving:
1. Go to the **Credentials** tab → **Set password**.
2. Password: `Admin@1234`, Temporary: OFF.
3. Go to the **Role mapping** tab → **Assign role** → select `ADMIN`.

**User 2 — Test customer:**
| Field | Value |
|-------|-------|
| Username | `customer@example.com` |
| Email | `customer@example.com` |
| First name | `Test` |
| Last name | `Customer` |
| Email verified | ON |
| Enabled | ON |

After saving:
1. Set password: `Customer@1234`, Temporary: OFF.
2. Role mapping: `CUSTOMER` is already assigned by default (from Step 4).

---

## Verify Everything Works

### 1. Check the discovery endpoint

```bash
curl -s http://localhost:8180/realms/retailstore/.well-known/openid-configuration \
  | python3 -m json.tool | grep -E '"issuer"|"token_endpoint"|"jwks_uri"'
```

Expected output:
```json
"issuer": "http://localhost:8180/realms/retailstore",
"token_endpoint": "http://localhost:8180/realms/retailstore/protocol/openid-connect/token",
"jwks_uri": "http://localhost:8180/realms/retailstore/protocol/openid-connect/certs",
```

### 2. Get a user token (Direct Grant — for curl testing only)

```bash
USER_TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password" \
  -d "client_id=web-storefront" \
  -d "username=customer@example.com" \
  -d "password=Customer@1234" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

echo $USER_TOKEN
```

Decode and inspect the token claims:
```bash
echo $USER_TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | python3 -m json.tool
```

You should see:
```json
{
  "sub": "...",
  "email": "customer@example.com",
  "realm_access": {
    "roles": ["CUSTOMER", "default-roles-retailstore"]
  },
  ...
}
```

### 3. Get a service token (Client Credentials)

```bash
SVC_TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=client_credentials" \
  -d "client_id=experience-service" \
  -d "client_secret=exp-service-secret" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

echo $SVC_TOKEN | cut -d'.' -f2 | base64 -d 2>/dev/null | python3 -m json.tool
```

You should see:
```json
{
  "sub": "...",
  "azp": "experience-service",
  "realm_access": {
    "roles": ["service-role"]
  },
  ...
}
```

### 4. Fetch the JWKS (public keys)

```bash
curl -s http://localhost:8180/realms/retailstore/protocol/openid-connect/certs \
  | python3 -m json.tool
```

You should get a JSON object with a `keys` array containing at least one RS256 public key.
This is the endpoint the api-gateway calls to validate token signatures.

---

## api-gateway Configuration Explained

### What connects to Keycloak

The api-gateway needs only one Keycloak setting — the **JWKS URI** (the URL where Keycloak
publishes its RS256 public keys). It uses this to verify every token's signature.

```
api-gateway/src/main/resources/application.yml
```

```yaml
spring:
  security:
    oauth2:
      resourceserver:
        jwt:
          jwk-set-uri: ${KEYCLOAK_JWKS_URI:http://localhost:8180/realms/retailstore/protocol/openid-connect/certs}
```

The `${KEYCLOAK_JWKS_URI:http://localhost:8180/...}` pattern means:
- **If** the env var `KEYCLOAK_JWKS_URI` is set → use it
- **Otherwise** → fall back to `http://localhost:8180/...` (your laptop)

So when running locally in IntelliJ with no env var set, it automatically points to your
locally running Keycloak on port 8180. No additional config needed.

### Why there is no `issuer-uri`

You might expect to also see:
```yaml
issuer-uri: http://localhost:8180/realms/retailstore
```

It is intentionally absent. If `issuer-uri` were set, Spring Security would also validate
that the `iss` claim in every token matches this URI exactly. That would break in the dev
environment where:
- IntelliJ connects to Keycloak via `localhost:8180`
- But k3s pods reach Keycloak via the cluster DNS name `keycloak:8180`
- Keycloak issues tokens with `iss = http://keycloak:8180/realms/retailstore`

Using only `jwk-set-uri` means the gateway validates the **RS256 signature and token expiry**
but skips the issuer check. This is a deliberate trade-off documented in the code.

### How SecurityConfig works

```
api-gateway/src/main/java/com/retailstore/gateway/config/SecurityConfig.java
```

```java
@Bean
public SecurityWebFilterChain securityWebFilterChain(ServerHttpSecurity http) {
    return http
        .csrf(CsrfSpec::disable)
        .httpBasic(HttpBasicSpec::disable)
        .formLogin(FormLoginSpec::disable)
        .authorizeExchange(exchanges -> exchanges.anyExchange().permitAll())
        .oauth2ResourceServer(OAuth2ResourceServerSpec::disable)  // ← disabled
        .build();
}

@Bean
public ReactiveJwtDecoder reactiveJwtDecoder() {
    return NimbusReactiveJwtDecoder.withJwkSetUri(jwksUri).build();  // ← manual decoder
}
```

Spring Security's built-in JWT resource server is **disabled**. Instead, a `ReactiveJwtDecoder`
bean is created manually and injected into `GlobalJwtFilter`. This gives the gateway full
control over the 401 response format (structured JSON instead of Spring's default error page).

### How GlobalJwtFilter works

```
api-gateway/src/main/java/com/retailstore/gateway/filter/GlobalJwtFilter.java
```

```
Every incoming request
       │
       ├── Path matches /actuator/** or /fallback/**?
       │   └── YES → skip filter, pass through (public path)
       │
       ├── Authorization header present and starts with "Bearer "?
       │   └── NO  → return 401 { "status": 401, "error": "Unauthorized",
       │                           "message": "Missing or invalid Authorization header" }
       │
       ├── jwtDecoder.decode(token)
       │   ├── Fetches JWKS from Keycloak (cached in JVM)
       │   ├── Verifies RS256 signature
       │   ├── Verifies expiry (exp claim)
       │   └── FAIL → return 401 { "message": "Token validation failed: ..." }
       │
       └── SUCCESS → mutate request, add headers, forward downstream:
           X-User-Id    = jwt.sub
           X-User-Email = jwt.email
           X-User-Name  = jwt.name
           X-User-Role  = first matching role from [ADMIN, CUSTOMER, SUPPORT, service-role]
```

Filter order is `-3` — it runs before correlation ID (`-2`) and logging (`-1`) filters,
so all downstream filters and routes already have the X-User-* headers available.

### Environment variable reference (local / IntelliJ)

When running locally (default Spring profile, no `SPRING_PROFILES_ACTIVE`), no env vars
are needed — all defaults point to `localhost:8180`.

| Env var | Default (local) | What it controls |
|---------|-----------------|------------------|
| `KEYCLOAK_JWKS_URI` | `http://localhost:8180/realms/retailstore/protocol/openid-connect/certs` | Where the gateway fetches public keys |
| `REDIS_HOST` | `localhost` | Rate limiting store (start Redis locally or disable rate limiting in dev) |
| `REDIS_PORT` | `6379` | Redis port |
| `RETAIL_GATEWAY_ROUTES_CATALOG` | `http://localhost:8081` | Where catalog-service is running |
| `RETAIL_GATEWAY_ROUTES_EXPERIENCE` | `http://localhost:8083` | Where experience-service is running |
| `RETAIL_GATEWAY_ROUTES_CARTS` | `http://localhost:8082` | Where cart-service is running |
| `RETAIL_GATEWAY_ROUTES_CHECKOUT` | `http://localhost:8084` | Where checkout-service is running |
| `RETAIL_GATEWAY_ROUTES_ORDERS` | `http://localhost:8085` | Where order-service is running |

### IntelliJ Run Configuration

No special configuration needed for Keycloak connectivity. Just run the api-gateway
`ApiGatewayApplication` main class with:

- **Active profiles:** *(leave empty for local)* or `default`
- **Environment variables:** only needed if you want to override defaults

The gateway will connect to `http://localhost:8180` automatically.

---

## Test the Gateway with a Token

Start the api-gateway in IntelliJ, then:

```bash
# 1. Get a token
TOKEN=$(curl -s -X POST \
  http://localhost:8180/realms/retailstore/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d "grant_type=password&client_id=web-storefront&username=customer@example.com&password=Customer@1234" \
  | python3 -c "import sys,json; print(json.load(sys.stdin)['access_token'])")

# 2. Call the gateway — should reach catalog-service (or return 503 if catalog isn't running)
curl -v -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/catalog/products

# 3. Call without a token — should return 401 immediately from GlobalJwtFilter
curl -v http://localhost:8080/api/v1/catalog/products
# Expected: {"status":401,"error":"Unauthorized","message":"Missing or invalid Authorization header",...}

# 4. Call with a bad token — should return 401
curl -v -H "Authorization: Bearer bad.token.here" http://localhost:8080/api/v1/catalog/products
# Expected: {"status":401,"error":"Unauthorized","message":"Token validation failed: ..."}

# 5. Public path — should always pass (no token needed)
curl -v http://localhost:8080/actuator/health
# Expected: {"status":"UP"}
```

---

## Managing the Container

```bash
# Stop Keycloak
docker stop keycloak-local

# Start again (data is preserved in the container)
docker start keycloak-local

# View logs
docker logs -f keycloak-local

# Remove completely (all data lost — next start is fresh)
docker rm -f keycloak-local

# Restart fresh with auto-import
docker rm -f keycloak-local && docker run -d \
  --name keycloak-local \
  -p 8180:8180 \
  -e KEYCLOAK_ADMIN=admin \
  -e KEYCLOAK_ADMIN_PASSWORD=admin \
  -e KC_HTTP_PORT=8180 \
  -e KC_HOSTNAME_STRICT=false \
  -e KC_HOSTNAME_STRICT_HTTPS=false \
  -v "$(pwd)/retailstore-platform/keycloak/realms:/opt/keycloak/data/import" \
  quay.io/keycloak/keycloak:25.0.6 \
  start-dev --import-realm
```

> **Note:** `start-dev` mode stores data inside the container (H2 embedded database). If you
> `docker rm` the container, all manual changes made via the Admin UI are lost. Option A
> (auto-import) re-creates everything from the JSON file, so this is fine. If you made manual
> changes you want to keep, export the realm first (Realm Settings → Action → Export).

---

## Troubleshooting

### Port 8180 already in use

```bash
lsof -i :8180
# Find the PID and kill it, or change the Keycloak port:
docker run ... -p 9180:8180 ...
# Then update KEYCLOAK_JWKS_URI env var to use port 9180
```

### `curl` returns connection refused on port 8180

Keycloak takes 20–30 seconds to start. Check if it's ready:
```bash
docker logs keycloak-local | tail -5
# Look for: "Keycloak 25.0.6 on /0.0.0.0:8180 ready."
```

### Realm not found (`404 Realm does not exist`)

The auto-import didn't work. Check:
```bash
docker logs keycloak-local | grep -i "import\|realm\|error"
```

Common cause: the `-v` volume path is wrong. Make sure you run the `docker run` command from
the **root of the repo** where `retailstore-platform/` exists.

### Token returns `{"error":"unauthorized_client"}`

The client is not set up correctly. Most common causes:
- `web-storefront` client has **Client authentication ON** (it must be OFF — it's a public client)
- Direct access grants is OFF (must be ON for password grant testing)

### api-gateway returns 401 even with a valid token

1. Check the JWKS endpoint is reachable from the gateway:
   ```bash
   curl http://localhost:8180/realms/retailstore/protocol/openid-connect/certs
   ```
2. Check the gateway logs in IntelliJ for `JWT validation failed`.
3. Make sure the token isn't expired — access tokens live only 5 minutes.

### Token claims missing roles

The `roles` scope is not assigned to the client. In the Admin UI:
- Go to `web-storefront` client → **Client scopes** tab
- Make sure `roles` is listed under **Default** (not Optional)

---

## Quick Reference

### Test users

| Username | Password | Role |
|----------|----------|------|
| `customer@example.com` | `Customer@1234` | CUSTOMER |
| `admin@retailstore.com` | `Admin@1234` | ADMIN |

### Key URLs

| URL | What it is |
|-----|-----------|
| `http://localhost:8180/admin` | Keycloak Admin UI |
| `http://localhost:8180/realms/retailstore/.well-known/openid-configuration` | OIDC discovery endpoint |
| `http://localhost:8180/realms/retailstore/protocol/openid-connect/token` | Token endpoint |
| `http://localhost:8180/realms/retailstore/protocol/openid-connect/certs` | JWKS endpoint (public keys) |
| `http://localhost:8080/actuator/health` | api-gateway health check |

### Client secrets (local dev only)

| Client | Secret |
|--------|--------|
| `experience-service` | `exp-service-secret` |
| `checkout-service` | `checkout-service-secret` |
| `web-storefront` | *(none — public client)* |
