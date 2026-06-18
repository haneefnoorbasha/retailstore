# identity-service

**Domain**: User identity — registration, login, JWT issuance, profile management.

**Data store**: PostgreSQL (H2 in development). BCrypt password hashing (strength 12).

## JWT token contents

```json
{ "sub": "userId", "email": "...", "username": "...", "fullName": "...", "role": "CUSTOMER" }
```

Downstream services (api-gateway) validate this token without calling identity-service on every request.

## API

| Method | Path | Description |
|--------|------|-------------|
| `POST` | `/api/v1/identity/register` | Register new account |
| `POST` | `/api/v1/identity/login` | Login → JWT |
| `GET` | `/api/v1/identity/profile/{userId}` | Get profile |
| `POST` | `/api/v1/identity/refresh/{userId}` | Refresh token |

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `RETAIL_IDENTITY_JWT_SECRET` | (base64 key) | JWT signing secret (min 32 bytes, base64-encoded) |
| `RETAIL_IDENTITY_JWT_EXPIRATION_MS` | `86400000` | Token lifetime (24h) |
| `RETAIL_IDENTITY_DB_ENDPOINT` | — | PostgreSQL host:port |
| `RETAIL_IDENTITY_DB_NAME` | `identitydb` | Database name |
| `RETAIL_IDENTITY_DB_USER` | `identity_user` | DB user |
| `RETAIL_IDENTITY_DB_PASSWORD` | — | DB password |
| `SPRING_PROFILES_ACTIVE` | — | Set to `postgres` for RDS |
