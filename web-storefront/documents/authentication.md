# web-storefront — Authentication Guide

> **Platform context first:** Read `documents/auth-design.md` at the workspace root to
> understand the full platform authentication design (Keycloak realm, JWT structure, how
> the gateway validates tokens, how services connect). This document covers only what the
> web-storefront does — the browser-side of the auth flow.

---

## 1. Responsibility

The web-storefront owns the **browser side of the OAuth2 Authorization Code + PKCE flow**.
It does not validate JWTs — that is the gateway's job. Its responsibilities are:

| Responsibility | Where |
|----------------|-------|
| Redirect user to Keycloak login page | `authService.login()` |
| Handle the redirect back (code → token exchange) | `AuthCallback.tsx` |
| Store tokens securely in `localStorage` | `oidc-client-ts` `WebStorageStateStore` |
| Attach `Authorization: Bearer <token>` to every API call | `api.ts` request interceptor |
| Silently renew tokens before they expire | `silent-renew.html` + `automaticSilentRenew: true` |
| Redirect to login on 401 response | `api.ts` response interceptor |
| Store user identity in app state | `useAppStore.ts` |
| Redirect to Keycloak on logout | `authService.logout()` |

---

## 2. PKCE Flow — How Login Works

PKCE (Proof Key for Code Exchange) is the secure login flow for browser apps. It prevents
authorization code interception attacks by binding the code to a one-time secret the browser
generates itself.

```
Browser (React SPA)               Keycloak (Identity Provider)        api-gateway
       │                                      │                              │
       │  1. User clicks "Login"              │                              │
       │                                      │                              │
       │  2. authService.login()              │                              │
       │     Generate random code_verifier    │                              │
       │     Compute code_challenge = S256(code_verifier)                    │
       │     Redirect browser to:             │                              │
       │       /realms/retailstore/protocol/openid-connect/auth              │
       │       ?client_id=web-storefront      │                              │
       │       &redirect_uri=.../callback     │                              │
       │       &response_type=code            │                              │
       │       &code_challenge=<hash>         │                              │
       │       &code_challenge_method=S256    │                              │
       │──────────────────────────────────►  │                              │
       │                                      │                              │
       │  3. Keycloak shows hosted login form │                              │
       │◄─────────────────────────────────── │                              │
       │  User enters email + password        │                              │
       │──────────────────────────────────►  │                              │
       │                                      │                              │
       │  4. Keycloak redirects back to app:  │                              │
       │     http://localhost:3000/callback?code=xyz&state=...               │
       │◄─────────────────────────────────── │                              │
       │                                      │                              │
       │  5. AuthCallback.tsx calls           │                              │
       │     authService.handleCallback()     │                              │
       │     POST /token                      │                              │
       │       grant_type=authorization_code  │                              │
       │       code=xyz                       │                              │
       │       code_verifier=<original>  ← proves we started the flow       │
       │──────────────────────────────────►  │                              │
       │                                      │                              │
       │  6. Keycloak returns tokens:         │                              │
       │     access_token  (JWT RS256, 5 min) │                              │
       │     refresh_token (30 min)           │                              │
       │     id_token                         │                              │
       │◄─────────────────────────────────── │                              │
       │  Stored in localStorage              │                              │
       │  User state set in useAppStore       │                              │
       │  Redirect to /                       │                              │
       │                                      │                              │
       │  7. React app makes API calls:       │                              │
       │     GET /api/v1/experience/homepage  │                              │
       │     Authorization: Bearer <token>   ─────────────────────────────► │
       │                                      │     GlobalJwtFilter validates│
       │                                      │     Injects X-User-* headers │
       │◄──────────────────────────────────────────────────────────────────  │
```

**Why PKCE and not a simpler flow?**  
Browser apps cannot keep a `client_secret` — anyone can view source. PKCE replaces the
secret with a one-time math challenge. Even if someone intercepts the authorization `code`,
they cannot exchange it without the `code_verifier` that only the original browser tab has.

---

## 3. Silent Token Renewal — How Sessions Stay Alive

Access tokens expire after 5 minutes. `oidc-client-ts` renews them silently using a hidden
`<iframe>` before they expire — no user interaction needed.

