# checkout-service

**Domain**: Checkout sessions — bridge between cart and order.

**Data store**: Redis (ElastiCache in production). Session TTL = 30 minutes.

## Business flow

```
1. POST /checkout/sessions        ← customer starts checkout with cart items
2. PUT  /sessions/{id}/shipping   ← customer enters shipping address
3. POST /sessions/{id}/submit     ← customer confirms → calls order-service → order created
```

## Pricing rules

- Subtotal = sum of all line totals
- Shipping = £5.99 (free if subtotal ≥ £50.00 — configurable)
- Tax = 20% on subtotal (VAT — configurable)
- Total = subtotal + shipping + tax

## API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/checkout/sessions` | Create session (calculates pricing) |
| `GET` | `/api/v1/checkout/sessions/{id}` | Get session |
| `PUT` | `/api/v1/checkout/sessions/{id}/shipping` | Set shipping address |
| `POST` | `/api/v1/checkout/sessions/{id}/submit` | Submit → order placed |
| `DELETE` | `/api/v1/checkout/sessions/{id}` | Abandon session |

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `REDIS_HOST` | `localhost` | Redis host |
| `REDIS_PORT` | `6379` | Redis port |
| `RETAIL_CHECKOUT_SESSION_TTL_MINUTES` | `30` | Session expiry |
| `RETAIL_CHECKOUT_TAX_RATE` | `0.20` | Tax rate (20%) |
| `RETAIL_CHECKOUT_SHIPPING_COST` | `5.99` | Flat shipping fee |
| `RETAIL_CHECKOUT_FREE_SHIPPING_THRESHOLD` | `50.00` | Free shipping above this |
| `RETAIL_CHECKOUT_ENDPOINTS_ORDERS` | `http://orders` | order-service URL |
