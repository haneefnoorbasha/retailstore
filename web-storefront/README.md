# web-storefront

React 18 + Vite + TypeScript + Tailwind CSS storefront.

**Architecture**: Calls `api-gateway` → `experience-service` for aggregated page data.
The `X-Client-Channel: WEB` header is sent on every request so the BFF knows to return the full rich response.

**Authentication**: OAuth2 Authorization Code + PKCE via Keycloak. `oidc-client-ts` handles
the redirect flow, token storage, and silent renewal. See [`documents/authentication.md`](documents/authentication.md).

## Run locally

```bash
npm install

# Dev server (proxies /api → localhost:8080 api-gateway)
npm run dev
# → http://localhost:3000

# Production build
npm run build
```

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `VITE_API_BASE_URL` | `` (relative) | Base URL for API calls |
| `VITE_CLIENT_CHANNEL` | `WEB` | Client channel sent in X-Client-Channel header |

## Pages

| Route | Page | Calls |
|-------|------|-------|
| `/` | Homepage | `/api/v1/experience/homepage` (aggregated) |
| `/catalog` | Shop | Same as homepage |
| `/cart` | Cart | `/api/v1/carts/{customerId}` |
| `/checkout` | Checkout | `/api/v1/orders` |
| `/order-confirmation/:id` | Confirmation | `/api/v1/orders/:id` |
| `/orders` | Order history | `/api/v1/orders/customer/:id` |