```
Browser main window              Hidden iframe              Keycloak
       │                              │                         │
       │  token expires in < 60s      │                         │
       │  automaticSilentRenew fires  │                         │
       │─────────────────────────────►│                         │
       │                              │  /auth?prompt=none      │
       │                              │  (no login prompt)      │
       │                              │────────────────────────►│
       │                              │  new code (session exists│
       │                              │◄──────────────────────── │
       │                              │  POST /token             │
       │                              │────────────────────────►│
       │                              │  new access_token        │
       │                              │◄──────────────────────── │
       │                              │  signinSilentCallback()  │
       │◄─────────────────────────────│  new token in localStorage
       │  all next API calls use new token
```

**Key file:** `public/silent-renew.html` — the iframe loads this page, which calls
`userManager.signinSilentCallback()` to complete the silent renewal.

If the session has expired in Keycloak (e.g. user was idle > 30 min), silent renewal fails
and the response interceptor in `api.ts` redirects the user to the login page on the next
401 response.

---

## 4. Implementation — File by File

### 4.1 `src/services/authService.ts`

The single auth façade. Wraps `oidc-client-ts` `UserManager`.

```typescript
const KEYCLOAK_BASE  = import.meta.env.VITE_KEYCLOAK_URL   ?? 'http://localhost:8180'
const KEYCLOAK_REALM = import.meta.env.VITE_KEYCLOAK_REALM ?? 'retailstore'
const APP_URL        = import.meta.env.VITE_APP_URL         ?? 'http://localhost:3000'

export const userManager = new UserManager({
  authority:              `${KEYCLOAK_BASE}/realms/${KEYCLOAK_REALM}`,
  client_id:              'web-storefront',          // public client — no client_secret
  redirect_uri:           `${APP_URL}/callback`,
  silent_redirect_uri:    `${APP_URL}/silent-renew.html`,
  post_logout_redirect_uri: APP_URL,
  response_type:          'code',                    // Authorization Code flow
  scope:                  'openid profile email roles',
  automaticSilentRenew:   true,                      // renew token before expiry
  userStore: new WebStorageStateStore({ store: window.localStorage }),
})

export const authService = {
  login:           () => userManager.signinRedirect(),
  logout:          () => userManager.signoutRedirect(),
  handleCallback:  () => userManager.signinRedirectCallback(),
  getUser:         () => userManager.getUser(),
  getAccessToken:  async () => (await userManager.getUser())?.access_token ?? null,
  isAuthenticated: async () => { const u = await userManager.getUser(); return !!u && !u.expired },
}
```

`oidc-client-ts` handles PKCE automatically — it generates `code_verifier`, computes
`code_challenge`, and sends them in the correct places. No manual crypto needed.

### 4.2 `src/services/api.ts`

The Axios instance. Two interceptors do all the auth wiring:

```typescript
// Request interceptor — attach token + correlation ID to every call
api.interceptors.request.use(async config => {
  config.headers['X-Correlation-Id'] = Math.random().toString(36).slice(2, 11)
  const token = await authService.getAccessToken()
  if (token) {
    config.headers['Authorization'] = `Bearer ${token}`
  }
  return config
})

// Response interceptor — redirect to login on 401
api.interceptors.response.use(
  res => res,
  err => {
    if (err.response?.status === 401) {
      authService.login()     // triggers PKCE redirect
    }
    return Promise.reject(err)
  }
)
```

`getAccessToken()` reads from `localStorage` via `oidc-client-ts`. If `automaticSilentRenew`
has already refreshed the token, it returns the new one transparently.

### 4.3 `src/pages/AuthCallback.tsx`

Mounted at `/callback`. Keycloak redirects here after login with the authorization `code`.

```typescript
export function AuthCallback() {
  const navigate  = useNavigate()
  const { setUser } = useAppStore()

  useEffect(() => {
    authService.handleCallback()          // exchanges code for tokens
      .then(user => {
        setUser({
          id:    user.profile.sub,        // Keycloak user UUID → used as customerId
          email: user.profile.email ?? '',
          name:  user.profile.name  ?? '',
          role:  user.profile.realm_access?.roles?.[0] ?? 'CUSTOMER',
        })
        navigate('/', { replace: true })  // replace so back button doesn't re-trigger
      })
      .catch(() => navigate('/', { replace: true }))
  }, [])

  return <LoadingSpinner />
}
```

