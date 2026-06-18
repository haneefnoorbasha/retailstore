# experience-service

**Role**: BFF (Backend-for-Frontend) / Experience layer.

Aggregates data from multiple downstream microservices in parallel and shapes the response per client channel. The frontend makes **one call** and gets all the data it needs for a page.

## Key design decisions

- **No database** — stateless. Just orchestrates downstream calls.
- **Parallel aggregation** via Reactor `Mono.zip()` — all downstream calls fire simultaneously.
- **Channel-aware shaping** — reads `X-Client-Channel: WEB|MOBILE|TABLET` and trims fields accordingly.
- **Resilient** — downstream failures return degraded (empty) data, not 500s.

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/experience/homepage` | Products + tags + cart badge in one call |
| `GET` | `/api/v1/experience/products/{id}` | Product detail (channel-shaped) |
| `GET` | `/api/v1/experience/cart/{customerId}/summary` | Cart + estimated totals |

## Adding a new page

1. Create a new aggregator in `application/aggregator/`
2. Add parallel `Mono.zip()` calls to the services you need
3. Add one endpoint in `ExperienceController`
4. Done — no changes to any downstream service

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `RETAIL_EXPERIENCE_ENDPOINTS_CATALOG` | `http://catalog` | catalog-service URL |
| `RETAIL_EXPERIENCE_ENDPOINTS_CARTS` | `http://carts` | cart-service URL |
| `RETAIL_EXPERIENCE_ENDPOINTS_ORDERS` | `http://orders` | order-service URL |
