# catalog-service

**Domain**: Product catalog — browse, search, and filter products.

**Architecture**: Hexagonal (Ports & Adapters). The domain is isolated from infrastructure via `port/in` (use case interfaces) and `port/out` (repository interfaces).

## Package structure

```
com.retailstore.catalog/
├── domain/              ← Pure business entities and exceptions (no Spring)
│   ├── model/
│   ├── repository/      ← JPA interfaces (Spring Data)
│   └── exception/
├── application/         ← Use case orchestration
│   ├── port/in/         ← Inbound use case interfaces
│   ├── port/out/        ← Outbound repository interfaces
│   └── service/         ← Implementations of use cases
├── infrastructure/      ← Technical adapters (JPA, cache, config)
│   ├── persistence/     ← Adapter: ProductRepositoryPort → JPA
│   └── config/          ← DataSeeder, OpenAPI, Cache
└── api/rest/v1/         ← HTTP layer (controllers, DTOs, mappers)
    ├── controller/
    ├── dto/
    └── mapper/          ← MapStruct (domain → DTO, zero boilerplate)
```

## API endpoints

| Method | Path | Description |
|--------|------|-------------|
| `GET` | `/api/v1/catalog/products` | Paginated product list (tag filter, sort) |
| `GET` | `/api/v1/catalog/products/{id}` | Single product |
| `GET` | `/api/v1/catalog/products/count` | Product count |
| `GET` | `/api/v1/catalog/tags` | All product tags |

Swagger UI: `http://localhost:8080/swagger-ui.html`

## Run locally

```bash
./mvnw spring-boot:run
# uses H2 in-memory by default — no external DB needed

# With MySQL (RDS):
SPRING_PROFILES_ACTIVE=mysql \
RETAIL_CATALOG_PERSISTENCE_ENDPOINT=localhost:3306 \
RETAIL_CATALOG_PERSISTENCE_PASSWORD=secret \
./mvnw spring-boot:run
```

## Environment variables

| Variable | Default | Description |
|----------|---------|-------------|
| `RETAIL_CATALOG_PERSISTENCE_PROVIDER` | `in-memory` | `in-memory` or `mysql` |
| `RETAIL_CATALOG_PERSISTENCE_ENDPOINT` | — | `host:port` of MySQL |
| `RETAIL_CATALOG_PERSISTENCE_DB_NAME` | `catalogdb` | Database name |
| `RETAIL_CATALOG_PERSISTENCE_USER` | `catalog_user` | DB user |
| `RETAIL_CATALOG_PERSISTENCE_PASSWORD` | — | DB password |
| `SPRING_PROFILES_ACTIVE` | — | Set to `mysql` for RDS |