### 4.4 `src/App.tsx` — Session Restore on Reload

On every page load, App.tsx restores the user from the stored token:

```typescript
useEffect(() => {
  authService.getUser().then(user => {
    if (user && !user.expired) {
      setUser({ id: user.profile.sub, email: ..., name: ..., role: ... })
    }
  })
}, [])
```

This means the user does not have to log in again after a browser refresh as long as the
stored token is still valid.

### 4.5 `src/store/useAppStore.ts`

Zustand store (persisted to `localStorage` under key `retailstore-app`).

| Field | Type | Set when | Used by |
|-------|------|----------|---------|
| `user` | `AuthUser \| null` | After login/session restore | Navbar, order pages |
| `customerId` | `string` | `user.id` after login; random `guest-xxx` before | Cart, orders API calls |
| `cartItemCount` | `number` | After fetching cart | Navbar badge |

`customerId` is the Keycloak `sub` claim (user UUID). It is used as the customer identifier
in all cart and order API calls. Before login, a guest ID is used so the cart works without
auth.

### 4.6 `public/silent-renew.html`

Static HTML file served at `/silent-renew.html`. The silent renewal iframe loads this page,
which calls `userManager.signinSilentCallback()` to complete the token refresh.

```html
<script>
  window.addEventListener('load', () => {
    import('/src/services/authService.ts').then(({ userManager }) => {
      userManager.signinSilentCallback()
    })
  })
</script>
```

---

## 5. Environment Configuration

### LOCAL (Vite dev server — `http://localhost:3000`)

No `.env` file needed — all defaults work:

| Variable | Default | Value used |
|----------|---------|------------|
| `VITE_KEYCLOAK_URL` | *(not set)* | `http://localhost:8180` |
| `VITE_KEYCLOAK_REALM` | *(not set)* | `retailstore` |
| `VITE_APP_URL` | *(not set)* | `http://localhost:3000` |
| `VITE_API_BASE_URL` | *(not set)* | `""` (relative — Vite proxy handles it) |

Keycloak must be running locally. See `api-gateway/documents/authentication.md` Section 6.1
for the Docker command.

**Vite proxy** (`vite.config.ts`) proxies `/api` → `http://localhost:8080` (the local gateway).

### DEV (served from k3s)

Create `.env.local`:
```bash
VITE_KEYCLOAK_URL=http://<EC2_PUBLIC_IP>:8180
VITE_KEYCLOAK_REALM=retailstore
VITE_APP_URL=http://localhost:3000
VITE_API_BASE_URL=http://localhost:8080
```

The gateway runs in k3s; access it via NodePort or `kubectl port-forward`.
Keycloak must be accessible from your browser (EC2 public IP, port 8180 open in security group,
or via `kubectl port-forward`).

### STAGE

```bash
VITE_KEYCLOAK_URL=https://auth.stage.retailstore.com
VITE_KEYCLOAK_REALM=retailstore
VITE_APP_URL=https://stage.retailstore.com
VITE_API_BASE_URL=https://api.stage.retailstore.com
```

Set as build-time environment variables in the CI pipeline (GitHub Actions secret → Vite
build arg). The built `dist/` is deployed to S3 + CloudFront.

### PROD

```bash
VITE_KEYCLOAK_URL=https://auth.retailstore.com
VITE_KEYCLOAK_REALM=retailstore
VITE_APP_URL=https://shop.retailstore.com
VITE_API_BASE_URL=https://api.retailstore.com
```

