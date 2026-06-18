# cart-service

**Domain**: Shopping cart — add, update, remove items per customer.

**Data store**: Amazon DynamoDB (partition key: `customerId`). In-cluster DynamoDB Local for development.

**Key behaviours**:
- Adding the same `productId` twice merges quantity (no duplicates)
- Setting quantity to 0 on update removes the item
- Cart is created on first add (no explicit create endpoint)
- Cart is deleted (not emptied) on `DELETE /carts/{customerId}`

## API

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/carts/{customerId}` | Get or create empty cart |
| `POST` | `/api/v1/carts/{customerId}/items` | Add item (merges if product exists) |
| `PUT` | `/api/v1/carts/{customerId}/items/{itemId}` | Update quantity |
| `DELETE` | `/api/v1/carts/{customerId}/items/{itemId}` | Remove item |
| `DELETE` | `/api/v1/carts/{customerId}` | Clear entire cart |

## Run locally

```bash
# Start DynamoDB Local
docker run -p 8000:8000 amazon/dynamodb-local

# Run service
RETAIL_CART_DYNAMODB_ENDPOINT=http://localhost:8000 \
AWS_ACCESS_KEY_ID=dummy AWS_SECRET_ACCESS_KEY=dummy AWS_REGION=us-east-1 \
./mvnw spring-boot:run
```