> **Note:** `VITE_*` variables are baked into the JavaScript bundle at build time — they are
> not runtime config. The correct values must be set before `npm run build`. There are no
> secrets here (Keycloak's public endpoints, no client secret needed for PKCE).

---

## 6. How to Test the Auth Flow

### Step 1 — Create a test user in Keycloak

```bash
# LOCAL: Keycloak running as Docker container
docker exec keycloak /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8180 --realm master --user admin --password admin

docker exec keycloak /opt/keycloak/bin/kcadm.sh create users -r retailstore \
  -s username=testuser -s email=test@example.com -s enabled=true
docker exec keycloak /opt/keycloak/bin/kcadm.sh set-password -r retailstore \
  --username testuser --new-password password123
docker exec keycloak /opt/keycloak/bin/kcadm.sh add-roles -r retailstore \
  --uusername testuser --rolename CUSTOMER

# DEV: Keycloak running in k3s (after port-forward.sh start)
kubectl exec -it statefulset/keycloak -n retailstore -- \
  /opt/keycloak/bin/kcadm.sh config credentials \
  --server http://localhost:8180 --realm master --user admin --password admin
# Then run the same create/set-password/add-roles commands inside the pod
```

### Step 2 — Start the dev server

```bash
npm install
npm run dev
# → http://localhost:3000
```

### Step 3 — Test the login flow

1. Open `http://localhost:3000` in a browser
2. Click **Login** (or any protected action)
3. You are redirected to `http://localhost:8180/realms/retailstore/...` (Keycloak login page)
4. Login with `testuser` / `password123`
5. You are redirected back to `http://localhost:3000/callback`
6. `AuthCallback.tsx` handles the code → token exchange
7. You land on the homepage, logged in

**Verify in browser DevTools:**
- `Application → Local Storage → retailstore-app` — Zustand store, check `user` field
- `Application → Local Storage → oidc.user:http://localhost:8180/realms/retailstore:web-storefront` — raw token
- `Network` tab → any API call should have `Authorization: Bearer eyJ...` header

### Step 4 — Verify silent renewal

```javascript
// Run in browser console
const storeKey = 'oidc.user:http://localhost:8180/realms/retailstore:web-storefront'
const user = JSON.parse(localStorage.getItem(storeKey))
console.log('Expires at:', new Date(user.expires_at * 1000))
// Wait until ~60 seconds before expiry — oidc-client-ts will silently renew
// Check Network tab for a call to /realms/retailstore/protocol/openid-connect/token
```

### Step 5 — Test logout

```javascript
// In browser console, or via logout button in the UI
import('/src/services/authService.ts').then(m => m.authService.logout())
// Redirects to Keycloak /logout → back to APP_URL (homepage, logged out)
```

### Step 6 — Test 401 handling

```bash
# Call the gateway without a token — should trigger automatic login redirect
fetch('/api/v1/catalog/products')
  .then(r => console.log(r.status))
# API interceptor catches 401 → authService.login() → Keycloak redirect
```

---

## 7. Troubleshooting

### "redirect_uri mismatch" error on Keycloak login page

Keycloak only allows redirect URIs registered in the realm. The `redirect_uri` built by
`oidc-client-ts` (`<APP_URL>/callback`) must match exactly.

```
Fix: In Keycloak Admin UI → Clients → web-storefront → Valid redirect URIs
Add: http://localhost:3000/callback  (for local/dev)
     https://stage.retailstore.com/callback  (for stage)
     https://shop.retailstore.com/callback  (for prod)
```

These are already set in the realm JSON (`retailstore-platform/keycloak/realms/retailstore-realm.json`).
If you are running on a different port, update the realm JSON and re-import.

### Stuck on `/callback` page (spinner never clears)

`handleCallback()` failed. Check the browser console.

| Console error | Cause | Fix |
|---------------|-------|-----|
| `No matching state found in storage` | User opened callback URL directly (no login flow started) | Navigate to `/` and click Login |
| `CORS error` on `/token` request | Keycloak not running or wrong URL | Check `VITE_KEYCLOAK_URL` |
| `invalid_grant` | Authorization code already used or expired | Re-login from scratch |

### Token is stored but API calls return 401

The gateway's JWKS URI is not pointing to the same Keycloak that issued the token.
Check that `KEYCLOAK_JWKS_URI` (or `KEYCLOAK_HOST/PORT`) on the gateway side matches
`VITE_KEYCLOAK_URL` on the frontend side.

### User state lost on browser refresh

This should not happen — `useAppStore` is persisted and `App.tsx` restores the user on
mount. If it does, check:
- `localStorage` is not disabled
- The stored token has not expired (`user.expired === true`)
- `VITE_APP_URL` matches the actual origin (mismatches break oidc-client-ts key lookup)
