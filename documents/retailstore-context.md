# RetailStore Platform — Complete Project Context for Claude Code

> **Instructions for Claude Code**: This file contains the complete context of the RetailStore
> e-commerce microservices platform. Read it fully before making any changes. All architecture
> decisions are locked. Follow existing patterns exactly when adding features.

---

## ARCHITECTURE OVERVIEW

### What this is
A production-grade e-commerce platform built as 9 repositories on Amazon EKS.
Industry patterns: Walmart, Airbnb, Zalando. Java 21, Spring Boot 3.3, Spring Cloud Gateway.

### The 9 Repositories

| Repo | Role | Stack | Data Store |
|------|------|-------|------------|
| web-storefront | React SPA → deployed to CDN, NOT a Spring Boot app | React 18, Vite, Tailwind, TanStack Query, Zustand | None |
| api-gateway | Single entry point. Auth, rate-limit, routing, CORS | Spring Cloud Gateway, Resilience4j, Redis | Redis (rate-limit) |
| experience-service | BFF. Parallel aggregation, channel shaping. NO database. | Spring Boot, WebFlux, WebClient | None (stateless) |
| catalog-service | Products, tags, search. Hexagonal arch. | Spring Boot, JPA, MapStruct, Cache | H2 (dev) / RDS MySQL (prod) |
| cart-service | Shopping cart. Merge-on-duplicate logic. | Spring Boot, DynamoDB Enhanced Client | DynamoDB |
| checkout-service | Checkout session. Pricing: subtotal+shipping+tax. Calls order-service on submit. | Spring Boot, Redis | ElastiCache Redis |
| order-service | Order lifecycle. 7 statuses. SQS events. | Spring Boot, JPA, SQS | H2 (dev) / RDS PostgreSQL (prod) |
| identity-service | Register, login, JWT (jjwt), BCrypt, profiles | Spring Boot, Spring Security | H2 (dev) / RDS PostgreSQL (prod) |
| retailstore-platform | Docker Compose, Helm overrides, scripts | Helm 3, Docker | — |

### Request Flow (LOCKED — do not change)
```
Browser / Mobile
    ↓ HTTPS
CloudFront (CDN — static assets, DDoS)
    ↓
ALB Ingress (TLS termination — one rule: /* → api-gateway pod)
    ↓
api-gateway (Spring Cloud Gateway — JWT, rate-limit, routing)
    ↓ routes /api/v1/experience/** 
experience-service (BFF — Mono.zip parallel calls, X-Client-Channel shaping)
    ↓ parallel WebClient
catalog  ·  cart  ·  checkout  ·  orders  ·  identity
```

### Gateway Decision (LOCKED)
- AWS API Gateway: NOT USED. Wrong for EKS — it's for serverless/Lambda.
- ALB Ingress: ONE rule only. TLS termination. Forwards /* to api-gateway pod. No app logic.
- Spring Cloud Gateway: App-level. JWT, rate-limit, CORS, circuit breaker, routing.
- Three layers STACK, they do not compete.

### BFF / Experience Layer Decision (LOCKED)
- ONE experience-service, not one BFF per client.
- Reads X-Client-Channel: WEB|MOBILE|TABLET header.
- Fires parallel Mono.zip() calls to downstream services.
- Shapes response in code (mobile gets lean payload, web gets full).
- REST today, GraphQL-ready (same aggregators become resolvers — no rewrite).
- No shared database. Stateless.

### Package Structure (ALL Java services follow this — Hexagonal Architecture)
```
com.retailstore.{service}/
├── domain/
│   ├── model/        ← Pure Java entities. ZERO Spring annotations.
│   ├── exception/    ← Business exceptions
│   └── repository/   ← JPA interfaces (catalog, order only)
├── application/
│   ├── port/in/      ← Use case interfaces (inbound)
│   ├── port/out/     ← Repository interfaces (outbound)
│   └── service/      ← Implements use cases, calls ports
├── infrastructure/
│   ├── persistence/  ← Adapter: implements port/out using JPA
│   ├── dynamodb/     ← Adapter: DynamoDB (cart only)
│   ├── redis/        ← Adapter: Redis (checkout only)
│   ├── messaging/    ← SQS publisher (order only)
│   ├── client/       ← WebClient wrappers (experience, checkout)
│   └── config/       ← Spring beans: DataSeeder, OpenAPI, Security
└── api/rest/v1/
    ├── controller/   ← @RestController — thin, delegates to port/in
    ├── dto/          ← Request/Response objects
    ├── mapper/       ← MapStruct (catalog only)
    └── shaper/       ← ClientChannel enum (experience only)
```

### Naming Conventions (LOCKED)
- API paths: /api/v1/{domain}/ — always versioned
- K8s service names: catalog, carts, checkout, orders, identity, experience, gateway
- Docker container names match K8s service names
- Java packages: com.retailstore.{service}.{layer}.{sublayer}
- Env vars: RETAIL_{SERVICE}_{CATEGORY}_{KEY}

### Extensibility Rules
- New service = new repo. Register one route in api-gateway application.yml. Zero other changes.
- New page endpoint = new aggregator in experience-service application/aggregator/. Zero changes to domain services.
- New client type = new case in ClientChannel enum + new shaping branch. Zero changes to downstream.

---

## COMPLETED USE CASES

### catalog-service
- GET /api/v1/catalog/products — paginated, filter by tags, sort (name_asc|name_desc|price_asc|price_desc)
- GET /api/v1/catalog/products/{id} — single product with tags
- GET /api/v1/catalog/products/count — total count, filterable by tags
- GET /api/v1/catalog/tags — all tags
- DataSeeder: 10 products seeded on startup (electronics, clothing, books, furniture, fitness)
- Caching: @Cacheable on getById and getAllTags

### cart-service
- GET /api/v1/carts/{customerId} — get or auto-create empty cart
- POST /api/v1/carts/{customerId}/items — add item (MERGES qty if same productId exists)
- PUT /api/v1/carts/{customerId}/items/{itemId} — update quantity (qty=0 removes item)
- DELETE /api/v1/carts/{customerId}/items/{itemId} — remove specific item
- DELETE /api/v1/carts/{customerId} — clear entire cart (DynamoDB delete)
- Business rule: same product added twice → quantity merged, not duplicated

### checkout-service
- POST /api/v1/checkout/sessions — create session from cart items, calculates pricing
- GET /api/v1/checkout/sessions/{id} — get session (throws 404 if expired)
- PUT /api/v1/checkout/sessions/{id}/shipping — set shipping address
- POST /api/v1/checkout/sessions/{id}/submit — CRITICAL: validates, calls order-service, marks SUBMITTED
- DELETE /api/v1/checkout/sessions/{id} — abandon session
- Pricing rules: subtotal=sum(qty×price), shipping=5.99 (FREE if subtotal≥50), tax=20% on subtotal
- Session TTL: 30 minutes in Redis
- States: ACTIVE → SUBMITTED | EXPIRED | ABANDONED

### order-service
- POST /api/v1/orders — place order (called by checkout-service, not browser)
- GET /api/v1/orders/{orderId} — get order
- GET /api/v1/orders/customer/{customerId} — order history, paginated, newest first
- POST /api/v1/orders/{orderId}/cancel — cancel (only if PENDING or CONFIRMED)
- PATCH /api/v1/orders/{orderId}/status — update status (internal/admin)
- Order statuses: PENDING → CONFIRMED → PROCESSING → SHIPPED → DELIVERED | CANCELLED | REFUNDED
- SQS events: ORDER_PLACED, ORDER_CANCELLED (non-fatal if SQS unavailable)

### identity-service
- POST /api/v1/identity/register — register account, returns JWT
- POST /api/v1/identity/login — login with email+password, returns JWT
- GET /api/v1/identity/profile/{userId} — get profile
- POST /api/v1/identity/refresh/{userId} — refresh JWT
- JWT contains: sub(userId), email, username, fullName, role
- BCrypt strength 12. Token expiry 24h default.

### experience-service (BFF)
- GET /api/v1/experience/homepage?customerId={id}&featuredCount={n} — parallel: catalog products + tags + cart badge count
- GET /api/v1/experience/products/{id} — product detail (mobile: lean fields only)
- GET /api/v1/experience/cart/{customerId}/summary — cart + estimated shipping + total
- Channel shaping: X-Client-Channel: MOBILE strips description and heavy fields

### api-gateway routes
- /api/v1/experience/** → experience-service (rate-limit: 100/s per IP)
- /api/v1/catalog/** → catalog-service (rate-limit: 200/s per IP)
- /api/v1/carts/** → cart-service (circuit breaker only)
- /api/v1/orders/** → order-service (circuit breaker only)
- /api/v1/identity/** → identity-service (NO rate-limit — login/register paths)
- Global CORS: configured for web-storefront origin
- Filters: CorrelationIdFilter (X-Correlation-Id), RequestLoggingFilter (method/path/status/duration)

### web-storefront pages
- / and /catalog → HomePage: aggregated via experience/homepage, tag filter chips, add-to-cart
- /cart → CartPage: qty update, remove, subtotal, proceed to checkout
- /checkout → CheckoutPage: shipping form, calls checkout-service → order-service flow
- /order-confirmation/:id → OrderConfirmationPage: order details after placement
- /orders → OrdersPage: customer order history with status badges

---

## WHAT IS NOT YET BUILT (open tasks for Claude Code)

1. Helm charts — only catalog-service has a complete chart/. All others have empty chart/templates/ directories.
   - Need: cart, checkout, order, identity, experience, gateway — same pattern as catalog-service/chart/
   - Pattern: Chart.yaml, values.yaml, templates/_helpers.tpl, deployment.yaml, service.yaml, configmap.yaml, serviceaccount.yaml, hpa.yaml

2. Terraform infrastructure — retailstore-platform/terraform/ is empty.
   - Need: network layer (VPC, subnets, IGW, NAT) and cluster layer (EKS, node groups, add-ons)
   - Pattern: two separate Terraform projects with remote state in S3
   - EKS add-ons: Pod Identity Agent, AWS Load Balancer Controller (Helm), EBS CSI Driver, Secrets Store CSI + ASCP

3. Unit tests — src/test/ directories exist but are empty.
   - Need: CatalogServiceTest, CartServiceTest, CheckoutServiceTest, OrderServiceTest
   - Use Mockito, no Spring context (@ExtendWith(MockitoExtension.class))

4. api-gateway JWT validation filter — GatewayApplication exists but no JWT filter yet.
   - Need: GlobalJwtFilter that validates Bearer token using same secret as identity-service
   - Skip: /api/v1/identity/register, /api/v1/identity/login, /actuator/**

5. order-service GlobalExceptionHandler and SqsConfig bean — missing from order-service.

6. CI/CD GitHub Actions — no .github/workflows/ yet.
   - Need: per-service workflow: test → build → push ECR → update helm values

7. ArgoCD application manifests — retailstore-platform/argocd/ directory not created yet.

8. checkout-service route in api-gateway application.yml — /api/v1/checkout/** not yet added.

---

## ENVIRONMENT VARIABLES REFERENCE

### catalog-service
RETAIL_CATALOG_PERSISTENCE_PROVIDER=in-memory|mysql
RETAIL_CATALOG_PERSISTENCE_ENDPOINT=host:port
RETAIL_CATALOG_PERSISTENCE_DB_NAME=catalogdb
RETAIL_CATALOG_PERSISTENCE_USER=catalog_user
RETAIL_CATALOG_PERSISTENCE_PASSWORD=secret
SPRING_PROFILES_ACTIVE=mysql  (for RDS)

### cart-service
RETAIL_CART_DYNAMODB_TABLE_NAME=Carts
RETAIL_CART_DYNAMODB_ENDPOINT=  (blank=real AWS, http://dynamodb-local:8000 for local)
AWS_REGION=us-east-1

### checkout-service
REDIS_HOST=localhost
REDIS_PORT=6379
RETAIL_CHECKOUT_ENDPOINTS_ORDERS=http://orders
RETAIL_CHECKOUT_SESSION_TTL_MINUTES=30
RETAIL_CHECKOUT_TAX_RATE=0.20
RETAIL_CHECKOUT_SHIPPING_COST=5.99
RETAIL_CHECKOUT_FREE_SHIPPING_THRESHOLD=50.00

### order-service
RETAIL_ORDER_DB_ENDPOINT=host:port
RETAIL_ORDER_DB_NAME=ordersdb
RETAIL_ORDER_DB_USER=orders_user
RETAIL_ORDER_DB_PASSWORD=secret
RETAIL_ORDER_MESSAGING_ENABLED=false|true
RETAIL_ORDER_MESSAGING_SQS_QUEUE_URL=https://sqs...
SPRING_PROFILES_ACTIVE=postgres  (for RDS)

### identity-service
RETAIL_IDENTITY_JWT_SECRET=base64-encoded-32-byte-minimum
RETAIL_IDENTITY_JWT_EXPIRATION_MS=86400000
RETAIL_IDENTITY_DB_ENDPOINT=host:port
RETAIL_IDENTITY_DB_NAME=identitydb
RETAIL_IDENTITY_DB_USER=identity_user
RETAIL_IDENTITY_DB_PASSWORD=secret
SPRING_PROFILES_ACTIVE=postgres  (for RDS)

### experience-service
RETAIL_EXPERIENCE_ENDPOINTS_CATALOG=http://catalog
RETAIL_EXPERIENCE_ENDPOINTS_CARTS=http://carts
RETAIL_EXPERIENCE_ENDPOINTS_ORDERS=http://orders

### api-gateway
RETAIL_GATEWAY_ROUTES_EXPERIENCE=http://experience
RETAIL_GATEWAY_ROUTES_CATALOG=http://catalog
RETAIL_GATEWAY_ROUTES_CARTS=http://carts
RETAIL_GATEWAY_ROUTES_ORDERS=http://orders
RETAIL_GATEWAY_ROUTES_IDENTITY=http://identity
REDIS_HOST=localhost
REDIS_PORT=6379
ALLOWED_ORIGIN=https://shop.retailstore.com

---

## LOCAL DEV

Start everything:
  cd retailstore-platform && ./scripts/local-dev.sh up

Ports:
  8080 api-gateway (single entry point)
  8081 catalog-service
  8082 cart-service
  8083 checkout-service
  8084 order-service
  8085 identity-service
  8086 experience-service
  8000 dynamodb-local
  6379 redis
  5432 postgres-orders
  5433 postgres-identity

Frontend:
  cd web-storefront && npm install && npm run dev → http://localhost:3000

Swagger UIs:
  http://localhost:8081/swagger-ui.html (catalog)
  http://localhost:8082/swagger-ui.html (cart)
  http://localhost:8083/swagger-ui.html (checkout)
  http://localhost:8084/swagger-ui.html (order)
  http://localhost:8085/swagger-ui.html (identity)
  http://localhost:8086/swagger-ui.html (experience)

---

---

## SOURCE: api-gateway


### `api-gateway/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.3.4</version></parent>
  <groupId>com.retailstore</groupId><artifactId>api-gateway</artifactId><version>1.0.0</version>
  <name>api-gateway</name>
  <description>RetailStore — API Gateway (Spring Cloud Gateway): auth, rate-limit, routing</description>
  <properties>
    <java.version>21</java.version>
    <spring-cloud.version>2023.0.3</spring-cloud.version>
    <lombok.version>1.18.34</lombok.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-gateway</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis-reactive</artifactId></dependency>
    <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-starter-circuitbreaker-reactor-resilience4j</artifactId></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <dependencyManagement>
    <dependencies>
      <dependency><groupId>org.springframework.cloud</groupId><artifactId>spring-cloud-dependencies</artifactId>
        <version>${spring-cloud.version}</version><type>pom</type><scope>import</scope></dependency>
    </dependencies>
  </dependencyManagement>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
        <configuration><source>${java.version}</source><target>${java.version}</target>
          <annotationProcessorPaths><path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path></annotationProcessorPaths>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>

```

### `api-gateway/Dockerfile`

```
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /build/target/api-gateway-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-XX:+UseContainerSupport","-XX:MaxRAMPercentage=75.0","-XX:+ExitOnOutOfMemoryError","-jar","app.jar"]

```

### `api-gateway/src/main/resources/application.yml`

```yaml
server:
  port: 8080
  shutdown: graceful

spring:
  application:
    name: api-gateway
  cloud:
    gateway:
      # Global CORS — web-storefront needs this
      globalcors:
        cors-configurations:
          '[/**]':
            allowed-origins:
              - "http://localhost:3000"
              - "${ALLOWED_ORIGIN:https://shop.retailstore.com}"
            allowed-methods: [GET, POST, PUT, DELETE, PATCH, OPTIONS]
            allowed-headers: ["*"]
            exposed-headers: [X-Correlation-Id, X-Request-Id]
            allow-credentials: true
            max-age: 3600

      routes:
        # ── Experience layer (BFF) ──
        - id: experience-service
          uri: ${RETAIL_GATEWAY_ROUTES_EXPERIENCE:http://experience}
          predicates:
            - Path=/api/v1/experience/**
          filters:
            - name: CircuitBreaker
              args:
                name: experience-cb
                fallbackUri: forward:/fallback/experience
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 100
                redis-rate-limiter.burstCapacity: 200
                key-resolver: "#{@ipKeyResolver}"

        # ── Catalog service ──
        - id: catalog-service
          uri: ${RETAIL_GATEWAY_ROUTES_CATALOG:http://catalog}
          predicates:
            - Path=/api/v1/catalog/**
          filters:
            - name: CircuitBreaker
              args:
                name: catalog-cb
                fallbackUri: forward:/fallback/catalog
            - name: RequestRateLimiter
              args:
                redis-rate-limiter.replenishRate: 200
                redis-rate-limiter.burstCapacity: 400
                key-resolver: "#{@ipKeyResolver}"

        # ── Cart service ──
        - id: cart-service
          uri: ${RETAIL_GATEWAY_ROUTES_CARTS:http://carts}
          predicates:
            - Path=/api/v1/carts/**
          filters:
            - name: CircuitBreaker
              args:
                name: cart-cb
                fallbackUri: forward:/fallback/cart

        # ── Order service ──
        - id: order-service
          uri: ${RETAIL_GATEWAY_ROUTES_ORDERS:http://orders}
          predicates:
            - Path=/api/v1/orders/**
          filters:
            - name: CircuitBreaker
              args:
                name: order-cb
                fallbackUri: forward:/fallback/order

      default-filters:
        - DedupeResponseHeader=Access-Control-Allow-Credentials Access-Control-Allow-Origin
        - AddResponseHeader=X-Content-Type-Options, nosniff
        - AddResponseHeader=X-Frame-Options, DENY

  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,gateway
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
    gateway:
      enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true

resilience4j:
  circuitbreaker:
    instances:
      experience-cb:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
      catalog-cb:
        sliding-window-size: 20
        failure-rate-threshold: 50
        wait-duration-in-open-state: 10s
      cart-cb:
        sliding-window-size: 10
        failure-rate-threshold: 60
        wait-duration-in-open-state: 10s
      order-cb:
        sliding-window-size: 10
        failure-rate-threshold: 50
        wait-duration-in-open-state: 15s

logging:
  level:
    com.retailstore: INFO
    org.springframework.cloud.gateway: WARN
    reactor.netty: WARN

```

### `api-gateway/src/main/java/com/retailstore/gateway/GatewayApplication.java`

```java
package com.retailstore.gateway;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class GatewayApplication {
    public static void main(String[] args) { SpringApplication.run(GatewayApplication.class, args); }
}

```

### `api-gateway/src/main/java/com/retailstore/gateway/filter/CorrelationIdFilter.java`

```java
package com.retailstore.gateway.filter;

import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.util.UUID;

@Component
public class CorrelationIdFilter implements GlobalFilter, Ordered {

    private static final String CORRELATION_HEADER = "X-Correlation-Id";

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String correlationId = exchange.getRequest().getHeaders()
            .getFirst(CORRELATION_HEADER);
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        String finalId = correlationId;
        return chain.filter(exchange.mutate()
            .request(exchange.getRequest().mutate()
                .header(CORRELATION_HEADER, finalId).build())
            .response(exchange.getResponse())
            .build())
            .then(Mono.fromRunnable(() ->
                exchange.getResponse().getHeaders().add(CORRELATION_HEADER, finalId)));
    }

    @Override public int getOrder() { return -2; }
}

```

### `api-gateway/src/main/java/com/retailstore/gateway/filter/RequestLoggingFilter.java`

```java
package com.retailstore.gateway.filter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;
import java.time.Instant;

@Slf4j
@Component
public class RequestLoggingFilter implements GlobalFilter, Ordered {

    @Override
    public Mono<Mono<Void>> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        ServerHttpRequest req = exchange.getRequest();
        long start = System.currentTimeMillis();
        String requestId = req.getId();
        String path = req.getURI().getPath();
        String method = req.getMethod().name();
        String clientChannel = req.getHeaders().getFirst("X-Client-Channel");

        // Attach request ID downstream
        ServerHttpRequest mutated = req.mutate()
            .header("X-Request-Id", requestId)
            .header("X-Gateway-Timestamp", Instant.now().toString())
            .build();

        return Mono.just(chain.filter(exchange.mutate().request(mutated).build())
            .doFinally(signal -> {
                long duration = System.currentTimeMillis() - start;
                int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
                log.info("{} {} → {} ({}ms) channel={}", method, path, status, duration,
                    clientChannel != null ? clientChannel : "WEB");
            }));
    }

    @Override public int getOrder() { return -1; }
}

```

---

## SOURCE: catalog-service


### `catalog-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
  </parent>
  <groupId>com.retailstore</groupId>
  <artifactId>catalog-service</artifactId>
  <version>1.0.0</version>
  <name>catalog-service</name>
  <description>RetailStore — Catalog domain microservice (products, tags, inventory metadata)</description>
  <properties>
    <java.version>21</java.version>
    <lombok.version>1.18.34</lombok.version>
    <springdoc.version>2.6.0</springdoc.version>
    <mapstruct.version>1.6.2</mapstruct.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-cache</artifactId></dependency>
    <dependency><groupId>com.mysql</groupId><artifactId>mysql-connector-j</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId><version>${springdoc.version}</version></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
    <dependency><groupId>org.mapstruct</groupId><artifactId>mapstruct</artifactId><version>${mapstruct.version}</version></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.mockito</groupId><artifactId>mockito-core</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-maven-plugin</artifactId>
        <configuration>
          <excludes><exclude><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId></exclude></excludes>
        </configuration>
      </plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <source>${java.version}</source><target>${java.version}</target>
          <annotationProcessorPaths>
            <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path>
            <path><groupId>org.mapstruct</groupId><artifactId>mapstruct-processor</artifactId><version>${mapstruct.version}</version></path>
          </annotationProcessorPaths>
          <compilerArgs><arg>-Amapstruct.defaultComponentModel=spring</arg></compilerArgs>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>

```

### `catalog-service/Dockerfile`

```
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 \
    ./mvnw -B clean package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /build/target/catalog-service-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java", \
  "-XX:+UseContainerSupport", \
  "-XX:MaxRAMPercentage=75.0", \
  "-XX:+ExitOnOutOfMemoryError", \
  "-Djava.security.egd=file:/dev/./urandom", \
  "-jar", "app.jar"]

```

### `catalog-service/src/main/resources/application.yml`

```yaml
server:
  port: 8080
  shutdown: graceful

spring:
  application:
    name: catalog-service
  lifecycle:
    timeout-per-shutdown-phase: 30s
  datasource:
    url: jdbc:h2:mem:catalogdb;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    open-in-view: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
        default_batch_fetch_size: 20
  cache:
    type: simple

management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus,env
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true

springdoc:
  api-docs:
    path: /api-docs
  swagger-ui:
    path: /swagger-ui.html
    operations-sorter: method

logging:
  level:
    com.retailstore: INFO
    org.hibernate.SQL: WARN
  pattern:
    console: "%d{HH:mm:ss} [%thread] %-5level %logger{36} — %msg%n"

retail:
  catalog:
    persistence:
      provider: ${RETAIL_CATALOG_PERSISTENCE_PROVIDER:in-memory}
      endpoint: ${RETAIL_CATALOG_PERSISTENCE_ENDPOINT:}
      db-name: ${RETAIL_CATALOG_PERSISTENCE_DB_NAME:catalogdb}
      user: ${RETAIL_CATALOG_PERSISTENCE_USER:catalog_user}
      password: ${RETAIL_CATALOG_PERSISTENCE_PASSWORD:}

```

### `catalog-service/src/main/resources/application-mysql.yml`

```yaml
spring:
  datasource:
    url: >-
      jdbc:mysql://${RETAIL_CATALOG_PERSISTENCE_ENDPOINT}/${RETAIL_CATALOG_PERSISTENCE_DB_NAME:catalogdb}
      ?useSSL=true&requireSSL=true&serverTimezone=UTC&connectTimeout=5000&socketTimeout=30000
    username: ${RETAIL_CATALOG_PERSISTENCE_USER:catalog_user}
    password: ${RETAIL_CATALOG_PERSISTENCE_PASSWORD}
    driver-class-name: com.mysql.cj.jdbc.Driver
    hikari:
      minimum-idle: 2
      maximum-pool-size: 20
      connection-timeout: 5000
      idle-timeout: 300000
      max-lifetime: 1200000
      pool-name: CatalogHikariPool
  jpa:
    properties:
      hibernate:
        dialect: org.hibernate.dialect.MySQLDialect
  cache:
    type: simple

```

### `catalog-service/src/main/java/com/retailstore/catalog/CatalogApplication.java`

```java
package com.retailstore.catalog;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

@SpringBootApplication
@EnableCaching
public class CatalogApplication {
    public static void main(String[] args) {
        SpringApplication.run(CatalogApplication.class, args);
    }
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/domain/model/Tag.java`

```java
package com.retailstore.catalog.domain.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tags",
       indexes = @Index(name = "idx_tag_name", columnList = "name"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 100)
    private String displayName;
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/domain/model/Product.java`

```java
package com.retailstore.catalog.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "products",
       indexes = {
           @Index(name = "idx_product_price", columnList = "price"),
           @Index(name = "idx_product_name",  columnList = "name")
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Product {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 255)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal price;

    @Column(nullable = false)
    @Builder.Default
    private Integer stockQuantity = 0;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = true;

    @ManyToMany(fetch = FetchType.LAZY)
    @JoinTable(
        name = "product_tags",
        joinColumns = @JoinColumn(name = "product_id"),
        inverseJoinColumns = @JoinColumn(name = "tag_id")
    )
    @Builder.Default
    private List<Tag> tags = new ArrayList<>();

    public boolean isInStock() {
        return stockQuantity != null && stockQuantity > 0;
    }

    public boolean isAvailable() {
        return Boolean.TRUE.equals(active) && isInStock();
    }
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/domain/exception/ProductNotFoundException.java`

```java
package com.retailstore.catalog.domain.exception;

public class ProductNotFoundException extends RuntimeException {
    private final String productId;

    public ProductNotFoundException(String productId) {
        super("Product not found with id: " + productId);
        this.productId = productId;
    }

    public String getProductId() { return productId; }
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/domain/exception/DuplicateProductException.java`

```java
package com.retailstore.catalog.domain.exception;

public class DuplicateProductException extends RuntimeException {
    public DuplicateProductException(String id) {
        super("Product already exists with id: " + id);
    }
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/domain/repository/ProductJpaRepository.java`

```java
package com.retailstore.catalog.domain.repository;

import com.retailstore.catalog.domain.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.util.List;

public interface ProductJpaRepository extends JpaRepository<Product, String> {

    @Query("SELECT DISTINCT p FROM Product p JOIN p.tags t WHERE t.name IN :tags AND p.active = true")
    Page<Product> findByTagNamesAndActive(@Param("tags") List<String> tags, Pageable pageable);

    @Query("SELECT COUNT(DISTINCT p) FROM Product p JOIN p.tags t WHERE t.name IN :tags AND p.active = true")
    long countByTagNamesAndActive(@Param("tags") List<String> tags);

    @Query("SELECT p FROM Product p WHERE p.active = true")
    Page<Product> findAllActive(Pageable pageable);
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/domain/repository/TagJpaRepository.java`

```java
package com.retailstore.catalog.domain.repository;

import com.retailstore.catalog.domain.model.Tag;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface TagJpaRepository extends JpaRepository<Tag, Long> {
    Optional<Tag> findByName(String name);
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/application/port/in/GetProductUseCase.java`

```java
package com.retailstore.catalog.application.port.in;

import com.retailstore.catalog.api.rest.v1.dto.response.ProductResponse;
import com.retailstore.catalog.api.rest.v1.dto.response.PagedProductResponse;
import java.util.List;

public interface GetProductUseCase {
    ProductResponse getById(String id);
    PagedProductResponse getProducts(List<String> tags, String order, int page, int size);
    long countProducts(List<String> tags);
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/application/port/in/GetTagUseCase.java`

```java
package com.retailstore.catalog.application.port.in;

import com.retailstore.catalog.api.rest.v1.dto.response.TagResponse;
import java.util.List;

public interface GetTagUseCase {
    List<TagResponse> getAllTags();
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/application/port/out/ProductRepositoryPort.java`

```java
package com.retailstore.catalog.application.port.out;

import com.retailstore.catalog.domain.model.Product;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import java.util.List;
import java.util.Optional;

public interface ProductRepositoryPort {
    Optional<Product> findById(String id);
    Page<Product> findAll(Pageable pageable);
    Page<Product> findByTagNames(List<String> tags, Pageable pageable);
    long countAll();
    long countByTagNames(List<String> tags);
    Product save(Product product);
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/application/service/CatalogService.java`

```java
package com.retailstore.catalog.application.service;

import com.retailstore.catalog.api.rest.v1.dto.response.*;
import com.retailstore.catalog.api.rest.v1.mapper.CatalogMapper;
import com.retailstore.catalog.application.port.in.GetProductUseCase;
import com.retailstore.catalog.application.port.in.GetTagUseCase;
import com.retailstore.catalog.application.port.out.ProductRepositoryPort;
import com.retailstore.catalog.domain.exception.ProductNotFoundException;
import com.retailstore.catalog.domain.model.Product;
import com.retailstore.catalog.domain.repository.TagJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CatalogService implements GetProductUseCase, GetTagUseCase {

    private final ProductRepositoryPort productRepository;
    private final TagJpaRepository tagRepository;
    private final CatalogMapper mapper;

    @Override
    @Cacheable(value = "products", key = "#id")
    public ProductResponse getById(String id) {
        log.debug("Fetching product by id: {}", id);
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new ProductNotFoundException(id));
        return mapper.toProductResponse(product);
    }

    @Override
    public PagedProductResponse getProducts(List<String> tags, String order, int page, int size) {
        log.debug("Fetching products — tags={}, order={}, page={}, size={}", tags, order, page, size);

        Sort sort = resolveSort(order);
        Pageable pageable = PageRequest.of(page, size, sort);

        Page<Product> resultPage = (tags != null && !tags.isEmpty())
            ? productRepository.findByTagNames(tags, pageable)
            : productRepository.findAll(pageable);

        List<ProductResponse> products = resultPage.getContent().stream()
            .map(mapper::toProductResponse)
            .collect(Collectors.toList());

        return PagedProductResponse.builder()
            .products(products)
            .totalCount(resultPage.getTotalElements())
            .page(page)
            .size(size)
            .totalPages(resultPage.getTotalPages())
            .hasNext(resultPage.hasNext())
            .hasPrevious(resultPage.hasPrevious())
            .build();
    }

    @Override
    public long countProducts(List<String> tags) {
        return (tags != null && !tags.isEmpty())
            ? productRepository.countByTagNames(tags)
            : productRepository.countAll();
    }

    @Override
    @Cacheable(value = "tags")
    public List<TagResponse> getAllTags() {
        return tagRepository.findAll().stream()
            .map(mapper::toTagResponse)
            .collect(Collectors.toList());
    }

    private Sort resolveSort(String order) {
        return switch (order == null ? "name_asc" : order) {
            case "price_asc"  -> Sort.by("price").ascending();
            case "price_desc" -> Sort.by("price").descending();
            case "name_desc"  -> Sort.by("name").descending();
            default           -> Sort.by("name").ascending();
        };
    }
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/infrastructure/persistence/ProductRepositoryAdapter.java`

```java
package com.retailstore.catalog.infrastructure.persistence;

import com.retailstore.catalog.application.port.out.ProductRepositoryPort;
import com.retailstore.catalog.domain.model.Product;
import com.retailstore.catalog.domain.repository.ProductJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Optional;

@Component
@RequiredArgsConstructor
public class ProductRepositoryAdapter implements ProductRepositoryPort {

    private final ProductJpaRepository jpaRepository;

    @Override public Optional<Product> findById(String id) { return jpaRepository.findById(id); }
    @Override public Page<Product> findAll(Pageable pageable) { return jpaRepository.findAllActive(pageable); }
    @Override public Page<Product> findByTagNames(List<String> tags, Pageable pageable) { return jpaRepository.findByTagNamesAndActive(tags, pageable); }
    @Override public long countAll() { return jpaRepository.count(); }
    @Override public long countByTagNames(List<String> tags) { return jpaRepository.countByTagNamesAndActive(tags); }
    @Override public Product save(Product product) { return jpaRepository.save(product); }
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/infrastructure/config/DataSeeder.java`

```java
package com.retailstore.catalog.infrastructure.config;

import com.retailstore.catalog.application.port.out.ProductRepositoryPort;
import com.retailstore.catalog.domain.model.Product;
import com.retailstore.catalog.domain.model.Tag;
import com.retailstore.catalog.domain.repository.TagJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
@Profile("!test")
public class DataSeeder implements ApplicationRunner {

    private final ProductRepositoryPort productRepository;
    private final TagJpaRepository tagRepository;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        if (productRepository.countAll() > 0) {
            log.info("Catalog already seeded — skipping");
            return;
        }
        log.info("Seeding catalog with sample products...");

        Tag electronics = tag("electronics", "Electronics");
        Tag clothing    = tag("clothing",    "Clothing");
        Tag books       = tag("books",       "Books");
        Tag furniture   = tag("furniture",   "Furniture");
        Tag fitness     = tag("fitness",     "Fitness");

        List<Product> products = List.of(
            product("prod-001", "Wireless Noise-Cancelling Headphones",
                "Premium ANC headphones with 30-hour battery life, spatial audio, and foldable design.",
                new BigDecimal("149.99"), 85, electronics),
            product("prod-002", "Mechanical Keyboard TKL",
                "Tenkeyless mechanical keyboard with Cherry MX switches, per-key RGB lighting.",
                new BigDecimal("79.99"), 120, electronics),
            product("prod-003", "4K USB-C Monitor 27in",
                "27-inch IPS panel, 4K UHD, 99% sRGB, USB-C 90W power delivery, adjustable stand.",
                new BigDecimal("399.99"), 35, electronics),
            product("prod-004", "Slim Running Shorts",
                "Lightweight moisture-wicking running shorts with 4-inch inseam and side pockets.",
                new BigDecimal("34.99"), 200, clothing, fitness),
            product("prod-005", "Merino Wool Crew Neck",
                "100% merino wool sweater — temperature-regulating, odour-resistant, machine washable.",
                new BigDecimal("89.99"), 60, clothing),
            product("prod-006", "Designing Data-Intensive Applications",
                "Martin Kleppmann's definitive guide to building scalable, reliable, and maintainable systems.",
                new BigDecimal("49.99"), 75, books),
            product("prod-007", "Clean Architecture",
                "Robert C. Martin on component design, SOLID principles, and software architecture.",
                new BigDecimal("39.99"), 90, books),
            product("prod-008", "Ergonomic Mesh Office Chair",
                "Lumbar support, adjustable armrests, breathable mesh back, 5-year warranty.",
                new BigDecimal("449.99"), 15, furniture),
            product("prod-009", "Standing Desk 140cm",
                "Electric height-adjustable desk with memory presets, cable management, solid MDF top.",
                new BigDecimal("699.99"), 10, furniture),
            product("prod-010", "Adjustable Dumbbell Set 5-50lb",
                "Replaces 15 sets. Click-dial adjustment, compact storage, durable neoprene coating.",
                new BigDecimal("329.99"), 25, fitness)
        );
        products.forEach(productRepository::save);
        log.info("Seeded {} products across {} tags", products.size(), 5);
    }

    private Tag tag(String name, String displayName) {
        return tagRepository.findByName(name)
            .orElseGet(() -> tagRepository.save(Tag.builder().name(name).displayName(displayName).build()));
    }

    private Product product(String id, String name, String desc, BigDecimal price, int stock, Tag... tags) {
        return Product.builder()
            .id(id).name(name).description(desc).price(price)
            .stockQuantity(stock).active(true)
            .tags(new ArrayList<>(Arrays.asList(tags)))
            .build();
    }
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/infrastructure/config/OpenApiConfig.java`

```java
package com.retailstore.catalog.infrastructure.config;

import io.swagger.v3.oas.models.*;
import io.swagger.v3.oas.models.info.*;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.*;
import java.util.List;

@Configuration
public class OpenApiConfig {
    @Bean
    public OpenAPI catalogOpenApi() {
        return new OpenAPI()
            .info(new Info()
                .title("Catalog Service API")
                .description("Product catalog — browse, filter, and retrieve product information")
                .version("1.0.0")
                .contact(new Contact().name("RetailStore Platform").email("platform@retailstore.com"))
                .license(new License().name("Apache 2.0").url("https://www.apache.org/licenses/LICENSE-2.0")))
            .servers(List.of(new Server().url("/").description("Current server")));
    }
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/api/rest/v1/dto/response/TagResponse.java`

```java
package com.retailstore.catalog.api.rest.v1.dto.response;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TagResponse {
    private Long id;
    private String name;
    private String displayName;
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/api/rest/v1/dto/response/ProductResponse.java`

```java
package com.retailstore.catalog.api.rest.v1.dto.response;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductResponse {
    private String id;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer stockQuantity;
    private boolean inStock;
    private boolean available;
    private List<TagResponse> tags;
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/api/rest/v1/dto/response/PagedProductResponse.java`

```java
package com.retailstore.catalog.api.rest.v1.dto.response;

import lombok.*;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class PagedProductResponse {
    private List<ProductResponse> products;
    private long totalCount;
    private int page;
    private int size;
    private int totalPages;
    private boolean hasNext;
    private boolean hasPrevious;
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/api/rest/v1/dto/response/ApiErrorResponse.java`

```java
package com.retailstore.catalog.api.rest.v1.dto.response;

import lombok.*;
import java.time.Instant;
import java.util.Map;

@Getter @Setter @Builder
public class ApiErrorResponse {
    private int status;
    private String error;
    private String message;
    private String path;
    @Builder.Default
    private Instant timestamp = Instant.now();
    private Map<String, String> fieldErrors;
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/api/rest/v1/mapper/CatalogMapper.java`

```java
package com.retailstore.catalog.api.rest.v1.mapper;

import com.retailstore.catalog.api.rest.v1.dto.response.ProductResponse;
import com.retailstore.catalog.api.rest.v1.dto.response.TagResponse;
import com.retailstore.catalog.domain.model.Product;
import com.retailstore.catalog.domain.model.Tag;
import org.mapstruct.*;

@Mapper(componentModel = "spring")
public interface CatalogMapper {

    @Mapping(target = "inStock",    expression = "java(product.isInStock())")
    @Mapping(target = "available",  expression = "java(product.isAvailable())")
    ProductResponse toProductResponse(Product product);

    TagResponse toTagResponse(Tag tag);
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/api/rest/v1/controller/CatalogController.java`

```java
package com.retailstore.catalog.api.rest.v1.controller;

import com.retailstore.catalog.api.rest.v1.dto.response.*;
import com.retailstore.catalog.application.port.in.GetProductUseCase;
import com.retailstore.catalog.application.port.in.GetTagUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/catalog")
@RequiredArgsConstructor
@Tag(name = "Catalog", description = "Product catalog — browse, search, and filter products")
public class CatalogController {

    private final GetProductUseCase getProductUseCase;
    private final GetTagUseCase getTagUseCase;

    @GetMapping("/products")
    @Operation(summary = "List products",
               description = "Returns a paginated list of active products. Optionally filter by tags.")
    public ResponseEntity<PagedProductResponse> listProducts(
            @Parameter(description = "Comma-separated tag names to filter by")
            @RequestParam(required = false) List<String> tags,
            @Parameter(description = "Sort order: name_asc, name_desc, price_asc, price_desc")
            @RequestParam(defaultValue = "name_asc") String order,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "20") int size) {

        return ResponseEntity.ok(getProductUseCase.getProducts(tags, order, page, size));
    }

    @GetMapping("/products/{id}")
    @Operation(summary = "Get product by ID")
    public ResponseEntity<ProductResponse> getProduct(@PathVariable String id) {
        return ResponseEntity.ok(getProductUseCase.getById(id));
    }

    @GetMapping("/products/count")
    @Operation(summary = "Get product count")
    public ResponseEntity<Map<String, Long>> countProducts(
            @RequestParam(required = false) List<String> tags) {
        return ResponseEntity.ok(Map.of("count", getProductUseCase.countProducts(tags)));
    }

    @GetMapping("/tags")
    @Operation(summary = "List all product tags")
    public ResponseEntity<List<TagResponse>> listTags() {
        return ResponseEntity.ok(getTagUseCase.getAllTags());
    }
}

```

### `catalog-service/src/main/java/com/retailstore/catalog/api/rest/v1/controller/GlobalExceptionHandler.java`

```java
package com.retailstore.catalog.api.rest.v1.controller;

import com.retailstore.catalog.api.rest.v1.dto.response.ApiErrorResponse;
import com.retailstore.catalog.domain.exception.DuplicateProductException;
import com.retailstore.catalog.domain.exception.ProductNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.*;
import java.util.stream.Collectors;

@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(ProductNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ApiErrorResponse handleNotFound(ProductNotFoundException ex, HttpServletRequest request) {
        return ApiErrorResponse.builder()
            .status(404).error("Not Found").message(ex.getMessage())
            .path(request.getRequestURI()).build();
    }

    @ExceptionHandler(DuplicateProductException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ApiErrorResponse handleDuplicate(DuplicateProductException ex, HttpServletRequest request) {
        return ApiErrorResponse.builder()
            .status(409).error("Conflict").message(ex.getMessage())
            .path(request.getRequestURI()).build();
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiErrorResponse handleValidation(MethodArgumentNotValidException ex, HttpServletRequest request) {
        var fieldErrors = ex.getBindingResult().getFieldErrors().stream()
            .collect(Collectors.toMap(FieldError::getField, fe -> 
                fe.getDefaultMessage() != null ? fe.getDefaultMessage() : "invalid"));

        return ApiErrorResponse.builder()
            .status(400).error("Validation Failed").message("One or more fields are invalid")
            .path(request.getRequestURI()).fieldErrors(fieldErrors).build();
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiErrorResponse handleGeneral(Exception ex, HttpServletRequest request) {
        log.error("Unhandled error on {}", request.getRequestURI(), ex);
        return ApiErrorResponse.builder()
            .status(500).error("Internal Server Error").message("An unexpected error occurred")
            .path(request.getRequestURI()).build();
    }
}

```

### `catalog-service/chart/Chart.yaml`

```yaml
apiVersion: v2
name: catalog-service
description: RetailStore — Catalog domain microservice
type: application
version: 1.0.0
appVersion: "1.0.0"
maintainers:
  - name: RetailStore Platform Team
keywords: [retailstore, catalog, products, ecommerce]

```

### `catalog-service/chart/values.yaml`

```yaml
replicaCount: 1

image:
  repository: ""
  pullPolicy: IfNotPresent
  tag: ""

nameOverride: ""
fullnameOverride: "catalog"

serviceAccount:
  create: true
  annotations: {}
  name: ""

service:
  type: ClusterIP
  port: 80
  targetPort: 8080

resources:
  limits:
    memory: 512Mi
  requests:
    cpu: 100m
    memory: 256Mi

autoscaling:
  enabled: false
  minReplicas: 2
  maxReplicas: 10
  targetCPUUtilizationPercentage: 70

livenessProbe:
  httpGet:
    path: /actuator/health/liveness
    port: 8080
  initialDelaySeconds: 30
  periodSeconds: 10
  failureThreshold: 3

readinessProbe:
  httpGet:
    path: /actuator/health/readiness
    port: 8080
  initialDelaySeconds: 10
  periodSeconds: 5
  failureThreshold: 3

podAnnotations:
  prometheus.io/scrape: "true"
  prometheus.io/port: "8080"
  prometheus.io/path: "/actuator/prometheus"

podSecurityContext:
  fsGroup: 1000
  runAsNonRoot: true

securityContext:
  capabilities:
    drop: [ALL]
  readOnlyRootFilesystem: true
  runAsNonRoot: true
  runAsUser: 1000

appEnv:
  RETAIL_CATALOG_PERSISTENCE_PROVIDER: "in-memory"
  RETAIL_CATALOG_PERSISTENCE_ENDPOINT: ""
  RETAIL_CATALOG_PERSISTENCE_DB_NAME: "catalogdb"
  RETAIL_CATALOG_PERSISTENCE_USER: "catalog_user"
  SPRING_PROFILES_ACTIVE: ""

configMap:
  create: true

nodeSelector: {}
tolerations: []
affinity: {}

```

### `catalog-service/chart/templates/_helpers.tpl`

```yaml
{{- define "catalog.name" -}}{{- default "catalog" .Values.nameOverride | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "catalog.fullname" -}}
{{- if .Values.fullnameOverride }}{{- .Values.fullnameOverride | trunc 63 | trimSuffix "-" }}
{{- else }}{{- printf "%s-%s" .Release.Name (include "catalog.name" .) | trunc 63 | trimSuffix "-" }}{{- end }}
{{- end }}
{{- define "catalog.chart" -}}{{- printf "%s-%s" .Chart.Name .Chart.Version | replace "+" "_" | trunc 63 | trimSuffix "-" }}{{- end }}
{{- define "catalog.labels" -}}
helm.sh/chart: {{ include "catalog.chart" . }}
{{ include "catalog.selectorLabels" . }}
app.kubernetes.io/version: {{ .Chart.AppVersion | quote }}
app.kubernetes.io/managed-by: {{ .Release.Service }}
{{- end }}
{{- define "catalog.selectorLabels" -}}
app.kubernetes.io/name: {{ include "catalog.name" . }}
app.kubernetes.io/instance: {{ .Release.Name }}
app.kubernetes.io/component: service
app.kubernetes.io/part-of: retailstore
{{- end }}
{{- define "catalog.serviceAccountName" -}}
{{- if .Values.serviceAccount.create }}{{- default (include "catalog.fullname" .) .Values.serviceAccount.name }}
{{- else }}default{{- end }}
{{- end }}

```

### `catalog-service/chart/templates/deployment.yaml`

```yaml
apiVersion: apps/v1
kind: Deployment
metadata:
  name: {{ include "catalog.fullname" . }}
  labels: {{- include "catalog.labels" . | nindent 4 }}
spec:
  {{- if not .Values.autoscaling.enabled }}
  replicas: {{ .Values.replicaCount }}
  {{- end }}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxUnavailable: 0
      maxSurge: 1
  selector:
    matchLabels: {{- include "catalog.selectorLabels" . | nindent 6 }}
  template:
    metadata:
      labels: {{- include "catalog.selectorLabels" . | nindent 8 }}
      {{- with .Values.podAnnotations }}
      annotations: {{- toYaml . | nindent 8 }}
      {{- end }}
    spec:
      serviceAccountName: {{ include "catalog.serviceAccountName" . }}
      securityContext: {{- toYaml .Values.podSecurityContext | nindent 8 }}
      terminationGracePeriodSeconds: 60
      containers:
        - name: catalog
          image: "{{ .Values.image.repository }}:{{ .Values.image.tag | default .Chart.AppVersion }}"
          imagePullPolicy: {{ .Values.image.pullPolicy }}
          securityContext: {{- toYaml .Values.securityContext | nindent 12 }}
          ports:
            - name: http
              containerPort: 8080
              protocol: TCP
          envFrom:
            - configMapRef:
                name: {{ include "catalog.fullname" . }}
          env:
            - name: JAVA_OPTS
              value: "-XX:+UseContainerSupport -XX:MaxRAMPercentage=75.0 -XX:+ExitOnOutOfMemoryError"
            - name: RETAIL_CATALOG_PERSISTENCE_PASSWORD
              valueFrom:
                secretKeyRef:
                  name: catalog-db-credentials
                  key: password
                  optional: true
          livenessProbe: {{- toYaml .Values.livenessProbe | nindent 12 }}
          readinessProbe: {{- toYaml .Values.readinessProbe | nindent 12 }}
          resources: {{- toYaml .Values.resources | nindent 12 }}
          volumeMounts:
            - name: tmp
              mountPath: /tmp
      volumes:
        - name: tmp
          emptyDir: {}
      {{- with .Values.nodeSelector }}
      nodeSelector: {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.affinity }}
      affinity: {{- toYaml . | nindent 8 }}
      {{- end }}
      {{- with .Values.tolerations }}
      tolerations: {{- toYaml . | nindent 8 }}
      {{- end }}

```

### `catalog-service/chart/templates/service.yaml`

```yaml
apiVersion: v1
kind: Service
metadata:
  name: {{ include "catalog.fullname" . }}
  labels: {{- include "catalog.labels" . | nindent 4 }}
spec:
  type: {{ .Values.service.type }}
  ports:
    - port: {{ .Values.service.port }}
      targetPort: {{ .Values.service.targetPort }}
      protocol: TCP
      name: http
  selector: {{- include "catalog.selectorLabels" . | nindent 4 }}

```

### `catalog-service/chart/templates/configmap.yaml`

```yaml
{{- if .Values.configMap.create }}
apiVersion: v1
kind: ConfigMap
metadata:
  name: {{ include "catalog.fullname" . }}
  labels: {{- include "catalog.labels" . | nindent 4 }}
data:
  {{- range $k, $v := .Values.appEnv }}
  {{ $k }}: {{ $v | quote }}
  {{- end }}
{{- end }}

```

### `catalog-service/chart/templates/serviceaccount.yaml`

```yaml
{{- if .Values.serviceAccount.create }}
apiVersion: v1
kind: ServiceAccount
metadata:
  name: {{ include "catalog.serviceAccountName" . }}
  labels: {{- include "catalog.labels" . | nindent 4 }}
  {{- with .Values.serviceAccount.annotations }}
  annotations: {{- toYaml . | nindent 4 }}
  {{- end }}
{{- end }}

```

### `catalog-service/chart/templates/hpa.yaml`

```yaml
{{- if .Values.autoscaling.enabled }}
apiVersion: autoscaling/v2
kind: HorizontalPodAutoscaler
metadata:
  name: {{ include "catalog.fullname" . }}
  labels: {{- include "catalog.labels" . | nindent 4 }}
spec:
  scaleTargetRef:
    apiVersion: apps/v1
    kind: Deployment
    name: {{ include "catalog.fullname" . }}
  minReplicas: {{ .Values.autoscaling.minReplicas }}
  maxReplicas: {{ .Values.autoscaling.maxReplicas }}
  metrics:
    - type: Resource
      resource:
        name: cpu
        target:
          type: Utilization
          averageUtilization: {{ .Values.autoscaling.targetCPUUtilizationPercentage }}
{{- end }}

```

---

## SOURCE: cart-service


### `cart-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent>
    <groupId>org.springframework.boot</groupId>
    <artifactId>spring-boot-starter-parent</artifactId>
    <version>3.3.4</version>
  </parent>
  <groupId>com.retailstore</groupId>
  <artifactId>cart-service</artifactId>
  <version>1.0.0</version>
  <name>cart-service</name>
  <description>RetailStore — Cart domain microservice (add, update, remove items)</description>
  <properties>
    <java.version>21</java.version>
    <lombok.version>1.18.34</lombok.version>
    <springdoc.version>2.6.0</springdoc.version>
    <aws.sdk.version>2.28.0</aws.sdk.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>software.amazon.awssdk</groupId><artifactId>dynamodb</artifactId><version>${aws.sdk.version}</version></dependency>
    <dependency><groupId>software.amazon.awssdk</groupId><artifactId>dynamodb-enhanced</artifactId><version>${aws.sdk.version}</version></dependency>
    <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId><version>${springdoc.version}</version></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
      <plugin>
        <groupId>org.apache.maven.plugins</groupId>
        <artifactId>maven-compiler-plugin</artifactId>
        <configuration>
          <source>${java.version}</source><target>${java.version}</target>
          <annotationProcessorPaths>
            <path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path>
          </annotationProcessorPaths>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>

```

### `cart-service/Dockerfile`

```
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /build/target/cart-service-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-XX:+UseContainerSupport","-XX:MaxRAMPercentage=75.0","-XX:+ExitOnOutOfMemoryError","-jar","app.jar"]

```

### `cart-service/src/main/resources/application.yml`

```yaml
server:
  port: 8080
  shutdown: graceful
spring:
  application:
    name: cart-service
  lifecycle:
    timeout-per-shutdown-phase: 30s
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
logging:
  level:
    com.retailstore: INFO
retail:
  cart:
    dynamodb:
      table-name: ${RETAIL_CART_DYNAMODB_TABLE_NAME:Carts}
      endpoint:   ${RETAIL_CART_DYNAMODB_ENDPOINT:}
      region:     ${AWS_REGION:us-east-1}

```

### `cart-service/src/main/java/com/retailstore/cart/CartApplication.java`

```java
package com.retailstore.cart;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class CartApplication {
    public static void main(String[] args) { SpringApplication.run(CartApplication.class, args); }
}

```

### `cart-service/src/main/java/com/retailstore/cart/domain/model/CartItem.java`

```java
package com.retailstore.cart.domain.model;

import lombok.*;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbAttribute;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.DynamoDbBean;
import java.math.BigDecimal;

@DynamoDbBean
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CartItem {
    private String itemId;
    private String productId;
    private String productName;
    private String imageUrl;
    private int quantity;
    private BigDecimal unitPrice;

    @DynamoDbAttribute("itemId")
    public String getItemId() { return itemId; }

    public BigDecimal getLineTotal() {
        return unitPrice != null ? unitPrice.multiply(BigDecimal.valueOf(quantity)) : BigDecimal.ZERO;
    }
}

```

### `cart-service/src/main/java/com/retailstore/cart/domain/model/Cart.java`

```java
package com.retailstore.cart.domain.model;

import lombok.*;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@DynamoDbBean
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Cart {

    private String customerId;
    @Builder.Default
    private List<CartItem> items = new ArrayList<>();
    private Instant lastModified;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("customerId")
    public String getCustomerId() { return customerId; }

    public BigDecimal getSubtotal() {
        return items.stream()
            .map(CartItem::getLineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public int getTotalItemCount() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    public int getLineItemCount() { return items.size(); }

    public Optional<CartItem> findItem(String itemId) {
        return items.stream().filter(i -> i.getItemId().equals(itemId)).findFirst();
    }

    public Optional<CartItem> findItemByProductId(String productId) {
        return items.stream().filter(i -> i.getProductId().equals(productId)).findFirst();
    }
}

```

### `cart-service/src/main/java/com/retailstore/cart/domain/exception/CartItemNotFoundException.java`

```java
package com.retailstore.cart.domain.exception;

public class CartItemNotFoundException extends RuntimeException {
    public CartItemNotFoundException(String itemId) {
        super("Cart item not found: " + itemId);
    }
}

```

### `cart-service/src/main/java/com/retailstore/cart/domain/exception/InvalidQuantityException.java`

```java
package com.retailstore.cart.domain.exception;

public class InvalidQuantityException extends RuntimeException {
    public InvalidQuantityException(int quantity) {
        super("Quantity must be between 1 and 100, got: " + quantity);
    }
}

```

### `cart-service/src/main/java/com/retailstore/cart/infrastructure/config/DynamoDbConfig.java`

```java
package com.retailstore.cart.infrastructure.config;

import com.retailstore.cart.domain.model.Cart;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.enhanced.dynamodb.*;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.dynamodb.DynamoDbClient;
import java.net.URI;

@Slf4j
@Configuration
public class DynamoDbConfig {

    @Value("${retail.cart.dynamodb.endpoint:}")
    private String endpoint;

    @Value("${retail.cart.dynamodb.region:us-east-1}")
    private String region;

    @Value("${retail.cart.dynamodb.table-name:Carts}")
    private String tableName;

    @Bean
    public DynamoDbClient dynamoDbClient() {
        var builder = DynamoDbClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create());
        if (endpoint != null && !endpoint.isBlank()) {
            log.info("Using DynamoDB local endpoint: {}", endpoint);
            builder.endpointOverride(URI.create(endpoint));
        }
        return builder.build();
    }

    @Bean
    public DynamoDbEnhancedClient dynamoDbEnhancedClient(DynamoDbClient client) {
        return DynamoDbEnhancedClient.builder().dynamoDbClient(client).build();
    }

    @Bean
    public DynamoDbTable<Cart> cartTable(DynamoDbEnhancedClient client) {
        return client.table(tableName, TableSchema.fromBean(Cart.class));
    }
}

```

### `cart-service/src/main/java/com/retailstore/cart/infrastructure/dynamodb/CartDynamoDbRepository.java`

```java
package com.retailstore.cart.infrastructure.dynamodb;

import com.retailstore.cart.domain.model.Cart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CartDynamoDbRepository {

    private final DynamoDbTable<Cart> cartTable;

    public Optional<Cart> findByCustomerId(String customerId) {
        try {
            Cart cart = cartTable.getItem(Key.builder().partitionValue(customerId).build());
            return Optional.ofNullable(cart);
        } catch (Exception e) {
            log.error("Error reading cart for customer {}", customerId, e);
            return Optional.empty();
        }
    }

    public Cart save(Cart cart) {
        cartTable.putItem(cart);
        return cart;
    }

    public void deleteByCustomerId(String customerId) {
        cartTable.deleteItem(Key.builder().partitionValue(customerId).build());
    }
}

```

### `cart-service/src/main/java/com/retailstore/cart/api/rest/v1/dto/AddItemRequest.java`

```java
package com.retailstore.cart.api.rest.v1.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class AddItemRequest {
    @NotBlank(message = "productId is required")
    private String productId;

    @NotBlank(message = "productName is required")
    private String productName;

    private String imageUrl;

    @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 100, message = "Quantity cannot exceed 100")
    private int quantity;

    @NotNull(message = "unitPrice is required")
    @DecimalMin(value = "0.01", message = "Price must be positive")
    private BigDecimal unitPrice;
}

```

### `cart-service/src/main/java/com/retailstore/cart/api/rest/v1/dto/UpdateItemRequest.java`

```java
package com.retailstore.cart.api.rest.v1.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class UpdateItemRequest {
    @Min(value = 0, message = "Quantity cannot be negative")
    @Max(value = 100, message = "Quantity cannot exceed 100")
    private int quantity;
}

```

### `cart-service/src/main/java/com/retailstore/cart/api/rest/v1/dto/CartResponse.java`

```java
package com.retailstore.cart.api.rest.v1.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CartResponse {
    private String customerId;
    private List<CartItemResponse> items;
    private int totalItemCount;
    private int lineItemCount;
    private BigDecimal subtotal;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CartItemResponse {
        private String itemId;
        private String productId;
        private String productName;
        private String imageUrl;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }
}

```

### `cart-service/src/main/java/com/retailstore/cart/application/service/CartService.java`

```java
package com.retailstore.cart.application.service;

import com.retailstore.cart.api.rest.v1.dto.*;
import com.retailstore.cart.domain.exception.CartItemNotFoundException;
import com.retailstore.cart.domain.model.*;
import com.retailstore.cart.infrastructure.dynamodb.CartDynamoDbRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartDynamoDbRepository cartRepository;

    public CartResponse getCart(String customerId) {
        Cart cart = cartRepository.findByCustomerId(customerId)
            .orElseGet(() -> Cart.builder().customerId(customerId).items(new ArrayList<>()).build());
        return toResponse(cart);
    }

    public CartResponse addItem(String customerId, AddItemRequest request) {
        Cart cart = cartRepository.findByCustomerId(customerId)
            .orElseGet(() -> Cart.builder().customerId(customerId).items(new ArrayList<>()).build());

        List<CartItem> items = new ArrayList<>(cart.getItems());

        // If same product already in cart, increase quantity
        cart.findItemByProductId(request.getProductId()).ifPresentOrElse(
            existing -> existing.setQuantity(existing.getQuantity() + request.getQuantity()),
            () -> items.add(CartItem.builder()
                .itemId(UUID.randomUUID().toString())
                .productId(request.getProductId())
                .productName(request.getProductName())
                .imageUrl(request.getImageUrl())
                .quantity(request.getQuantity())
                .unitPrice(request.getUnitPrice())
                .build())
        );

        cart.setItems(items);
        cart.setLastModified(Instant.now());
        cartRepository.save(cart);

        log.info("Added item productId={} qty={} to cart customerId={}", request.getProductId(), request.getQuantity(), customerId);
        return toResponse(cart);
    }

    public CartResponse updateItem(String customerId, String itemId, UpdateItemRequest request) {
        Cart cart = cartRepository.findByCustomerId(customerId)
            .orElseGet(() -> Cart.builder().customerId(customerId).items(new ArrayList<>()).build());

        List<CartItem> items = new ArrayList<>(cart.getItems());

        CartItem item = cart.findItem(itemId)
            .orElseThrow(() -> new CartItemNotFoundException(itemId));

        if (request.getQuantity() <= 0) {
            items.remove(item);
            log.info("Removed item itemId={} from cart customerId={}", itemId, customerId);
        } else {
            item.setQuantity(request.getQuantity());
            log.info("Updated item itemId={} qty={} in cart customerId={}", itemId, request.getQuantity(), customerId);
        }

        cart.setItems(items);
        cart.setLastModified(Instant.now());
        cartRepository.save(cart);
        return toResponse(cart);
    }

    public void removeItem(String customerId, String itemId) {
        Cart cart = cartRepository.findByCustomerId(customerId)
            .orElseGet(() -> Cart.builder().customerId(customerId).items(new ArrayList<>()).build());

        boolean removed = cart.getItems().removeIf(i -> i.getItemId().equals(itemId));
        if (!removed) throw new CartItemNotFoundException(itemId);

        cart.setLastModified(Instant.now());
        cartRepository.save(cart);
    }

    public void clearCart(String customerId) {
        cartRepository.deleteByCustomerId(customerId);
        log.info("Cleared cart for customerId={}", customerId);
    }

    private CartResponse toResponse(Cart cart) {
        List<CartResponse.CartItemResponse> itemResponses = cart.getItems().stream()
            .map(i -> CartResponse.CartItemResponse.builder()
                .itemId(i.getItemId())
                .productId(i.getProductId())
                .productName(i.getProductName())
                .imageUrl(i.getImageUrl())
                .quantity(i.getQuantity())
                .unitPrice(i.getUnitPrice())
                .lineTotal(i.getLineTotal())
                .build())
            .collect(Collectors.toList());

        return CartResponse.builder()
            .customerId(cart.getCustomerId())
            .items(itemResponses)
            .totalItemCount(cart.getTotalItemCount())
            .lineItemCount(cart.getLineItemCount())
            .subtotal(cart.getSubtotal())
            .build();
    }
}

```

### `cart-service/src/main/java/com/retailstore/cart/api/rest/v1/controller/CartController.java`

```java
package com.retailstore.cart.api.rest.v1.controller;

import com.retailstore.cart.api.rest.v1.dto.*;
import com.retailstore.cart.application.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/carts/{customerId}")
@RequiredArgsConstructor
@Tag(name = "Cart", description = "Shopping cart — add, update, remove items")
public class CartController {

    private final CartService cartService;

    @GetMapping
    @Operation(summary = "Get cart for a customer")
    public ResponseEntity<CartResponse> getCart(@PathVariable String customerId) {
        return ResponseEntity.ok(cartService.getCart(customerId));
    }

    @PostMapping("/items")
    @Operation(summary = "Add item to cart (merges if product already exists)")
    public ResponseEntity<CartResponse> addItem(
            @PathVariable String customerId,
            @Valid @RequestBody AddItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cartService.addItem(customerId, request));
    }

    @PutMapping("/items/{itemId}")
    @Operation(summary = "Update item quantity (set to 0 to remove)")
    public ResponseEntity<CartResponse> updateItem(
            @PathVariable String customerId,
            @PathVariable String itemId,
            @Valid @RequestBody UpdateItemRequest request) {
        return ResponseEntity.ok(cartService.updateItem(customerId, itemId, request));
    }

    @DeleteMapping("/items/{itemId}")
    @Operation(summary = "Remove a specific item from cart")
    public ResponseEntity<Void> removeItem(
            @PathVariable String customerId,
            @PathVariable String itemId) {
        cartService.removeItem(customerId, itemId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(summary = "Clear entire cart")
    public ResponseEntity<Void> clearCart(@PathVariable String customerId) {
        cartService.clearCart(customerId);
        return ResponseEntity.noContent().build();
    }
}

```

### `cart-service/src/main/java/com/retailstore/cart/api/rest/v1/controller/GlobalExceptionHandler.java`

```java
package com.retailstore.cart.api.rest.v1.controller;

import com.retailstore.cart.domain.exception.CartItemNotFoundException;
import com.retailstore.cart.domain.exception.InvalidQuantityException;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CartItemNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(CartItemNotFoundException ex, HttpServletRequest req) {
        return error(404, "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(InvalidQuantityException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ErrorResponse handleInvalidQty(InvalidQuantityException ex, HttpServletRequest req) {
        return error(400, "Bad Request", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex, HttpServletRequest req) {
        return error(500, "Internal Server Error", "An unexpected error occurred", req.getRequestURI());
    }

    private ErrorResponse error(int status, String error, String message, String path) {
        return ErrorResponse.builder().status(status).error(error).message(message)
            .path(path).timestamp(Instant.now()).build();
    }

    @Getter @Builder
    public static class ErrorResponse {
        private int status; private String error; private String message;
        private String path; private Instant timestamp;
    }
}

```

---

## SOURCE: checkout-service


### `checkout-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.3.4</version></parent>
  <groupId>com.retailstore</groupId><artifactId>checkout-service</artifactId><version>1.0.0</version>
  <name>checkout-service</name>
  <description>RetailStore — Checkout domain: session management, price calculation, order handoff</description>
  <properties>
    <java.version>21</java.version>
    <lombok.version>1.18.34</lombok.version>
    <springdoc.version>2.6.0</springdoc.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-redis</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webflux</artifactId></dependency>
    <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId><version>${springdoc.version}</version></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
    <dependency><groupId>com.fasterxml.jackson.core</groupId><artifactId>jackson-databind</artifactId></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
        <configuration><source>${java.version}</source><target>${java.version}</target>
          <annotationProcessorPaths><path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path></annotationProcessorPaths>
        </configuration></plugin>
    </plugins>
  </build>
</project>

```

### `checkout-service/Dockerfile`

```
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /build/target/checkout-service-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-XX:+UseContainerSupport","-XX:MaxRAMPercentage=75.0","-XX:+ExitOnOutOfMemoryError","-jar","app.jar"]

```

### `checkout-service/src/main/resources/application.yml`

```yaml
server:
  port: 8080
  shutdown: graceful
spring:
  application:
    name: checkout-service
  lifecycle:
    timeout-per-shutdown-phase: 30s
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: ${REDIS_PORT:6379}
      timeout: 2000ms
      lettuce:
        pool:
          min-idle: 2
          max-idle: 8
          max-active: 16
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
logging:
  level:
    com.retailstore: INFO
retail:
  checkout:
    session:
      ttl-minutes: ${RETAIL_CHECKOUT_SESSION_TTL_MINUTES:30}
    pricing:
      tax-rate: ${RETAIL_CHECKOUT_TAX_RATE:0.20}
      shipping-cost: ${RETAIL_CHECKOUT_SHIPPING_COST:5.99}
      free-shipping-threshold: ${RETAIL_CHECKOUT_FREE_SHIPPING_THRESHOLD:50.00}
    endpoints:
      orders: ${RETAIL_CHECKOUT_ENDPOINTS_ORDERS:http://orders}

```

### `checkout-service/src/main/java/com/retailstore/checkout/CheckoutApplication.java`

```java
package com.retailstore.checkout;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class CheckoutApplication {
    public static void main(String[] args) { SpringApplication.run(CheckoutApplication.class, args); }
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/domain/model/CheckoutStatus.java`

```java
package com.retailstore.checkout.domain.model;

public enum CheckoutStatus {
    ACTIVE,       // session open, customer still filling in details
    SUBMITTED,    // order placed — session becomes read-only
    EXPIRED,      // TTL elapsed before order placed
    ABANDONED     // customer explicitly abandoned
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/domain/model/CheckoutSession.java`

```java
package com.retailstore.checkout.domain.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Checkout session — stored in Redis with TTL.
 * Holds everything the customer has committed to before placing the order.
 * Immutable once submitted (status = SUBMITTED).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CheckoutSession implements Serializable {

    private String sessionId;
    private String customerId;
    private CheckoutStatus status;

    @Builder.Default
    private List<CheckoutLineItem> lineItems = new ArrayList<>();

    private ShippingDetails shippingDetails;
    private PriceSummary priceSummary;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant expiresAt;

    private String submittedOrderId;

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isSubmittable() {
        return status == CheckoutStatus.ACTIVE
            && !isExpired()
            && lineItems != null && !lineItems.isEmpty()
            && shippingDetails != null;
    }

    // ── Nested value objects ────────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CheckoutLineItem implements Serializable {
        private String productId;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;

        public BigDecimal lineTotal() {
            return unitPrice != null ? unitPrice.multiply(BigDecimal.valueOf(quantity)) : BigDecimal.ZERO;
        }
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ShippingDetails implements Serializable {
        private String fullName;
        private String addressLine1;
        private String addressLine2;
        private String city;
        private String state;
        private String postalCode;
        private String country;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PriceSummary implements Serializable {
        private BigDecimal subtotal;
        private BigDecimal shippingCost;
        private BigDecimal taxAmount;
        private BigDecimal total;
    }
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/domain/exception/CheckoutSessionNotFoundException.java`

```java
package com.retailstore.checkout.domain.exception;
public class CheckoutSessionNotFoundException extends RuntimeException {
    public CheckoutSessionNotFoundException(String id) {
        super("Checkout session not found or expired: " + id);
    }
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/domain/exception/CheckoutSessionNotSubmittableException.java`

```java
package com.retailstore.checkout.domain.exception;
public class CheckoutSessionNotSubmittableException extends RuntimeException {
    public CheckoutSessionNotSubmittableException(String reason) {
        super("Checkout session cannot be submitted: " + reason);
    }
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/infrastructure/config/RedisConfig.java`

```java
package com.retailstore.checkout.infrastructure.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.retailstore.checkout.domain.model.CheckoutSession;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.serializer.*;

@Configuration
public class RedisConfig {

    @Bean
    public ObjectMapper checkoutObjectMapper() {
        return new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    }

    @Bean
    public RedisTemplate<String, CheckoutSession> checkoutSessionRedisTemplate(
            RedisConnectionFactory factory, ObjectMapper checkoutObjectMapper) {

        RedisTemplate<String, CheckoutSession> template = new RedisTemplate<>();
        template.setConnectionFactory(factory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(
            new GenericJackson2JsonRedisSerializer(checkoutObjectMapper));
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setHashValueSerializer(
            new GenericJackson2JsonRedisSerializer(checkoutObjectMapper));
        template.afterPropertiesSet();
        return template;
    }
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/infrastructure/redis/CheckoutSessionRepository.java`

```java
package com.retailstore.checkout.infrastructure.redis;

import com.retailstore.checkout.domain.model.CheckoutSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Repository;
import java.time.Duration;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CheckoutSessionRepository {

    private final RedisTemplate<String, CheckoutSession> redisTemplate;

    @Value("${retail.checkout.session.ttl-minutes:30}")
    private int ttlMinutes;

    private static final String KEY_PREFIX = "checkout:session:";

    public CheckoutSession save(CheckoutSession session) {
        String key = KEY_PREFIX + session.getSessionId();
        redisTemplate.opsForValue().set(key, session, Duration.ofMinutes(ttlMinutes));
        log.debug("Saved checkout session {} (TTL {}min)", session.getSessionId(), ttlMinutes);
        return session;
    }

    public Optional<CheckoutSession> findById(String sessionId) {
        CheckoutSession session = redisTemplate.opsForValue().get(KEY_PREFIX + sessionId);
        return Optional.ofNullable(session);
    }

    public void delete(String sessionId) {
        redisTemplate.delete(KEY_PREFIX + sessionId);
    }

    public boolean exists(String sessionId) {
        return Boolean.TRUE.equals(redisTemplate.hasKey(KEY_PREFIX + sessionId));
    }
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/infrastructure/client/OrderServiceClient.java`

```java
package com.retailstore.checkout.infrastructure.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import com.retailstore.checkout.domain.model.CheckoutSession;

/**
 * Calls order-service to place the order once checkout is submitted.
 * Checkout-service owns the session; order-service owns the permanent record.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OrderServiceClient {

    private final WebClient.Builder webClientBuilder;

    @Value("${retail.checkout.endpoints.orders:http://orders}")
    private String ordersEndpoint;

    public String placeOrder(CheckoutSession session) {
        WebClient client = webClientBuilder.baseUrl(ordersEndpoint).build();

        List<Map<String, Object>> lineItems = session.getLineItems().stream()
            .map(li -> Map.of(
                "productId",   (Object) li.getProductId(),
                "productName", li.getProductName(),
                "quantity",    li.getQuantity(),
                "unitPrice",   li.getUnitPrice().toString()
            ))
            .collect(Collectors.toList());

        CheckoutSession.ShippingDetails addr = session.getShippingDetails();
        Map<String, Object> shippingAddress = Map.of(
            "fullName",     addr.getFullName(),
            "addressLine1", addr.getAddressLine1(),
            "addressLine2", addr.getAddressLine2() != null ? addr.getAddressLine2() : "",
            "city",         addr.getCity(),
            "state",        addr.getState(),
            "postalCode",   addr.getPostalCode(),
            "country",      addr.getCountry()
        );

        CheckoutSession.PriceSummary prices = session.getPriceSummary();
        Map<String, Object> orderRequest = Map.of(
            "customerId",        session.getCustomerId(),
            "checkoutSessionId", session.getSessionId(),
            "lineItems",         lineItems,
            "shippingAddress",   shippingAddress,
            "subtotal",          prices.getSubtotal().toString(),
            "shippingCost",      prices.getShippingCost().toString(),
            "total",             prices.getTotal().toString()
        );

        Map<?, ?> response = client.post()
            .uri("/api/v1/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(orderRequest)
            .retrieve()
            .bodyToMono(Map.class)
            .block();

        String orderId = response != null ? String.valueOf(response.get("id")) : null;
        log.info("Order placed via order-service: orderId={} sessionId={}", orderId, session.getSessionId());
        return orderId;
    }
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/api/rest/v1/dto/CreateSessionRequest.java`

```java
package com.retailstore.checkout.api.rest.v1.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class CreateSessionRequest {

    @NotBlank(message = "customerId is required")
    private String customerId;

    @NotEmpty(message = "At least one line item is required")
    @Valid
    private List<LineItemDto> lineItems;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class LineItemDto {
        @NotBlank private String productId;
        @NotBlank private String productName;
        @Min(1)   private int quantity;
        @NotNull @DecimalMin("0.01") private BigDecimal unitPrice;
    }
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/api/rest/v1/dto/UpdateShippingRequest.java`

```java
package com.retailstore.checkout.api.rest.v1.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class UpdateShippingRequest {
    @NotBlank private String fullName;
    @NotBlank private String addressLine1;
    private String addressLine2;
    @NotBlank private String city;
    @NotBlank private String state;
    @NotBlank private String postalCode;
    @NotBlank private String country;
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/api/rest/v1/dto/CheckoutSessionResponse.java`

```java
package com.retailstore.checkout.api.rest.v1.dto;

import com.retailstore.checkout.domain.model.CheckoutSession;
import com.retailstore.checkout.domain.model.CheckoutStatus;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CheckoutSessionResponse {
    private String sessionId;
    private String customerId;
    private CheckoutStatus status;
    private List<LineItemDto> lineItems;
    private ShippingDto shippingDetails;
    private PriceSummaryDto priceSummary;
    private Instant createdAt;
    private Instant expiresAt;
    private boolean submittable;
    private String submittedOrderId;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LineItemDto {
        private String productId; private String productName;
        private int quantity; private BigDecimal unitPrice; private BigDecimal lineTotal;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ShippingDto {
        private String fullName; private String addressLine1; private String addressLine2;
        private String city; private String state; private String postalCode; private String country;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PriceSummaryDto {
        private BigDecimal subtotal; private BigDecimal shippingCost;
        private BigDecimal taxAmount; private BigDecimal total;
    }
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/application/service/CheckoutService.java`

```java
package com.retailstore.checkout.application.service;

import com.retailstore.checkout.api.rest.v1.dto.*;
import com.retailstore.checkout.domain.exception.*;
import com.retailstore.checkout.domain.model.*;
import com.retailstore.checkout.infrastructure.client.OrderServiceClient;
import com.retailstore.checkout.infrastructure.redis.CheckoutSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CheckoutService {

    private final CheckoutSessionRepository sessionRepository;
    private final OrderServiceClient orderServiceClient;

    // Standard UK/EU VAT rate — configurable per environment
    @Value("${retail.checkout.pricing.tax-rate:0.20}")
    private BigDecimal taxRate;

    @Value("${retail.checkout.pricing.shipping-cost:5.99}")
    private BigDecimal shippingCost;

    @Value("${retail.checkout.pricing.free-shipping-threshold:50.00}")
    private BigDecimal freeShippingThreshold;

    @Value("${retail.checkout.session.ttl-minutes:30}")
    private int ttlMinutes;

    /**
     * Create a new checkout session from the customer's cart items.
     * Calculates subtotal, shipping (free above threshold), and tax.
     */
    public CheckoutSessionResponse createSession(CreateSessionRequest request) {
        List<CheckoutSession.CheckoutLineItem> lineItems = request.getLineItems().stream()
            .map(li -> CheckoutSession.CheckoutLineItem.builder()
                .productId(li.getProductId())
                .productName(li.getProductName())
                .quantity(li.getQuantity())
                .unitPrice(li.getUnitPrice())
                .build())
            .collect(Collectors.toList());

        CheckoutSession.PriceSummary pricing = calculatePricing(lineItems);

        CheckoutSession session = CheckoutSession.builder()
            .sessionId(UUID.randomUUID().toString())
            .customerId(request.getCustomerId())
            .status(CheckoutStatus.ACTIVE)
            .lineItems(lineItems)
            .priceSummary(pricing)
            .createdAt(Instant.now())
            .expiresAt(Instant.now().plus(ttlMinutes, ChronoUnit.MINUTES))
            .build();

        sessionRepository.save(session);
        log.info("Checkout session created: sessionId={} customerId={} total={}",
            session.getSessionId(), session.getCustomerId(), pricing.getTotal());
        return toResponse(session);
    }

    /**
     * Retrieve an existing session. Returns 404 if expired or missing.
     */
    public CheckoutSessionResponse getSession(String sessionId) {
        CheckoutSession session = loadSession(sessionId);
        if (session.isExpired()) {
            session.setStatus(CheckoutStatus.EXPIRED);
            sessionRepository.save(session);
            throw new CheckoutSessionNotFoundException(sessionId);
        }
        return toResponse(session);
    }

    /**
     * Update shipping address and recalculate shipping cost if needed.
     */
    public CheckoutSessionResponse updateShipping(String sessionId, UpdateShippingRequest request) {
        CheckoutSession session = loadActiveSession(sessionId);

        session.setShippingDetails(CheckoutSession.ShippingDetails.builder()
            .fullName(request.getFullName())
            .addressLine1(request.getAddressLine1())
            .addressLine2(request.getAddressLine2())
            .city(request.getCity())
            .state(request.getState())
            .postalCode(request.getPostalCode())
            .country(request.getCountry())
            .build());

        sessionRepository.save(session);
        log.info("Shipping updated for sessionId={}", sessionId);
        return toResponse(session);
    }

    /**
     * Submit the checkout — validates session, calls order-service, marks SUBMITTED.
     * This is the critical business transaction.
     */
    public CheckoutSessionResponse submitCheckout(String sessionId) {
        CheckoutSession session = loadActiveSession(sessionId);

        if (!session.isSubmittable()) {
            String reason = session.getShippingDetails() == null
                ? "shipping address is missing"
                : "session is not in a valid state";
            throw new CheckoutSessionNotSubmittableException(reason);
        }

        // Call order-service — if this throws, session stays ACTIVE (retry is safe)
        String orderId = orderServiceClient.placeOrder(session);

        session.setStatus(CheckoutStatus.SUBMITTED);
        session.setSubmittedOrderId(orderId);
        sessionRepository.save(session);

        log.info("Checkout submitted: sessionId={} orderId={} customerId={}",
            sessionId, orderId, session.getCustomerId());
        return toResponse(session);
    }

    /**
     * Abandon a session explicitly (e.g. customer clicks "cancel").
     */
    public void abandonSession(String sessionId) {
        CheckoutSession session = loadSession(sessionId);
        session.setStatus(CheckoutStatus.ABANDONED);
        sessionRepository.save(session);
        log.info("Checkout session abandoned: sessionId={}", sessionId);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private CheckoutSession loadSession(String sessionId) {
        return sessionRepository.findById(sessionId)
            .orElseThrow(() -> new CheckoutSessionNotFoundException(sessionId));
    }

    private CheckoutSession loadActiveSession(String sessionId) {
        CheckoutSession session = loadSession(sessionId);
        if (session.isExpired() || session.getStatus() != CheckoutStatus.ACTIVE) {
            throw new CheckoutSessionNotFoundException(sessionId);
        }
        return session;
    }

    /**
     * Pricing rules:
     * - Subtotal = sum of all line totals
     * - Shipping = free if subtotal >= freeShippingThreshold, otherwise shippingCost
     * - Tax = 20% on subtotal (configurable)
     * - Total = subtotal + shipping + tax
     */
    private CheckoutSession.PriceSummary calculatePricing(
            List<CheckoutSession.CheckoutLineItem> items) {

        BigDecimal subtotal = items.stream()
            .map(CheckoutSession.CheckoutLineItem::lineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(2, RoundingMode.HALF_UP);

        BigDecimal shipping = subtotal.compareTo(freeShippingThreshold) >= 0
            ? BigDecimal.ZERO
            : shippingCost;

        BigDecimal tax = subtotal.multiply(taxRate).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(shipping).add(tax);

        return CheckoutSession.PriceSummary.builder()
            .subtotal(subtotal)
            .shippingCost(shipping)
            .taxAmount(tax)
            .total(total)
            .build();
    }

    private CheckoutSessionResponse toResponse(CheckoutSession s) {
        List<CheckoutSessionResponse.LineItemDto> items = s.getLineItems().stream()
            .map(li -> CheckoutSessionResponse.LineItemDto.builder()
                .productId(li.getProductId()).productName(li.getProductName())
                .quantity(li.getQuantity()).unitPrice(li.getUnitPrice())
                .lineTotal(li.lineTotal()).build())
            .collect(Collectors.toList());

        CheckoutSessionResponse.ShippingDto shipping = null;
        if (s.getShippingDetails() != null) {
            CheckoutSession.ShippingDetails sd = s.getShippingDetails();
            shipping = CheckoutSessionResponse.ShippingDto.builder()
                .fullName(sd.getFullName()).addressLine1(sd.getAddressLine1())
                .addressLine2(sd.getAddressLine2()).city(sd.getCity())
                .state(sd.getState()).postalCode(sd.getPostalCode())
                .country(sd.getCountry()).build();
        }

        CheckoutSessionResponse.PriceSummaryDto prices = null;
        if (s.getPriceSummary() != null) {
            CheckoutSession.PriceSummary p = s.getPriceSummary();
            prices = CheckoutSessionResponse.PriceSummaryDto.builder()
                .subtotal(p.getSubtotal()).shippingCost(p.getShippingCost())
                .taxAmount(p.getTaxAmount()).total(p.getTotal()).build();
        }

        return CheckoutSessionResponse.builder()
            .sessionId(s.getSessionId()).customerId(s.getCustomerId())
            .status(s.getStatus()).lineItems(items).shippingDetails(shipping)
            .priceSummary(prices).createdAt(s.getCreatedAt()).expiresAt(s.getExpiresAt())
            .submittable(s.isSubmittable()).submittedOrderId(s.getSubmittedOrderId())
            .build();
    }
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/api/rest/v1/controller/CheckoutController.java`

```java
package com.retailstore.checkout.api.rest.v1.controller;

import com.retailstore.checkout.api.rest.v1.dto.*;
import com.retailstore.checkout.application.service.CheckoutService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/checkout/sessions")
@RequiredArgsConstructor
@Tag(name = "Checkout", description = "Checkout sessions — price calculation, shipping, and order placement")
public class CheckoutController {

    private final CheckoutService checkoutService;

    @PostMapping
    @Operation(summary = "Create checkout session from cart items",
               description = "Calculates subtotal, shipping (free over $50), and 20% tax. Session expires in 30 min.")
    public ResponseEntity<CheckoutSessionResponse> createSession(
            @Valid @RequestBody CreateSessionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
            .body(checkoutService.createSession(request));
    }

    @GetMapping("/{sessionId}")
    @Operation(summary = "Get checkout session by ID")
    public ResponseEntity<CheckoutSessionResponse> getSession(@PathVariable String sessionId) {
        return ResponseEntity.ok(checkoutService.getSession(sessionId));
    }

    @PutMapping("/{sessionId}/shipping")
    @Operation(summary = "Update shipping address on the session")
    public ResponseEntity<CheckoutSessionResponse> updateShipping(
            @PathVariable String sessionId,
            @Valid @RequestBody UpdateShippingRequest request) {
        return ResponseEntity.ok(checkoutService.updateShipping(sessionId, request));
    }

    @PostMapping("/{sessionId}/submit")
    @Operation(summary = "Submit checkout — places the order via order-service",
               description = "Validates session is ACTIVE, shipping is set, then calls order-service. Returns the session with submittedOrderId.")
    public ResponseEntity<CheckoutSessionResponse> submitCheckout(@PathVariable String sessionId) {
        return ResponseEntity.ok(checkoutService.submitCheckout(sessionId));
    }

    @DeleteMapping("/{sessionId}")
    @Operation(summary = "Abandon a checkout session")
    public ResponseEntity<Void> abandonSession(@PathVariable String sessionId) {
        checkoutService.abandonSession(sessionId);
        return ResponseEntity.noContent().build();
    }
}

```

### `checkout-service/src/main/java/com/retailstore/checkout/api/rest/v1/controller/GlobalExceptionHandler.java`

```java
package com.retailstore.checkout.api.rest.v1.controller;

import com.retailstore.checkout.domain.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(CheckoutSessionNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(CheckoutSessionNotFoundException ex, HttpServletRequest req) {
        return error(404, "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(CheckoutSessionNotSubmittableException.class)
    @ResponseStatus(HttpStatus.UNPROCESSABLE_ENTITY)
    public ErrorResponse handleNotSubmittable(CheckoutSessionNotSubmittableException ex, HttpServletRequest req) {
        return error(422, "Unprocessable Entity", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex, HttpServletRequest req) {
        return error(500, "Internal Server Error", "An unexpected error occurred", req.getRequestURI());
    }

    private ErrorResponse error(int status, String error, String message, String path) {
        return ErrorResponse.builder().status(status).error(error)
            .message(message).path(path).timestamp(Instant.now()).build();
    }

    @Getter @Builder
    public static class ErrorResponse {
        private int status; private String error;
        private String message; private String path; private Instant timestamp;
    }
}

```

---

## SOURCE: order-service


### `order-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.3.4</version></parent>
  <groupId>com.retailstore</groupId><artifactId>order-service</artifactId><version>1.0.0</version>
  <name>order-service</name>
  <description>RetailStore — Order domain microservice (place, track, manage orders)</description>
  <properties>
    <java.version>21</java.version>
    <lombok.version>1.18.34</lombok.version>
    <springdoc.version>2.6.0</springdoc.version>
    <aws.sdk.version>2.28.0</aws.sdk.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>software.amazon.awssdk</groupId><artifactId>sqs</artifactId><version>${aws.sdk.version}</version></dependency>
    <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId><version>${springdoc.version}</version></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
        <configuration><source>${java.version}</source><target>${java.version}</target>
          <annotationProcessorPaths><path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path></annotationProcessorPaths>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>

```

### `order-service/Dockerfile`

```
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /build/target/order-service-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-XX:+UseContainerSupport","-XX:MaxRAMPercentage=75.0","-XX:+ExitOnOutOfMemoryError","-jar","app.jar"]

```

### `order-service/src/main/resources/application.yml`

```yaml
server:
  port: 8080
  shutdown: graceful
spring:
  application:
    name: order-service
  lifecycle:
    timeout-per-shutdown-phase: 30s
  datasource:
    url: jdbc:h2:mem:ordersdb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    open-in-view: false
    properties:
      hibernate:
        dialect: org.hibernate.dialect.H2Dialect
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
logging:
  level:
    com.retailstore: INFO
retail:
  order:
    messaging:
      enabled: ${RETAIL_ORDER_MESSAGING_ENABLED:false}
      sqs:
        queue-url: ${RETAIL_ORDER_MESSAGING_SQS_QUEUE_URL:}

```

### `order-service/src/main/resources/application-postgres.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${RETAIL_ORDER_DB_ENDPOINT}/${RETAIL_ORDER_DB_NAME:ordersdb}
    username: ${RETAIL_ORDER_DB_USER:orders_user}
    password: ${RETAIL_ORDER_DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      minimum-idle: 2
      maximum-pool-size: 20
      pool-name: OrderHikariPool
  jpa:
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

```

### `order-service/src/main/java/com/retailstore/order/OrderApplication.java`

```java
package com.retailstore.order;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class OrderApplication {
    public static void main(String[] args) { SpringApplication.run(OrderApplication.class, args); }
}

```

### `order-service/src/main/java/com/retailstore/order/domain/model/OrderStatus.java`

```java
package com.retailstore.order.domain.model;

public enum OrderStatus {
    PENDING,       // just placed
    CONFIRMED,     // payment captured
    PROCESSING,    // picking and packing
    SHIPPED,       // dispatched
    DELIVERED,     // confirmed delivered
    CANCELLED,     // cancelled before dispatch
    REFUNDED       // returned and refunded
}

```

### `order-service/src/main/java/com/retailstore/order/domain/model/ShippingAddress.java`

```java
package com.retailstore.order.domain.model;

import jakarta.persistence.Embeddable;
import lombok.*;

@Embeddable
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ShippingAddress {
    private String fullName;
    private String addressLine1;
    private String addressLine2;
    private String city;
    private String state;
    private String postalCode;
    private String country;
}

```

### `order-service/src/main/java/com/retailstore/order/domain/model/OrderLineItem.java`

```java
package com.retailstore.order.domain.model;

import jakarta.persistence.*;
import lombok.*;
import java.math.BigDecimal;

@Entity
@Table(name = "order_line_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderLineItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String productId;

    @Column(nullable = false, length = 255)
    private String productName;

    @Column(nullable = false)
    private int quantity;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal unitPrice;

    public BigDecimal getLineTotal() {
        return unitPrice != null ? unitPrice.multiply(BigDecimal.valueOf(quantity)) : BigDecimal.ZERO;
    }
}

```

### `order-service/src/main/java/com/retailstore/order/domain/model/Order.java`

```java
package com.retailstore.order.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders",
       indexes = {
           @Index(name = "idx_order_customer",    columnList = "customerId"),
           @Index(name = "idx_order_status",      columnList = "status"),
           @Index(name = "idx_order_created_at",  columnList = "createdAt")
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Order {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String customerId;

    @Column(length = 36)
    private String checkoutSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    @Builder.Default
    private List<OrderLineItem> lineItems = new ArrayList<>();

    @Embedded
    private ShippingAddress shippingAddress;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal shippingCost;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Column(length = 500)
    private String cancellationReason;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public boolean isCancellable() {
        return status == OrderStatus.PENDING || status == OrderStatus.CONFIRMED;
    }

    public boolean isShipped() {
        return status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED;
    }
}

```

### `order-service/src/main/java/com/retailstore/order/domain/exception/OrderNotFoundException.java`

```java
package com.retailstore.order.domain.exception;
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(String id) { super("Order not found: " + id); }
}

```

### `order-service/src/main/java/com/retailstore/order/domain/exception/OrderNotCancellableException.java`

```java
package com.retailstore.order.domain.exception;
public class OrderNotCancellableException extends RuntimeException {
    public OrderNotCancellableException(String id, String status) {
        super("Order " + id + " cannot be cancelled in status: " + status);
    }
}

```

### `order-service/src/main/java/com/retailstore/order/infrastructure/persistence/OrderJpaRepository.java`

```java
package com.retailstore.order.infrastructure.persistence;

import com.retailstore.order.domain.model.Order;
import com.retailstore.order.domain.model.OrderStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderJpaRepository extends JpaRepository<Order, String> {

    Page<Order> findByCustomerIdOrderByCreatedAtDesc(String customerId, Pageable pageable);

    Page<Order> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable pageable);

    @Query("SELECT COUNT(o) FROM Order o WHERE o.customerId = :customerId")
    long countByCustomerId(@Param("customerId") String customerId);
}

```

### `order-service/src/main/java/com/retailstore/order/infrastructure/messaging/OrderEventPublisher.java`

```java
package com.retailstore.order.infrastructure.messaging;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.retailstore.order.domain.model.Order;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;
import java.time.Instant;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderEventPublisher {

    private final SqsClient sqsClient;
    private final ObjectMapper objectMapper;

    @Value("${retail.order.messaging.sqs.queue-url:}")
    private String queueUrl;

    @Value("${retail.order.messaging.enabled:false}")
    private boolean messagingEnabled;

    public void publishOrderPlaced(Order order) {
        if (!messagingEnabled || queueUrl.isBlank()) {
            log.debug("SQS messaging disabled — skipping ORDER_PLACED event for order {}", order.getId());
            return;
        }
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "eventType",   "ORDER_PLACED",
                "orderId",     order.getId(),
                "customerId",  order.getCustomerId(),
                "total",       order.getTotal().toString(),
                "itemCount",   order.getLineItems().size(),
                "timestamp",   Instant.now().toString()
            ));
            sqsClient.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(payload)
                .messageGroupId("orders")
                .build());
            log.info("Published ORDER_PLACED event for order {}", order.getId());
        } catch (Exception e) {
            log.error("Failed to publish ORDER_PLACED event for order {} — non-fatal", order.getId(), e);
        }
    }

    public void publishOrderCancelled(Order order) {
        if (!messagingEnabled || queueUrl.isBlank()) return;
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                "eventType",  "ORDER_CANCELLED",
                "orderId",    order.getId(),
                "customerId", order.getCustomerId(),
                "reason",     order.getCancellationReason() != null ? order.getCancellationReason() : "",
                "timestamp",  Instant.now().toString()
            ));
            sqsClient.sendMessage(SendMessageRequest.builder().queueUrl(queueUrl).messageBody(payload).build());
        } catch (Exception e) {
            log.error("Failed to publish ORDER_CANCELLED event for order {}", order.getId(), e);
        }
    }
}

```

### `order-service/src/main/java/com/retailstore/order/api/rest/v1/dto/PlaceOrderRequest.java`

```java
package com.retailstore.order.api.rest.v1.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PlaceOrderRequest {

    @NotBlank(message = "customerId is required")
    private String customerId;

    private String checkoutSessionId;

    @NotEmpty(message = "At least one line item required")
    @Valid
    private List<LineItemRequest> lineItems;

    @Valid @NotNull
    private ShippingAddressRequest shippingAddress;

    @NotNull @DecimalMin("0.01")
    private BigDecimal subtotal;

    @NotNull @DecimalMin("0.00")
    private BigDecimal shippingCost;

    @NotNull @DecimalMin("0.01")
    private BigDecimal total;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class LineItemRequest {
        @NotBlank private String productId;
        @NotBlank private String productName;
        @Min(1) private int quantity;
        @NotNull @DecimalMin("0.01") private BigDecimal unitPrice;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class ShippingAddressRequest {
        @NotBlank private String fullName;
        @NotBlank private String addressLine1;
        private String addressLine2;
        @NotBlank private String city;
        @NotBlank private String state;
        @NotBlank private String postalCode;
        @NotBlank private String country;
    }
}

```

### `order-service/src/main/java/com/retailstore/order/api/rest/v1/dto/OrderResponse.java`

```java
package com.retailstore.order.api.rest.v1.dto;

import com.retailstore.order.domain.model.OrderStatus;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderResponse {
    private String id;
    private String customerId;
    private OrderStatus status;
    private List<LineItemResponse> lineItems;
    private ShippingAddressResponse shippingAddress;
    private BigDecimal subtotal;
    private BigDecimal shippingCost;
    private BigDecimal total;
    private boolean cancellable;
    private String cancellationReason;
    private Instant createdAt;
    private Instant updatedAt;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LineItemResponse {
        private String productId;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ShippingAddressResponse {
        private String fullName;
        private String addressLine1;
        private String addressLine2;
        private String city;
        private String state;
        private String postalCode;
        private String country;
    }
}

```

### `order-service/src/main/java/com/retailstore/order/application/service/OrderService.java`

```java
package com.retailstore.order.application.service;

import com.retailstore.order.api.rest.v1.dto.*;
import com.retailstore.order.domain.exception.*;
import com.retailstore.order.domain.model.*;
import com.retailstore.order.infrastructure.messaging.OrderEventPublisher;
import com.retailstore.order.infrastructure.persistence.OrderJpaRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class OrderService {

    private final OrderJpaRepository orderRepository;
    private final OrderEventPublisher eventPublisher;

    @Transactional
    public OrderResponse placeOrder(PlaceOrderRequest request) {
        var lineItems = request.getLineItems().stream()
            .map(li -> OrderLineItem.builder()
                .productId(li.getProductId())
                .productName(li.getProductName())
                .quantity(li.getQuantity())
                .unitPrice(li.getUnitPrice())
                .build())
            .collect(Collectors.toList());

        var addr = request.getShippingAddress();
        var shippingAddress = ShippingAddress.builder()
            .fullName(addr.getFullName())
            .addressLine1(addr.getAddressLine1())
            .addressLine2(addr.getAddressLine2())
            .city(addr.getCity())
            .state(addr.getState())
            .postalCode(addr.getPostalCode())
            .country(addr.getCountry())
            .build();

        var order = Order.builder()
            .id(UUID.randomUUID().toString())
            .customerId(request.getCustomerId())
            .checkoutSessionId(request.getCheckoutSessionId())
            .status(OrderStatus.PENDING)
            .lineItems(lineItems)
            .shippingAddress(shippingAddress)
            .subtotal(request.getSubtotal())
            .shippingCost(request.getShippingCost())
            .total(request.getTotal())
            .build();

        Order saved = orderRepository.save(order);
        eventPublisher.publishOrderPlaced(saved);
        log.info("Order placed — id={} customerId={} total={}", saved.getId(), saved.getCustomerId(), saved.getTotal());
        return toResponse(saved);
    }

    public OrderResponse getOrder(String orderId) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        return toResponse(order);
    }

    public Page<OrderResponse> getOrdersByCustomer(String customerId, int page, int size) {
        Pageable pageable = PageRequest.of(page, size);
        return orderRepository.findByCustomerIdOrderByCreatedAtDesc(customerId, pageable)
            .map(this::toResponse);
    }

    @Transactional
    public OrderResponse cancelOrder(String orderId, String reason) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));

        if (!order.isCancellable()) {
            throw new OrderNotCancellableException(orderId, order.getStatus().name());
        }

        order.setStatus(OrderStatus.CANCELLED);
        order.setCancellationReason(reason);
        Order saved = orderRepository.save(order);
        eventPublisher.publishOrderCancelled(saved);
        log.info("Order cancelled — id={} reason={}", orderId, reason);
        return toResponse(saved);
    }

    @Transactional
    public OrderResponse updateStatus(String orderId, OrderStatus newStatus) {
        Order order = orderRepository.findById(orderId)
            .orElseThrow(() -> new OrderNotFoundException(orderId));
        order.setStatus(newStatus);
        Order saved = orderRepository.save(order);
        log.info("Order {} status updated to {}", orderId, newStatus);
        return toResponse(saved);
    }

    private OrderResponse toResponse(Order o) {
        var items = o.getLineItems().stream()
            .map(li -> OrderResponse.LineItemResponse.builder()
                .productId(li.getProductId()).productName(li.getProductName())
                .quantity(li.getQuantity()).unitPrice(li.getUnitPrice())
                .lineTotal(li.getLineTotal()).build())
            .collect(Collectors.toList());

        var addr = o.getShippingAddress();
        OrderResponse.ShippingAddressResponse addrResp = null;
        if (addr != null) {
            addrResp = OrderResponse.ShippingAddressResponse.builder()
                .fullName(addr.getFullName()).addressLine1(addr.getAddressLine1())
                .addressLine2(addr.getAddressLine2()).city(addr.getCity())
                .state(addr.getState()).postalCode(addr.getPostalCode())
                .country(addr.getCountry()).build();
        }

        return OrderResponse.builder()
            .id(o.getId()).customerId(o.getCustomerId()).status(o.getStatus())
            .lineItems(items).shippingAddress(addrResp)
            .subtotal(o.getSubtotal()).shippingCost(o.getShippingCost()).total(o.getTotal())
            .cancellable(o.isCancellable()).cancellationReason(o.getCancellationReason())
            .createdAt(o.getCreatedAt()).updatedAt(o.getUpdatedAt())
            .build();
    }
}

```

### `order-service/src/main/java/com/retailstore/order/api/rest/v1/controller/OrderController.java`

```java
package com.retailstore.order.api.rest.v1.controller;

import com.retailstore.order.api.rest.v1.dto.*;
import com.retailstore.order.application.service.OrderService;
import com.retailstore.order.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/orders")
@RequiredArgsConstructor
@Tag(name = "Orders", description = "Order management — place, track, cancel orders")
public class OrderController {

    private final OrderService orderService;

    @PostMapping
    @Operation(summary = "Place a new order")
    public ResponseEntity<OrderResponse> placeOrder(@Valid @RequestBody PlaceOrderRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(orderService.placeOrder(request));
    }

    @GetMapping("/{orderId}")
    @Operation(summary = "Get order by ID")
    public ResponseEntity<OrderResponse> getOrder(@PathVariable String orderId) {
        return ResponseEntity.ok(orderService.getOrder(orderId));
    }

    @GetMapping("/customer/{customerId}")
    @Operation(summary = "Get order history for a customer (paginated, newest first)")
    public ResponseEntity<Page<OrderResponse>> getCustomerOrders(
            @PathVariable String customerId,
            @RequestParam(defaultValue = "0")  int page,
            @RequestParam(defaultValue = "10") int size) {
        return ResponseEntity.ok(orderService.getOrdersByCustomer(customerId, page, size));
    }

    @PostMapping("/{orderId}/cancel")
    @Operation(summary = "Cancel an order (only if PENDING or CONFIRMED)")
    public ResponseEntity<OrderResponse> cancelOrder(
            @PathVariable String orderId,
            @RequestParam(required = false, defaultValue = "Customer requested cancellation") String reason) {
        return ResponseEntity.ok(orderService.cancelOrder(orderId, reason));
    }

    @PatchMapping("/{orderId}/status")
    @Operation(summary = "Update order status (internal/admin use)")
    public ResponseEntity<OrderResponse> updateStatus(
            @PathVariable String orderId,
            @RequestParam OrderStatus status) {
        return ResponseEntity.ok(orderService.updateStatus(orderId, status));
    }
}

```

---

## SOURCE: identity-service


### `identity-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.3.4</version></parent>
  <groupId>com.retailstore</groupId><artifactId>identity-service</artifactId><version>1.0.0</version>
  <name>identity-service</name>
  <description>RetailStore — Identity domain: registration, login, JWT tokens, user profiles</description>
  <properties>
    <java.version>21</java.version>
    <lombok.version>1.18.34</lombok.version>
    <springdoc.version>2.6.0</springdoc.version>
    <jjwt.version>0.12.6</jjwt.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-data-jpa</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-security</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.postgresql</groupId><artifactId>postgresql</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>com.h2database</groupId><artifactId>h2</artifactId><scope>runtime</scope></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-api</artifactId><version>${jjwt.version}</version></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-impl</artifactId><version>${jjwt.version}</version><scope>runtime</scope></dependency>
    <dependency><groupId>io.jsonwebtoken</groupId><artifactId>jjwt-jackson</artifactId><version>${jjwt.version}</version><scope>runtime</scope></dependency>
    <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId><version>${springdoc.version}</version></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>org.springframework.security</groupId><artifactId>spring-security-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
        <configuration><source>${java.version}</source><target>${java.version}</target>
          <annotationProcessorPaths><path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path></annotationProcessorPaths>
        </configuration></plugin>
    </plugins>
  </build>
</project>

```

### `identity-service/Dockerfile`

```
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /build/target/identity-service-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-XX:+UseContainerSupport","-XX:MaxRAMPercentage=75.0","-XX:+ExitOnOutOfMemoryError","-jar","app.jar"]

```

### `identity-service/src/main/resources/application.yml`

```yaml
server:
  port: 8080
  shutdown: graceful
spring:
  application:
    name: identity-service
  lifecycle:
    timeout-per-shutdown-phase: 30s
  datasource:
    url: jdbc:h2:mem:identitydb;DB_CLOSE_DELAY=-1
    driver-class-name: org.h2.Driver
    username: sa
    password: ""
  jpa:
    hibernate:
      ddl-auto: update
    show-sql: false
    open-in-view: false
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
logging:
  level:
    com.retailstore: INFO
retail:
  identity:
    jwt:
      secret: ${RETAIL_IDENTITY_JWT_SECRET:bXlzdXBlcnNlY3JldGtleW15c3VwZXJzZWNyZXRrZXlteXN1cGVyc2VjcmV0a2V5bXlzdXBlcg==}
      expiration-ms: ${RETAIL_IDENTITY_JWT_EXPIRATION_MS:86400000}

```

### `identity-service/src/main/resources/application-postgres.yml`

```yaml
spring:
  datasource:
    url: jdbc:postgresql://${RETAIL_IDENTITY_DB_ENDPOINT}/${RETAIL_IDENTITY_DB_NAME:identitydb}
    username: ${RETAIL_IDENTITY_DB_USER:identity_user}
    password: ${RETAIL_IDENTITY_DB_PASSWORD}
    driver-class-name: org.postgresql.Driver
    hikari:
      minimum-idle: 2
      maximum-pool-size: 20
      pool-name: IdentityHikariPool
  jpa:
    properties:
      hibernate:
        dialect: org.hibernate.dialect.PostgreSQLDialect

```

### `identity-service/src/main/java/com/retailstore/identity/IdentityApplication.java`

```java
package com.retailstore.identity;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class IdentityApplication {
    public static void main(String[] args) { SpringApplication.run(IdentityApplication.class, args); }
}

```

### `identity-service/src/main/java/com/retailstore/identity/domain/model/UserRole.java`

```java
package com.retailstore.identity.domain.model;
public enum UserRole { CUSTOMER, ADMIN, SUPPORT }

```

### `identity-service/src/main/java/com/retailstore/identity/domain/model/UserAccount.java`

```java
package com.retailstore.identity.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "user_accounts",
       indexes = {
           @Index(name = "idx_user_email",    columnList = "email",    unique = true),
           @Index(name = "idx_user_username", columnList = "username", unique = true)
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserAccount {

    @Id
    @Column(length = 36)
    private String id;

    @Column(unique = true, nullable = false, length = 100)
    private String email;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 80)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserRole role = UserRole.CUSTOMER;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @CreationTimestamp private Instant createdAt;
    @UpdateTimestamp  private Instant updatedAt;

    public boolean isActive() { return enabled; }
}

```

### `identity-service/src/main/java/com/retailstore/identity/domain/exception/UserNotFoundException.java`

```java
package com.retailstore.identity.domain.exception;
public class UserNotFoundException extends RuntimeException {
    public UserNotFoundException(String msg) { super(msg); }
}

```

### `identity-service/src/main/java/com/retailstore/identity/domain/exception/UserAlreadyExistsException.java`

```java
package com.retailstore.identity.domain.exception;
public class UserAlreadyExistsException extends RuntimeException {
    public UserAlreadyExistsException(String field, String value) {
        super("User already exists with " + field + ": " + value);
    }
}

```

### `identity-service/src/main/java/com/retailstore/identity/domain/exception/InvalidCredentialsException.java`

```java
package com.retailstore.identity.domain.exception;
public class InvalidCredentialsException extends RuntimeException {
    public InvalidCredentialsException() { super("Invalid email or password"); }
}

```

### `identity-service/src/main/java/com/retailstore/identity/infrastructure/persistence/UserAccountRepository.java`

```java
package com.retailstore.identity.infrastructure.persistence;

import com.retailstore.identity.domain.model.UserAccount;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;

public interface UserAccountRepository extends JpaRepository<UserAccount, String> {
    Optional<UserAccount> findByEmail(String email);
    Optional<UserAccount> findByUsername(String username);
    boolean existsByEmail(String email);
    boolean existsByUsername(String username);
}

```

### `identity-service/src/main/java/com/retailstore/identity/infrastructure/security/JwtTokenProvider.java`

```java
package com.retailstore.identity.infrastructure.security;

import com.retailstore.identity.domain.model.UserAccount;
import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import javax.crypto.SecretKey;
import java.util.Base64;
import java.util.Date;

/**
 * Issues and validates JWT tokens.
 * Token contains: sub (userId), email, username, role — enough for downstream services
 * to authorise without calling identity-service on every request.
 */
@Slf4j
@Component
public class JwtTokenProvider {

    private final SecretKey signingKey;
    private final long expirationMs;

    public JwtTokenProvider(
            @Value("${retail.identity.jwt.secret}") String secret,
            @Value("${retail.identity.jwt.expiration-ms:86400000}") long expirationMs) {
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        this.signingKey = Keys.hmacShaKeyFor(keyBytes);
        this.expirationMs = expirationMs;
    }

    public String generateToken(UserAccount user) {
        Date now    = new Date();
        Date expiry = new Date(now.getTime() + expirationMs);

        return Jwts.builder()
            .subject(user.getId())
            .claim("email",    user.getEmail())
            .claim("username", user.getUsername())
            .claim("fullName", user.getFullName())
            .claim("role",     user.getRole().name())
            .issuedAt(now)
            .expiration(expiry)
            .signWith(signingKey)
            .compact();
    }

    public Claims validateAndParse(String token) {
        return Jwts.parser()
            .verifyWith(signingKey)
            .build()
            .parseSignedClaims(token)
            .getPayload();
    }

    public boolean isValid(String token) {
        try {
            validateAndParse(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            log.debug("Invalid JWT token: {}", e.getMessage());
            return false;
        }
    }
}

```

### `identity-service/src/main/java/com/retailstore/identity/infrastructure/config/SecurityConfig.java`

```java
package com.retailstore.identity.infrastructure.config;

import org.springframework.context.annotation.*;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(
                    "/api/v1/identity/register",
                    "/api/v1/identity/login",
                    "/actuator/**",
                    "/swagger-ui/**",
                    "/api-docs/**"
                ).permitAll()
                .anyRequest().authenticated()
            );
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(12);
    }
}

```

### `identity-service/src/main/java/com/retailstore/identity/api/rest/v1/dto/RegisterRequest.java`

```java
package com.retailstore.identity.api.rest.v1.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class RegisterRequest {
    @NotBlank @Email(message = "Must be a valid email")
    private String email;

    @NotBlank @Size(min = 3, max = 50, message = "Username must be 3-50 characters")
    private String username;

    @NotBlank @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank @Size(max = 80)
    private String fullName;
}

```

### `identity-service/src/main/java/com/retailstore/identity/api/rest/v1/dto/LoginRequest.java`

```java
package com.retailstore.identity.api.rest.v1.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class LoginRequest {
    @NotBlank private String email;
    @NotBlank private String password;
}

```

### `identity-service/src/main/java/com/retailstore/identity/api/rest/v1/dto/AuthResponse.java`

```java
package com.retailstore.identity.api.rest.v1.dto;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AuthResponse {
    private String accessToken;
    private String tokenType;
    private long expiresIn;
    private UserProfileResponse user;
}

```

### `identity-service/src/main/java/com/retailstore/identity/api/rest/v1/dto/UserProfileResponse.java`

```java
package com.retailstore.identity.api.rest.v1.dto;

import lombok.*;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserProfileResponse {
    private String id;
    private String email;
    private String username;
    private String fullName;
    private String role;
    private boolean emailVerified;
    private Instant createdAt;
}

```

### `identity-service/src/main/java/com/retailstore/identity/application/service/IdentityService.java`

```java
package com.retailstore.identity.application.service;

import com.retailstore.identity.api.rest.v1.dto.*;
import com.retailstore.identity.domain.exception.*;
import com.retailstore.identity.domain.model.UserAccount;
import com.retailstore.identity.infrastructure.persistence.UserAccountRepository;
import com.retailstore.identity.infrastructure.security.JwtTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class IdentityService {

    private final UserAccountRepository userRepository;
    private final JwtTokenProvider jwtTokenProvider;
    private final PasswordEncoder passwordEncoder;

    @Value("${retail.identity.jwt.expiration-ms:86400000}")
    private long expirationMs;

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        if (userRepository.existsByEmail(request.getEmail())) {
            throw new UserAlreadyExistsException("email", request.getEmail());
        }
        if (userRepository.existsByUsername(request.getUsername())) {
            throw new UserAlreadyExistsException("username", request.getUsername());
        }

        UserAccount user = UserAccount.builder()
            .id(UUID.randomUUID().toString())
            .email(request.getEmail().toLowerCase().trim())
            .username(request.getUsername().trim())
            .passwordHash(passwordEncoder.encode(request.getPassword()))
            .fullName(request.getFullName().trim())
            .build();

        UserAccount saved = userRepository.save(user);
        log.info("New user registered: id={} email={}", saved.getId(), saved.getEmail());

        String token = jwtTokenProvider.generateToken(saved);
        return buildAuthResponse(saved, token);
    }

    public AuthResponse login(LoginRequest request) {
        UserAccount user = userRepository.findByEmail(request.getEmail().toLowerCase().trim())
            .orElseThrow(InvalidCredentialsException::new);

        if (!user.isActive()) {
            throw new InvalidCredentialsException();
        }

        if (!passwordEncoder.matches(request.getPassword(), user.getPasswordHash())) {
            log.warn("Failed login attempt for email={}", request.getEmail());
            throw new InvalidCredentialsException();
        }

        String token = jwtTokenProvider.generateToken(user);
        log.info("User logged in: id={} email={}", user.getId(), user.getEmail());
        return buildAuthResponse(user, token);
    }

    public UserProfileResponse getProfile(String userId) {
        UserAccount user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
        return toProfileResponse(user);
    }

    public AuthResponse refreshToken(String userId) {
        UserAccount user = userRepository.findById(userId)
            .orElseThrow(() -> new UserNotFoundException("User not found: " + userId));
        String token = jwtTokenProvider.generateToken(user);
        return buildAuthResponse(user, token);
    }

    private AuthResponse buildAuthResponse(UserAccount user, String token) {
        return AuthResponse.builder()
            .accessToken(token)
            .tokenType("Bearer")
            .expiresIn(expirationMs / 1000)
            .user(toProfileResponse(user))
            .build();
    }

    private UserProfileResponse toProfileResponse(UserAccount u) {
        return UserProfileResponse.builder()
            .id(u.getId()).email(u.getEmail()).username(u.getUsername())
            .fullName(u.getFullName()).role(u.getRole().name())
            .emailVerified(u.isEmailVerified()).createdAt(u.getCreatedAt())
            .build();
    }
}

```

### `identity-service/src/main/java/com/retailstore/identity/api/rest/v1/controller/IdentityController.java`

```java
package com.retailstore.identity.api.rest.v1.controller;

import com.retailstore.identity.api.rest.v1.dto.*;
import com.retailstore.identity.application.service.IdentityService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/identity")
@RequiredArgsConstructor
@Tag(name = "Identity", description = "Registration, login, JWT tokens, user profiles")
public class IdentityController {

    private final IdentityService identityService;

    @PostMapping("/register")
    @Operation(summary = "Register a new customer account")
    public ResponseEntity<AuthResponse> register(@Valid @RequestBody RegisterRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(identityService.register(request));
    }

    @PostMapping("/login")
    @Operation(summary = "Login with email and password — returns JWT")
    public ResponseEntity<AuthResponse> login(@Valid @RequestBody LoginRequest request) {
        return ResponseEntity.ok(identityService.login(request));
    }

    @GetMapping("/profile/{userId}")
    @Operation(summary = "Get user profile by ID")
    public ResponseEntity<UserProfileResponse> getProfile(@PathVariable String userId) {
        return ResponseEntity.ok(identityService.getProfile(userId));
    }

    @PostMapping("/refresh/{userId}")
    @Operation(summary = "Refresh JWT token for a user")
    public ResponseEntity<AuthResponse> refresh(@PathVariable String userId) {
        return ResponseEntity.ok(identityService.refreshToken(userId));
    }
}

```

### `identity-service/src/main/java/com/retailstore/identity/api/rest/v1/controller/GlobalExceptionHandler.java`

```java
package com.retailstore.identity.api.rest.v1.controller;

import com.retailstore.identity.domain.exception.*;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Builder;
import lombok.Getter;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(UserNotFoundException.class)
    @ResponseStatus(HttpStatus.NOT_FOUND)
    public ErrorResponse handleNotFound(UserNotFoundException ex, HttpServletRequest req) {
        return error(404, "Not Found", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(UserAlreadyExistsException.class)
    @ResponseStatus(HttpStatus.CONFLICT)
    public ErrorResponse handleConflict(UserAlreadyExistsException ex, HttpServletRequest req) {
        return error(409, "Conflict", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    @ResponseStatus(HttpStatus.UNAUTHORIZED)
    public ErrorResponse handleUnauthorized(InvalidCredentialsException ex, HttpServletRequest req) {
        return error(401, "Unauthorized", ex.getMessage(), req.getRequestURI());
    }

    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ErrorResponse handleGeneral(Exception ex, HttpServletRequest req) {
        return error(500, "Internal Server Error", "An unexpected error occurred", req.getRequestURI());
    }

    private ErrorResponse error(int status, String error, String message, String path) {
        return ErrorResponse.builder().status(status).error(error)
            .message(message).path(path).timestamp(Instant.now()).build();
    }

    @Getter @Builder
    public static class ErrorResponse {
        private int status; private String error;
        private String message; private String path; private Instant timestamp;
    }
}

```

---

## SOURCE: experience-service


### `experience-service/pom.xml`

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
  <modelVersion>4.0.0</modelVersion>
  <parent><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-parent</artifactId><version>3.3.4</version></parent>
  <groupId>com.retailstore</groupId><artifactId>experience-service</artifactId><version>1.0.0</version>
  <name>experience-service</name>
  <description>RetailStore — Experience layer (BFF): aggregates data, shapes response per client channel</description>
  <properties>
    <java.version>21</java.version>
    <lombok.version>1.18.34</lombok.version>
    <springdoc.version>2.6.0</springdoc.version>
  </properties>
  <dependencies>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-webflux</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-web</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-actuator</artifactId></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-validation</artifactId></dependency>
    <dependency><groupId>org.springdoc</groupId><artifactId>springdoc-openapi-starter-webmvc-ui</artifactId><version>${springdoc.version}</version></dependency>
    <dependency><groupId>io.micrometer</groupId><artifactId>micrometer-registry-prometheus</artifactId></dependency>
    <dependency><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><optional>true</optional></dependency>
    <dependency><groupId>org.springframework.boot</groupId><artifactId>spring-boot-starter-test</artifactId><scope>test</scope></dependency>
    <dependency><groupId>io.projectreactor</groupId><artifactId>reactor-test</artifactId><scope>test</scope></dependency>
  </dependencies>
  <build>
    <plugins>
      <plugin><groupId>org.springframework.boot</groupId><artifactId>spring-boot-maven-plugin</artifactId></plugin>
      <plugin><groupId>org.apache.maven.plugins</groupId><artifactId>maven-compiler-plugin</artifactId>
        <configuration><source>${java.version}</source><target>${java.version}</target>
          <annotationProcessorPaths><path><groupId>org.projectlombok</groupId><artifactId>lombok</artifactId><version>${lombok.version}</version></path></annotationProcessorPaths>
        </configuration>
      </plugin>
    </plugins>
  </build>
</project>

```

### `experience-service/Dockerfile`

```
FROM eclipse-temurin:21-jdk-alpine AS builder
WORKDIR /build
COPY pom.xml .
COPY src ./src
RUN --mount=type=cache,target=/root/.m2 ./mvnw -B clean package -DskipTests -q

FROM eclipse-temurin:21-jre-alpine AS runtime
WORKDIR /app
RUN addgroup -S appgroup && adduser -S appuser -G appgroup
COPY --from=builder /build/target/experience-service-*.jar app.jar
RUN chown appuser:appgroup app.jar
USER appuser
EXPOSE 8080
ENTRYPOINT ["java","-XX:+UseContainerSupport","-XX:MaxRAMPercentage=75.0","-XX:+ExitOnOutOfMemoryError","-jar","app.jar"]

```

### `experience-service/src/main/resources/application.yml`

```yaml
server:
  port: 8080
  shutdown: graceful
spring:
  application:
    name: experience-service
  lifecycle:
    timeout-per-shutdown-phase: 30s
  main:
    web-application-type: reactive
management:
  endpoints:
    web:
      exposure:
        include: health,info,metrics,prometheus
  endpoint:
    health:
      show-details: always
      probes:
        enabled: true
  health:
    livenessstate:
      enabled: true
    readinessstate:
      enabled: true
logging:
  level:
    com.retailstore: INFO
    reactor.netty: WARN
retail:
  experience:
    endpoints:
      catalog: ${RETAIL_EXPERIENCE_ENDPOINTS_CATALOG:http://catalog}
      carts:   ${RETAIL_EXPERIENCE_ENDPOINTS_CARTS:http://carts}
      orders:  ${RETAIL_EXPERIENCE_ENDPOINTS_ORDERS:http://orders}

```

### `experience-service/src/main/java/com/retailstore/experience/ExperienceApplication.java`

```java
package com.retailstore.experience;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
@SpringBootApplication
public class ExperienceApplication {
    public static void main(String[] args) { SpringApplication.run(ExperienceApplication.class, args); }
}

```

### `experience-service/src/main/java/com/retailstore/experience/api/rest/v1/shaper/ClientChannel.java`

```java
package com.retailstore.experience.api.rest.v1.shaper;

/**
 * Represents the client type requesting data.
 * The experience layer detects this from the X-Client-Channel header
 * and shapes responses accordingly — more fields for web, fewer for mobile.
 */
public enum ClientChannel {
    WEB,       // full rich response
    MOBILE,    // lean payload, no large descriptions
    TABLET,    // similar to web, slightly reduced
    UNKNOWN;   // defaults to web behaviour

    public static ClientChannel from(String header) {
        if (header == null || header.isBlank()) return UNKNOWN;
        try { return valueOf(header.trim().toUpperCase()); }
        catch (IllegalArgumentException e) { return UNKNOWN; }
    }
}

```

### `experience-service/src/main/java/com/retailstore/experience/api/rest/v1/dto/HomepageResponse.java`

```java
package com.retailstore.experience.api.rest.v1.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregated homepage response — parallel calls to:
 * - catalog-service (featured products, tags)
 * - cart-service (badge count)
 * Combined into one response to save the client multiple round-trips.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class HomepageResponse {
    private List<FeaturedProduct> featuredProducts;
    private List<TagSummary> availableTags;
    private int cartItemCount;           // badge on nav icon

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class FeaturedProduct {
        private String id;
        private String name;
        private String description;
        private BigDecimal price;
        private boolean inStock;
        private List<String> tagNames;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class TagSummary {
        private String name;
        private String displayName;
    }
}

```

### `experience-service/src/main/java/com/retailstore/experience/api/rest/v1/dto/ProductPageResponse.java`

```java
package com.retailstore.experience.api.rest.v1.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

/**
 * Aggregated product page response — combines data from catalog-service.
 * The shaper trims fields based on the client channel.
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class ProductPageResponse {
    private String id;
    private String name;
    private String description;          // omitted for mobile
    private BigDecimal price;
    private boolean inStock;
    private List<String> tagNames;
    private List<ProductSummary> relatedProducts;  // omitted for mobile

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ProductSummary {
        private String id;
        private String name;
        private BigDecimal price;
        private boolean inStock;
    }
}

```

### `experience-service/src/main/java/com/retailstore/experience/api/rest/v1/dto/CartSummaryResponse.java`

```java
package com.retailstore.experience.api.rest.v1.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CartSummaryResponse {
    private String customerId;
    private List<CartLineItem> items;
    private int totalItemCount;
    private BigDecimal subtotal;
    private BigDecimal estimatedShipping;
    private BigDecimal estimatedTotal;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CartLineItem {
        private String itemId;
        private String productId;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }
}

```

### `experience-service/src/main/java/com/retailstore/experience/infrastructure/config/WebClientConfig.java`

```java
package com.retailstore.experience.infrastructure.config;

import io.netty.channel.ChannelOption;
import io.netty.handler.timeout.ReadTimeoutHandler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.*;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.*;
import reactor.netty.http.client.HttpClient;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

@Slf4j
@Configuration
public class WebClientConfig {

    @Bean
    public WebClient.Builder webClientBuilder() {
        HttpClient httpClient = HttpClient.create()
            .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 3000)
            .responseTimeout(Duration.ofSeconds(5))
            .doOnConnected(conn ->
                conn.addHandlerLast(new ReadTimeoutHandler(5, TimeUnit.SECONDS)));

        return WebClient.builder()
            .clientConnector(new ReactorClientHttpConnector(httpClient))
            .filter(ExchangeFilterFunction.ofRequestProcessor(req -> {
                log.debug("→ {} {}", req.method(), req.url());
                return reactor.core.publisher.Mono.just(req);
            }));
    }

    @Bean("catalogClient")
    public WebClient catalogClient(WebClient.Builder builder,
            @Value("${retail.experience.endpoints.catalog:http://catalog}") String url) {
        return builder.baseUrl(url).build();
    }

    @Bean("cartClient")
    public WebClient cartClient(WebClient.Builder builder,
            @Value("${retail.experience.endpoints.carts:http://carts}") String url) {
        return builder.baseUrl(url).build();
    }

    @Bean("orderClient")
    public WebClient orderClient(WebClient.Builder builder,
            @Value("${retail.experience.endpoints.orders:http://orders}") String url) {
        return builder.baseUrl(url).build();
    }
}

```

### `experience-service/src/main/java/com/retailstore/experience/infrastructure/client/CatalogClient.java`

```java
package com.retailstore.experience.infrastructure.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CatalogClient {

    @Qualifier("catalogClient")
    private final WebClient webClient;

    public Mono<Map> getProducts(int page, int size, String tags) {
        return webClient.get()
            .uri(u -> u.path("/api/v1/catalog/products")
                .queryParam("page", page).queryParam("size", size)
                .queryParamIfPresent("tags", java.util.Optional.ofNullable(tags))
                .build())
            .retrieve()
            .bodyToMono(Map.class)
            .doOnError(e -> log.error("catalog getProducts error", e))
            .onErrorReturn(Map.of("products", java.util.List.of()));
    }

    public Mono<Map> getProduct(String id) {
        return webClient.get()
            .uri("/api/v1/catalog/products/{id}", id)
            .retrieve()
            .bodyToMono(Map.class)
            .doOnError(e -> log.error("catalog getProduct {} error", id, e));
    }

    public Mono<java.util.List> getTags() {
        return webClient.get()
            .uri("/api/v1/catalog/tags")
            .retrieve()
            .bodyToMono(java.util.List.class)
            .onErrorReturn(java.util.List.of());
    }
}

```

### `experience-service/src/main/java/com/retailstore/experience/infrastructure/client/CartClient.java`

```java
package com.retailstore.experience.infrastructure.client;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class CartClient {

    @Qualifier("cartClient")
    private final WebClient webClient;

    public Mono<Map> getCart(String customerId) {
        return webClient.get()
            .uri("/api/v1/carts/{customerId}", customerId)
            .retrieve()
            .bodyToMono(Map.class)
            .doOnError(e -> log.error("cart getCart {} error", customerId, e))
            .onErrorReturn(Map.of("customerId", customerId, "items", java.util.List.of(),
                "totalItemCount", 0, "subtotal", "0"));
    }

    public Mono<Map> addItem(String customerId, Map<String, Object> item) {
        return webClient.post()
            .uri("/api/v1/carts/{customerId}/items", customerId)
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(item)
            .retrieve()
            .bodyToMono(Map.class);
    }
}

```

### `experience-service/src/main/java/com/retailstore/experience/application/aggregator/HomepageAggregator.java`

```java
package com.retailstore.experience.application.aggregator;

import com.retailstore.experience.api.rest.v1.dto.HomepageResponse;
import com.retailstore.experience.infrastructure.client.CartClient;
import com.retailstore.experience.infrastructure.client.CatalogClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Fires parallel calls to catalog-service and cart-service,
 * then merges results into a single HomepageResponse.
 * One network round-trip for the client instead of three.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class HomepageAggregator {

    private final CatalogClient catalogClient;
    private final CartClient cartClient;

    public Mono<HomepageResponse> aggregate(String customerId, int featuredCount) {
        Mono<Map> productsMono = catalogClient.getProducts(0, featuredCount, null);
        Mono<List> tagsMono    = catalogClient.getTags();
        Mono<Map> cartMono     = cartClient.getCart(customerId);

        // Parallel execution — all three calls fire simultaneously
        return Mono.zip(productsMono, tagsMono, cartMono)
            .map(tuple -> {
                Map productsData  = tuple.getT1();
                List tagsData     = tuple.getT2();
                Map cartData      = tuple.getT3();

                List<Map> products = (List<Map>) productsData.getOrDefault("products", List.of());
                int cartCount      = (int) cartData.getOrDefault("totalItemCount", 0);

                List<HomepageResponse.FeaturedProduct> featured = products.stream()
                    .map(p -> HomepageResponse.FeaturedProduct.builder()
                        .id(String.valueOf(p.get("id")))
                        .name(String.valueOf(p.get("name")))
                        .description(String.valueOf(p.get("description")))
                        .price(new BigDecimal(String.valueOf(p.getOrDefault("price", "0"))))
                        .inStock(Boolean.TRUE.equals(p.get("inStock")))
                        .tagNames(extractTagNames(p))
                        .build())
                    .collect(Collectors.toList());

                List<HomepageResponse.TagSummary> tags = tagsData.stream()
                    .map(t -> {
                        Map tm = (Map) t;
                        return HomepageResponse.TagSummary.builder()
                            .name(String.valueOf(tm.get("name")))
                            .displayName(String.valueOf(tm.get("displayName")))
                            .build();
                    })
                    .collect(Collectors.toList());

                return HomepageResponse.builder()
                    .featuredProducts(featured)
                    .availableTags(tags)
                    .cartItemCount(cartCount)
                    .build();
            })
            .doOnError(e -> log.error("HomepageAggregator error", e));
    }

    @SuppressWarnings("unchecked")
    private List<String> extractTagNames(Map product) {
        Object tags = product.get("tags");
        if (tags instanceof List<?> tagList) {
            return tagList.stream()
                .map(t -> t instanceof Map ? String.valueOf(((Map) t).get("name")) : "")
                .filter(s -> !s.isBlank())
                .collect(Collectors.toList());
        }
        return List.of();
    }
}

```

### `experience-service/src/main/java/com/retailstore/experience/api/rest/v1/controller/ExperienceController.java`

```java
package com.retailstore.experience.api.rest.v1.controller;

import com.retailstore.experience.api.rest.v1.dto.*;
import com.retailstore.experience.api.rest.v1.shaper.ClientChannel;
import com.retailstore.experience.application.aggregator.HomepageAggregator;
import com.retailstore.experience.infrastructure.client.*;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;
import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/api/v1/experience")
@RequiredArgsConstructor
@Tag(name = "Experience", description = "Aggregated endpoints shaped per client channel — one call returns all page data")
public class ExperienceController {

    private final HomepageAggregator homepageAggregator;
    private final CatalogClient catalogClient;
    private final CartClient cartClient;

    /**
     * Homepage endpoint — fires parallel calls to catalog + cart.
     * Returns featured products, tags, and cart badge count in one response.
     * Mobile channel receives fewer fields (no description).
     */
    @GetMapping("/homepage")
    @Operation(summary = "Homepage — aggregated products + tags + cart badge count")
    public Mono<ResponseEntity<HomepageResponse>> homepage(
            @RequestParam(defaultValue = "guest") String customerId,
            @RequestParam(defaultValue = "8") int featuredCount,
            @Parameter(description = "Client channel: WEB, MOBILE, TABLET")
            @RequestHeader(value = "X-Client-Channel", required = false, defaultValue = "WEB") String channel) {

        ClientChannel clientChannel = ClientChannel.from(channel);
        log.debug("Homepage request — customerId={} channel={}", customerId, clientChannel);

        return homepageAggregator.aggregate(customerId, featuredCount)
            .map(response -> {
                // Mobile: strip descriptions from featured products
                if (clientChannel == ClientChannel.MOBILE) {
                    response.setFeaturedProducts(response.getFeaturedProducts().stream()
                        .map(p -> HomepageResponse.FeaturedProduct.builder()
                            .id(p.getId()).name(p.getName()).price(p.getPrice())
                            .inStock(p.isInStock()).tagNames(p.getTagNames())
                            .build()) // description omitted
                        .collect(Collectors.toList()));
                }
                return ResponseEntity.ok(response);
            })
            .onErrorReturn(ResponseEntity.internalServerError().build());
    }

    /**
     * Product detail page — catalog data only, but structured for display.
     * Extensible: add review-service or recommendation-service here without changing the API.
     */
    @GetMapping("/products/{id}")
    @Operation(summary = "Product detail page — single product with full data")
    public Mono<ResponseEntity<Map>> productDetail(
            @PathVariable String id,
            @RequestHeader(value = "X-Client-Channel", required = false, defaultValue = "WEB") String channel) {

        ClientChannel clientChannel = ClientChannel.from(channel);
        return catalogClient.getProduct(id)
            .map(product -> {
                // Mobile: strip heavy fields
                if (clientChannel == ClientChannel.MOBILE) {
                    Map<String, Object> lean = new LinkedHashMap<>();
                    lean.put("id",       product.get("id"));
                    lean.put("name",     product.get("name"));
                    lean.put("price",    product.get("price"));
                    lean.put("inStock",  product.get("inStock"));
                    lean.put("tags",     product.get("tags"));
                    return ResponseEntity.ok((Map) lean);
                }
                return ResponseEntity.ok(product);
            })
            .onErrorReturn(ResponseEntity.notFound().build());
    }

    /**
     * Cart summary enriched with estimated totals.
     * Adds shipping estimate so the client displays the full cost breakdown.
     */
    @GetMapping("/cart/{customerId}/summary")
    @Operation(summary = "Cart summary with estimated shipping and total")
    public Mono<ResponseEntity<CartSummaryResponse>> cartSummary(@PathVariable String customerId) {
        return cartClient.getCart(customerId)
            .map(cart -> {
                List<Map> rawItems = (List<Map>) cart.getOrDefault("items", List.of());
                List<CartSummaryResponse.CartLineItem> items = rawItems.stream()
                    .map(i -> CartSummaryResponse.CartLineItem.builder()
                        .itemId(String.valueOf(i.get("itemId")))
                        .productId(String.valueOf(i.get("productId")))
                        .productName(String.valueOf(i.get("productName")))
                        .quantity((int) i.getOrDefault("quantity", 0))
                        .unitPrice(new BigDecimal(String.valueOf(i.getOrDefault("unitPrice", "0"))))
                        .lineTotal(new BigDecimal(String.valueOf(i.getOrDefault("lineTotal", "0"))))
                        .build())
                    .collect(Collectors.toList());

                BigDecimal subtotal = new BigDecimal(String.valueOf(cart.getOrDefault("subtotal", "0")));
                BigDecimal shipping = subtotal.compareTo(BigDecimal.ZERO) > 0
                    ? new BigDecimal("5.99") : BigDecimal.ZERO;

                return ResponseEntity.ok(CartSummaryResponse.builder()
                    .customerId(customerId)
                    .items(items)
                    .totalItemCount((int) cart.getOrDefault("totalItemCount", 0))
                    .subtotal(subtotal)
                    .estimatedShipping(shipping)
                    .estimatedTotal(subtotal.add(shipping))
                    .build());
            });
    }
}

```

---

## INFRA: retailstore-platform — docker-compose


### `retailstore-platform/docker-compose.yml`

```yaml
# RetailStore — local development stack
# Starts all 7 services + their infrastructure dependencies.
# No AWS account needed. Uses local substitutes for DynamoDB, Redis, PostgreSQL.
#
# Usage:
#   docker compose up           # start everything
#   docker compose up catalog   # start one service
#   docker compose logs -f      # tail all logs

version: "3.9"

networks:
  retailstore:
    driver: bridge

volumes:
  postgres-catalog:
  postgres-orders:
  postgres-identity:
  dynamodb-data:
  redis-data:

services:

  # ── Infrastructure ──────────────────────────────────────────────────────────

  dynamodb-local:
    image: amazon/dynamodb-local:2.5.2
    container_name: dynamodb-local
    command: ["-jar", "DynamoDBLocal.jar", "-sharedDb", "-inMemory"]
    ports: ["8000:8000"]
    networks: [retailstore]
    healthcheck:
      test: ["CMD-SHELL", "curl -s http://localhost:8000/shell/ | grep -q DynamoDB"]
      interval: 10s
      timeout: 5s
      retries: 5

  dynamodb-init:
    image: amazon/aws-cli:2.17.0
    container_name: dynamodb-init
    depends_on:
      dynamodb-local:
        condition: service_healthy
    environment:
      AWS_ACCESS_KEY_ID: dummy
      AWS_SECRET_ACCESS_KEY: dummy
      AWS_DEFAULT_REGION: us-east-1
    command: >
      dynamodb create-table
      --table-name Carts
      --attribute-definitions AttributeName=customerId,AttributeType=S
      --key-schema AttributeName=customerId,KeyType=HASH
      --billing-mode PAY_PER_REQUEST
      --endpoint-url http://dynamodb-local:8000
    networks: [retailstore]

  redis:
    image: redis:7.4-alpine
    container_name: redis
    ports: ["6379:6379"]
    volumes: [redis-data:/data]
    networks: [retailstore]
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 3s
      retries: 5

  postgres-orders:
    image: postgres:16-alpine
    container_name: postgres-orders
    environment:
      POSTGRES_DB: ordersdb
      POSTGRES_USER: orders_user
      POSTGRES_PASSWORD: orders_pass
    ports: ["5432:5432"]
    volumes: [postgres-orders:/var/lib/postgresql/data]
    networks: [retailstore]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U orders_user -d ordersdb"]
      interval: 5s
      timeout: 3s
      retries: 10

  postgres-identity:
    image: postgres:16-alpine
    container_name: postgres-identity
    environment:
      POSTGRES_DB: identitydb
      POSTGRES_USER: identity_user
      POSTGRES_PASSWORD: identity_pass
    ports: ["5433:5432"]
    volumes: [postgres-identity:/var/lib/postgresql/data]
    networks: [retailstore]
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U identity_user -d identitydb"]
      interval: 5s
      timeout: 3s
      retries: 10

  # ── Domain Services ─────────────────────────────────────────────────────────

  catalog:
    build:
      context: ../catalog-service
      dockerfile: Dockerfile
    container_name: catalog
    environment:
      RETAIL_CATALOG_PERSISTENCE_PROVIDER: in-memory
      SPRING_PROFILES_ACTIVE: ""
    ports: ["8081:8080"]
    networks: [retailstore]
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health/readiness || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  carts:
    build:
      context: ../cart-service
      dockerfile: Dockerfile
    container_name: carts
    environment:
      RETAIL_CART_DYNAMODB_TABLE_NAME: Carts
      RETAIL_CART_DYNAMODB_ENDPOINT: http://dynamodb-local:8000
      AWS_ACCESS_KEY_ID: dummy
      AWS_SECRET_ACCESS_KEY: dummy
      AWS_REGION: us-east-1
    ports: ["8082:8080"]
    networks: [retailstore]
    depends_on:
      dynamodb-init:
        condition: service_completed_successfully
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health/readiness || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  checkout:
    build:
      context: ../checkout-service
      dockerfile: Dockerfile
    container_name: checkout
    environment:
      REDIS_HOST: redis
      REDIS_PORT: "6379"
      RETAIL_CHECKOUT_ENDPOINTS_ORDERS: http://orders:8080
      RETAIL_CHECKOUT_SESSION_TTL_MINUTES: "30"
      RETAIL_CHECKOUT_TAX_RATE: "0.20"
      RETAIL_CHECKOUT_SHIPPING_COST: "5.99"
      RETAIL_CHECKOUT_FREE_SHIPPING_THRESHOLD: "50.00"
    ports: ["8083:8080"]
    networks: [retailstore]
    depends_on:
      redis:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health/readiness || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  orders:
    build:
      context: ../order-service
      dockerfile: Dockerfile
    container_name: orders
    environment:
      RETAIL_ORDER_MESSAGING_ENABLED: "false"
      SPRING_PROFILES_ACTIVE: postgres
      RETAIL_ORDER_DB_ENDPOINT: postgres-orders:5432
      RETAIL_ORDER_DB_NAME: ordersdb
      RETAIL_ORDER_DB_USER: orders_user
      RETAIL_ORDER_DB_PASSWORD: orders_pass
    ports: ["8084:8080"]
    networks: [retailstore]
    depends_on:
      postgres-orders:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health/readiness || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  identity:
    build:
      context: ../identity-service
      dockerfile: Dockerfile
    container_name: identity
    environment:
      SPRING_PROFILES_ACTIVE: postgres
      RETAIL_IDENTITY_DB_ENDPOINT: postgres-identity:5432
      RETAIL_IDENTITY_DB_NAME: identitydb
      RETAIL_IDENTITY_DB_USER: identity_user
      RETAIL_IDENTITY_DB_PASSWORD: identity_pass
      RETAIL_IDENTITY_JWT_SECRET: bXlzdXBlcnNlY3JldGtleW15c3VwZXJzZWNyZXRrZXlteXN1cGVyc2VjcmV0a2V5bXlzdXBlcg==
    ports: ["8085:8080"]
    networks: [retailstore]
    depends_on:
      postgres-identity:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health/readiness || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  experience:
    build:
      context: ../experience-service
      dockerfile: Dockerfile
    container_name: experience
    environment:
      RETAIL_EXPERIENCE_ENDPOINTS_CATALOG: http://catalog:8080
      RETAIL_EXPERIENCE_ENDPOINTS_CARTS: http://carts:8080
      RETAIL_EXPERIENCE_ENDPOINTS_ORDERS: http://orders:8080
    ports: ["8086:8080"]
    networks: [retailstore]
    depends_on:
      catalog:
        condition: service_healthy
      carts:
        condition: service_healthy
      orders:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health/readiness || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

  gateway:
    build:
      context: ../api-gateway
      dockerfile: Dockerfile
    container_name: gateway
    environment:
      RETAIL_GATEWAY_ROUTES_EXPERIENCE: http://experience:8080
      RETAIL_GATEWAY_ROUTES_CATALOG: http://catalog:8080
      RETAIL_GATEWAY_ROUTES_CARTS: http://carts:8080
      RETAIL_GATEWAY_ROUTES_ORDERS: http://orders:8080
      RETAIL_GATEWAY_ROUTES_IDENTITY: http://identity:8080
      REDIS_HOST: redis
      REDIS_PORT: "6379"
      ALLOWED_ORIGIN: http://localhost:3000
    ports: ["8080:8080"]
    networks: [retailstore]
    depends_on:
      experience:
        condition: service_healthy
      identity:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "curl -sf http://localhost:8080/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 10
      start_period: 30s

```

---

## INFRA: retailstore-platform — scripts


### `retailstore-platform/scripts/local-dev.sh`

```bash
#!/bin/bash
# Start the full RetailStore stack locally using Docker Compose
# Run from: retailstore-platform/

set -e
cd "$(dirname "$0")/.."

echo "================================================"
echo "  RetailStore — Local Dev Stack"
echo "================================================"

case "${1:-up}" in
  up)
    echo "Starting all services..."
    docker compose up --build -d
    echo ""
    echo "Services starting... Waiting for health checks..."
    sleep 15
    docker compose ps
    echo ""
    echo "API Gateway:        http://localhost:8080"
    echo "Catalog Service:    http://localhost:8081/swagger-ui.html"
    echo "Cart Service:       http://localhost:8082/swagger-ui.html"
    echo "Checkout Service:   http://localhost:8083/swagger-ui.html"
    echo "Order Service:      http://localhost:8084/swagger-ui.html"
    echo "Identity Service:   http://localhost:8085/swagger-ui.html"
    echo "Experience Service: http://localhost:8086/swagger-ui.html"
    echo ""
    echo "Start the React frontend:"
    echo "  cd web-storefront && npm install && npm run dev"
    echo "  → http://localhost:3000"
    ;;
  down)
    docker compose down
    ;;
  logs)
    docker compose logs -f "${2:-}"
    ;;
  restart)
    docker compose restart "${2:-}"
    ;;
  *)
    echo "Usage: $0 [up|down|logs|restart] [service]"
    exit 1
    ;;
esac

```

### `retailstore-platform/scripts/deploy-dev.sh`

```bash
#!/bin/bash
# Deploy all RetailStore services to EKS (dev environment)
# Run from: retailstore-platform/scripts/
# Prereqs: kubectl configured, helm installed, ECR authenticated

set -e

NAMESPACE=${NAMESPACE:-default}
CHART_REPO=${CHART_REPO:-""}  # set to your ECR registry if using remote charts
ENV=dev

echo "================================================"
echo "  RetailStore — Deploy (env=$ENV, ns=$NAMESPACE)"
echo "================================================"

VALUES_DIR="$(dirname "$0")/../helm/$ENV"

deploy() {
  local name=$1
  local chart=$2
  local values=$3
  echo ""
  echo "── Deploying $name ──"
  helm upgrade --install "$name" "$chart" \
    -f "$VALUES_DIR/$values" \
    --namespace "$NAMESPACE" \
    --create-namespace \
    --wait \
    --timeout 5m
}

# Order matters — gateway deploys last
deploy catalog   oci://public.ecr.aws/retailstore/catalog-chart   catalog.yaml
deploy carts     oci://public.ecr.aws/retailstore/cart-chart      carts.yaml
deploy checkout  oci://public.ecr.aws/retailstore/checkout-chart  checkout.yaml
deploy orders    oci://public.ecr.aws/retailstore/order-chart     orders.yaml
deploy identity  oci://public.ecr.aws/retailstore/identity-chart  identity.yaml
deploy experience oci://public.ecr.aws/retailstore/experience-chart experience.yaml
deploy gateway   oci://public.ecr.aws/retailstore/gateway-chart   gateway.yaml

echo ""
echo "================================================"
echo "  All services deployed successfully"
echo "================================================"
kubectl get pods -n "$NAMESPACE"

```

### `retailstore-platform/scripts/build-images.sh`

```bash
#!/bin/bash
# Build and push all Docker images to ECR
# Usage: ./build-images.sh <ecr-registry> <tag>
# Example: ./build-images.sh 123456789.dkr.ecr.us-east-1.amazonaws.com sha-abc1234

set -e

REGISTRY=${1:?"Usage: $0 <ecr-registry> <image-tag>"}
TAG=${2:?"Usage: $0 <ecr-registry> <image-tag>"}

SERVICES=(
  "catalog-service:catalog"
  "cart-service:carts"
  "checkout-service:checkout"
  "order-service:orders"
  "identity-service:identity"
  "experience-service:experience"
  "api-gateway:gateway"
)

REPO_ROOT="$(dirname "$0")/../.."

echo "Building and pushing images (tag=$TAG)..."

for entry in "${SERVICES[@]}"; do
  dir="${entry%%:*}"
  name="${entry##*:}"
  image="$REGISTRY/retailstore/$name:$TAG"
  echo ""
  echo "── $name ──"
  docker build -t "$image" "$REPO_ROOT/$dir"
  docker push "$image"
  echo "✓ Pushed $image"
done

echo ""
echo "All images pushed. Update helm values with tag: $TAG"

```

---

## INFRA: retailstore-platform — Helm dev overrides


### `retailstore-platform/helm/dev/catalog.yaml`

```yaml
image:
  repository: ""
  tag: "latest"
replicaCount: 1
appEnv:
  RETAIL_CATALOG_PERSISTENCE_PROVIDER: "in-memory"
  SPRING_PROFILES_ACTIVE: ""
resources:
  limits:
    memory: 512Mi
  requests:
    cpu: 100m
    memory: 256Mi
autoscaling:
  enabled: false

```

### `retailstore-platform/helm/dev/carts.yaml`

```yaml
image:
  repository: ""
  tag: "latest"
replicaCount: 1
appEnv:
  RETAIL_CART_DYNAMODB_TABLE_NAME: "Carts"
  RETAIL_CART_DYNAMODB_ENDPOINT: "http://dynamodb-local:8000"
  AWS_REGION: "us-east-1"
resources:
  limits:
    memory: 512Mi
  requests:
    cpu: 100m
    memory: 256Mi
autoscaling:
  enabled: false

```

### `retailstore-platform/helm/dev/checkout.yaml`

```yaml
image:
  repository: ""
  tag: "latest"
replicaCount: 1
appEnv:
  REDIS_HOST: "redis-master"
  REDIS_PORT: "6379"
  RETAIL_CHECKOUT_ENDPOINTS_ORDERS: "http://orders"
  RETAIL_CHECKOUT_SESSION_TTL_MINUTES: "30"
  RETAIL_CHECKOUT_TAX_RATE: "0.20"
  RETAIL_CHECKOUT_SHIPPING_COST: "5.99"
  RETAIL_CHECKOUT_FREE_SHIPPING_THRESHOLD: "50.00"
resources:
  limits:
    memory: 512Mi
  requests:
    cpu: 100m
    memory: 256Mi
autoscaling:
  enabled: false

```

### `retailstore-platform/helm/dev/orders.yaml`

```yaml
image:
  repository: ""
  tag: "latest"
replicaCount: 1
appEnv:
  RETAIL_ORDER_MESSAGING_ENABLED: "false"
  SPRING_PROFILES_ACTIVE: "postgres"
  RETAIL_ORDER_DB_ENDPOINT: "postgres-orders:5432"
  RETAIL_ORDER_DB_NAME: "ordersdb"
  RETAIL_ORDER_DB_USER: "orders_user"
resources:
  limits:
    memory: 512Mi
  requests:
    cpu: 100m
    memory: 256Mi
autoscaling:
  enabled: false

```

### `retailstore-platform/helm/dev/identity.yaml`

```yaml
image:
  repository: ""
  tag: "latest"
replicaCount: 1
appEnv:
  SPRING_PROFILES_ACTIVE: "postgres"
  RETAIL_IDENTITY_DB_ENDPOINT: "postgres-identity:5432"
  RETAIL_IDENTITY_DB_NAME: "identitydb"
  RETAIL_IDENTITY_DB_USER: "identity_user"
resources:
  limits:
    memory: 512Mi
  requests:
    cpu: 100m
    memory: 256Mi
autoscaling:
  enabled: false

```

### `retailstore-platform/helm/dev/experience.yaml`

```yaml
image:
  repository: ""
  tag: "latest"
replicaCount: 1
appEnv:
  RETAIL_EXPERIENCE_ENDPOINTS_CATALOG: "http://catalog"
  RETAIL_EXPERIENCE_ENDPOINTS_CARTS: "http://carts"
  RETAIL_EXPERIENCE_ENDPOINTS_ORDERS: "http://orders"
resources:
  limits:
    memory: 512Mi
  requests:
    cpu: 100m
    memory: 256Mi
autoscaling:
  enabled: false

```

### `retailstore-platform/helm/dev/gateway.yaml`

```yaml
image:
  repository: ""
  tag: "latest"
replicaCount: 1
appEnv:
  RETAIL_GATEWAY_ROUTES_EXPERIENCE: "http://experience"
  RETAIL_GATEWAY_ROUTES_CATALOG: "http://catalog"
  RETAIL_GATEWAY_ROUTES_CARTS: "http://carts"
  RETAIL_GATEWAY_ROUTES_ORDERS: "http://orders"
  RETAIL_GATEWAY_ROUTES_IDENTITY: "http://identity"
  REDIS_HOST: "redis-master"
  REDIS_PORT: "6379"
  ALLOWED_ORIGIN: "https://shop.retailstore.com"
resources:
  limits:
    memory: 512Mi
  requests:
    cpu: 100m
    memory: 256Mi
autoscaling:
  enabled: false

```

---

## SOURCE: web-storefront


### `web-storefront/package.json`

```json
{
  "name": "web-storefront",
  "version": "1.0.0",
  "private": true,
  "scripts": {
    "dev": "vite",
    "build": "tsc && vite build",
    "preview": "vite preview",
    "lint": "eslint src --ext ts,tsx --report-unused-disable-directives",
    "test": "vitest"
  },
  "dependencies": {
    "react": "^18.3.1",
    "react-dom": "^18.3.1",
    "react-router-dom": "^6.27.0",
    "axios": "^1.7.7",
    "zustand": "^5.0.0",
    "@tanstack/react-query": "^5.59.0",
    "lucide-react": "^0.454.0",
    "clsx": "^2.1.1"
  },
  "devDependencies": {
    "@types/react": "^18.3.11",
    "@types/react-dom": "^18.3.1",
    "@vitejs/plugin-react": "^4.3.2",
    "typescript": "^5.6.3",
    "vite": "^5.4.10",
    "tailwindcss": "^3.4.14",
    "autoprefixer": "^10.4.20",
    "postcss": "^8.4.47",
    "vitest": "^2.1.3",
    "@testing-library/react": "^16.0.1"
  }
}

```

### `web-storefront/vite.config.ts`

```typescript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import path from 'path'

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { '@': path.resolve(__dirname, 'src') },
  },
  server: {
    port: 3000,
    proxy: {
      // Dev proxy → api-gateway running locally
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  build: {
    outDir: 'dist',
    sourcemap: false,
    rollupOptions: {
      output: {
        manualChunks: {
          vendor:   ['react', 'react-dom', 'react-router-dom'],
          query:    ['@tanstack/react-query'],
          ui:       ['lucide-react'],
        },
      },
    },
  },
})

```

### `web-storefront/tsconfig.json`

```json
{
  "compilerOptions": {
    "target": "ES2020",
    "useDefineForClassFields": true,
    "lib": ["ES2020", "DOM", "DOM.Iterable"],
    "module": "ESNext",
    "skipLibCheck": true,
    "moduleResolution": "bundler",
    "allowImportingTsExtensions": true,
    "resolveJsonModule": true,
    "isolatedModules": true,
    "noEmit": true,
    "jsx": "react-jsx",
    "strict": true,
    "baseUrl": ".",
    "paths": { "@/*": ["src/*"] }
  },
  "include": ["src"]
}

```

### `web-storefront/tailwind.config.js`

```javascript
/** @type {import('tailwindcss').Config} */
export default {
  content: ['./index.html', './src/**/*.{js,ts,jsx,tsx}'],
  theme: {
    extend: {
      colors: {
        brand: {
          50: '#eff6ff', 100: '#dbeafe', 200: '#bfdbfe',
          500: '#3b82f6', 600: '#2563eb', 700: '#1d4ed8', 900: '#1e3a8a',
        },
      },
      fontFamily: {
        sans: ['Inter', 'system-ui', 'sans-serif'],
      },
    },
  },
  plugins: [],
}

```

### `web-storefront/index.html`

```
<!DOCTYPE html>
<html lang="en">
  <head>
    <meta charset="UTF-8" />
    <meta name="viewport" content="width=device-width, initial-scale=1.0" />
    <link rel="preconnect" href="https://fonts.googleapis.com" />
    <link rel="preconnect" href="https://fonts.gstatic.com" crossorigin />
    <link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700&display=swap" rel="stylesheet" />
    <title>RetailStore</title>
  </head>
  <body>
    <div id="root"></div>
    <script type="module" src="/src/main.tsx"></script>
  </body>
</html>

```

### `web-storefront/.env.example`

```
# Copy to .env.local and fill in
VITE_API_BASE_URL=http://localhost:8080
VITE_CLIENT_CHANNEL=WEB

```

### `web-storefront/src/index.css`

```
@tailwind base;
@tailwind components;
@tailwind utilities;

@layer base {
  body { @apply bg-gray-50 text-gray-900 antialiased; }
  * { @apply box-border; }
}

```

### `web-storefront/src/main.tsx`

```typescript
import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { QueryClient, QueryClientProvider } from '@tanstack/react-query'
import App from './App'
import './index.css'

const queryClient = new QueryClient({
  defaultOptions: {
    queries: { staleTime: 60_000, retry: 2, refetchOnWindowFocus: false },
  },
})

ReactDOM.createRoot(document.getElementById('root')!).render(
  <React.StrictMode>
    <QueryClientProvider client={queryClient}>
      <BrowserRouter>
        <App />
      </BrowserRouter>
    </QueryClientProvider>
  </React.StrictMode>
)

```

### `web-storefront/src/App.tsx`

```typescript
import { Routes, Route } from 'react-router-dom'
import { Layout } from '@/components/layout/Layout'
import { HomePage } from '@/pages/HomePage'
import { CartPage } from '@/pages/CartPage'
import { CheckoutPage } from '@/pages/CheckoutPage'
import { OrderConfirmationPage } from '@/pages/OrderConfirmationPage'
import { OrdersPage } from '@/pages/OrdersPage'

export default function App() {
  return (
    <Layout>
      <Routes>
        <Route path="/"                             element={<HomePage />} />
        <Route path="/catalog"                      element={<HomePage />} />
        <Route path="/cart"                         element={<CartPage />} />
        <Route path="/checkout"                     element={<CheckoutPage />} />
        <Route path="/order-confirmation/:orderId"  element={<OrderConfirmationPage />} />
        <Route path="/orders"                       element={<OrdersPage />} />
      </Routes>
    </Layout>
  )
}

```

### `web-storefront/src/types/index.ts`

```typescript
// ─── Domain types ─────────────────────────────────────────────────────────────

export interface Tag {
  name: string
  displayName: string
}

export interface Product {
  id: string
  name: string
  description: string
  price: number
  inStock: boolean
  available: boolean
  tags: Tag[]
}

export interface PagedProductResponse {
  products: Product[]
  totalCount: number
  page: number
  size: number
  totalPages: number
  hasNext: boolean
  hasPrevious: boolean
}

export interface CartItem {
  itemId: string
  productId: string
  productName: string
  imageUrl?: string
  quantity: number
  unitPrice: number
  lineTotal: number
}

export interface Cart {
  customerId: string
  items: CartItem[]
  totalItemCount: number
  lineItemCount: number
  subtotal: number
}

export interface CartSummary {
  customerId: string
  items: CartItem[]
  totalItemCount: number
  subtotal: number
  estimatedShipping: number
  estimatedTotal: number
}

export interface OrderLineItem {
  productId: string
  productName: string
  quantity: number
  unitPrice: number
  lineTotal: number
}

export interface ShippingAddress {
  fullName: string
  addressLine1: string
  addressLine2?: string
  city: string
  state: string
  postalCode: string
  country: string
}

export interface Order {
  id: string
  customerId: string
  status: OrderStatus
  lineItems: OrderLineItem[]
  shippingAddress: ShippingAddress
  subtotal: number
  shippingCost: number
  total: number
  cancellable: boolean
  cancellationReason?: string
  createdAt: string
  updatedAt: string
}

export type OrderStatus =
  | 'PENDING'
  | 'CONFIRMED'
  | 'PROCESSING'
  | 'SHIPPED'
  | 'DELIVERED'
  | 'CANCELLED'
  | 'REFUNDED'

// ─── Experience layer (BFF) types ─────────────────────────────────────────────

export interface HomepageResponse {
  featuredProducts: FeaturedProduct[]
  availableTags: Tag[]
  cartItemCount: number
}

export interface FeaturedProduct {
  id: string
  name: string
  description?: string
  price: number
  inStock: boolean
  tagNames: string[]
}

// ─── Request types ─────────────────────────────────────────────────────────────

export interface AddToCartRequest {
  productId: string
  productName: string
  quantity: number
  unitPrice: number
  imageUrl?: string
}

export interface PlaceOrderRequest {
  customerId: string
  checkoutSessionId?: string
  lineItems: PlaceOrderLineItem[]
  shippingAddress: ShippingAddress
  subtotal: number
  shippingCost: number
  total: number
}

export interface PlaceOrderLineItem {
  productId: string
  productName: string
  quantity: number
  unitPrice: number
}

```

### `web-storefront/src/services/api.ts`

```typescript
import axios from 'axios'

const CLIENT_CHANNEL = import.meta.env.VITE_CLIENT_CHANNEL ?? 'WEB'
const BASE_URL = import.meta.env.VITE_API_BASE_URL ?? ''

export const api = axios.create({
  baseURL: BASE_URL,
  timeout: 10_000,
  headers: {
    'Content-Type': 'application/json',
    'X-Client-Channel': CLIENT_CHANNEL,
  },
})

api.interceptors.request.use(config => {
  config.headers['X-Correlation-Id'] =
    Math.random().toString(36).slice(2, 11)
  return config
})

api.interceptors.response.use(
  res => res,
  err => {
    if (err.response?.status === 401) {
      window.location.href = '/login'
    }
    return Promise.reject(err)
  }
)

```

### `web-storefront/src/services/experienceService.ts`

```typescript
import { api } from './api'
import type { HomepageResponse, Product } from '@/types'

export const experienceService = {
  getHomepage: (customerId: string, featuredCount = 8) =>
    api.get<HomepageResponse>('/api/v1/experience/homepage', {
      params: { customerId, featuredCount },
    }).then(r => r.data),

  getProductDetail: (id: string) =>
    api.get<Product>(`/api/v1/experience/products/${id}`).then(r => r.data),

  getCartSummary: (customerId: string) =>
    api.get(`/api/v1/experience/cart/${customerId}/summary`).then(r => r.data),
}

```

### `web-storefront/src/services/catalogService.ts`

```typescript
import { api } from './api'
import type { PagedProductResponse, Product, Tag } from '@/types'

export const catalogService = {
  getProducts: (params: { page?: number; size?: number; tags?: string; order?: string }) =>
    api.get<PagedProductResponse>('/api/v1/catalog/products', { params }).then(r => r.data),

  getProduct: (id: string) =>
    api.get<Product>(`/api/v1/catalog/products/${id}`).then(r => r.data),

  getTags: () =>
    api.get<Tag[]>('/api/v1/catalog/tags').then(r => r.data),
}

```

### `web-storefront/src/services/cartService.ts`

```typescript
import { api } from './api'
import type { AddToCartRequest, Cart } from '@/types'

export const cartService = {
  getCart: (customerId: string) =>
    api.get<Cart>(`/api/v1/carts/${customerId}`).then(r => r.data),

  addItem: (customerId: string, request: AddToCartRequest) =>
    api.post<Cart>(`/api/v1/carts/${customerId}/items`, request).then(r => r.data),

  updateItem: (customerId: string, itemId: string, quantity: number) =>
    api.put<Cart>(`/api/v1/carts/${customerId}/items/${itemId}`, { quantity }).then(r => r.data),

  removeItem: (customerId: string, itemId: string) =>
    api.delete(`/api/v1/carts/${customerId}/items/${itemId}`),

  clearCart: (customerId: string) =>
    api.delete(`/api/v1/carts/${customerId}`),
}

```

### `web-storefront/src/services/orderService.ts`

```typescript
import { api } from './api'
import type { Order, PlaceOrderRequest } from '@/types'

interface PagedOrders {
  content: Order[]
  totalElements: number
  totalPages: number
}

export const orderService = {
  placeOrder: (request: PlaceOrderRequest) =>
    api.post<Order>('/api/v1/orders', request).then(r => r.data),

  getOrder: (orderId: string) =>
    api.get<Order>(`/api/v1/orders/${orderId}`).then(r => r.data),

  getCustomerOrders: (customerId: string, page = 0, size = 10) =>
    api.get<PagedOrders>(`/api/v1/orders/customer/${customerId}`, {
      params: { page, size },
    }).then(r => r.data),

  cancelOrder: (orderId: string, reason?: string) =>
    api.post<Order>(`/api/v1/orders/${orderId}/cancel`, null, {
      params: { reason },
    }).then(r => r.data),
}

```

### `web-storefront/src/store/useAppStore.ts`

```typescript
import { create } from 'zustand'
import { persist } from 'zustand/middleware'

interface AppStore {
  customerId: string
  cartItemCount: number
  setCustomerId: (id: string) => void
  setCartItemCount: (count: number) => void
}

export const useAppStore = create<AppStore>()(
  persist(
    set => ({
      customerId: `guest-${Math.random().toString(36).slice(2, 9)}`,
      cartItemCount: 0,
      setCustomerId: id => set({ customerId: id }),
      setCartItemCount: count => set({ cartItemCount: count }),
    }),
    { name: 'retailstore-app' }
  )
)

```

### `web-storefront/src/utils/format.ts`

```typescript
export const formatPrice = (value: number | string): string => {
  const amount = typeof value === 'string' ? parseFloat(value) : value
  return new Intl.NumberFormat('en-US', { style: 'currency', currency: 'USD' }).format(amount)
}

export const formatDate = (iso: string): string =>
  new Intl.DateTimeFormat('en-US', {
    year: 'numeric', month: 'short', day: 'numeric',
    hour: '2-digit', minute: '2-digit',
  }).format(new Date(iso))

export const ORDER_STATUS_LABELS: Record<string, string> = {
  PENDING: 'Pending', CONFIRMED: 'Confirmed', PROCESSING: 'Processing',
  SHIPPED: 'Shipped', DELIVERED: 'Delivered', CANCELLED: 'Cancelled', REFUNDED: 'Refunded',
}

export const ORDER_STATUS_COLORS: Record<string, string> = {
  PENDING:    'bg-yellow-100 text-yellow-800',
  CONFIRMED:  'bg-blue-100 text-blue-800',
  PROCESSING: 'bg-indigo-100 text-indigo-800',
  SHIPPED:    'bg-purple-100 text-purple-800',
  DELIVERED:  'bg-green-100 text-green-800',
  CANCELLED:  'bg-red-100 text-red-800',
  REFUNDED:   'bg-gray-100 text-gray-800',
}

```

### `web-storefront/src/components/layout/Layout.tsx`

```typescript
import type { ReactNode } from 'react'
import { Navbar } from './Navbar'
export function Layout({ children }: { children: ReactNode }) {
  return (
    <div className="min-h-screen flex flex-col bg-gray-50">
      <Navbar />
      <main className="flex-1 max-w-7xl mx-auto w-full px-4 sm:px-6 lg:px-8 py-8">{children}</main>
      <footer className="bg-white border-t border-gray-100 py-6">
        <p className="text-center text-sm text-gray-400">© {new Date().getFullYear()} RetailStore Platform</p>
      </footer>
    </div>
  )
}

```

### `web-storefront/src/components/layout/Navbar.tsx`

```typescript
import { Link } from 'react-router-dom'
import { ShoppingCart, Package, User } from 'lucide-react'
import { useAppStore } from '@/store/useAppStore'

export function Navbar() {
  const cartItemCount = useAppStore(s => s.cartItemCount)
  return (
    <nav className="sticky top-0 z-50 bg-white border-b border-gray-200 shadow-sm">
      <div className="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 flex items-center justify-between h-16">
        <Link to="/" className="flex items-center gap-2 font-bold text-xl text-blue-700">
          <Package className="w-6 h-6" /> RetailStore
        </Link>
        <div className="hidden sm:flex items-center gap-8">
          <Link to="/" className="text-sm text-gray-600 hover:text-blue-600">Home</Link>
          <Link to="/catalog" className="text-sm text-gray-600 hover:text-blue-600">Shop</Link>
          <Link to="/orders" className="text-sm text-gray-600 hover:text-blue-600">My Orders</Link>
        </div>
        <div className="flex items-center gap-4">
          <Link to="/cart" className="relative">
            <ShoppingCart className="w-6 h-6 text-gray-600 hover:text-blue-600" />
            {cartItemCount > 0 && (
              <span className="absolute -top-2 -right-2 bg-blue-600 text-white text-xs rounded-full min-w-[18px] h-[18px] flex items-center justify-center font-semibold px-1">
                {cartItemCount > 99 ? '99+' : cartItemCount}
              </span>
            )}
          </Link>
          <Link to="/account"><User className="w-6 h-6 text-gray-600 hover:text-blue-600" /></Link>
        </div>
      </div>
    </nav>
  )
}

```

### `web-storefront/src/components/common/LoadingSpinner.tsx`

```typescript
const sizes = { sm: 'h-4 w-4', md: 'h-8 w-8', lg: 'h-12 w-12' }
export function LoadingSpinner({ size = 'md' }: { size?: 'sm'|'md'|'lg' }) {
  return (
    <div className="flex justify-center items-center py-12">
      <div className={`${sizes[size]} animate-spin rounded-full border-4 border-blue-100 border-t-blue-600`} />
    </div>
  )
}

```

### `web-storefront/src/components/common/EmptyState.tsx`

```typescript
import type { ReactNode } from 'react'
export function EmptyState({ icon, title, description, action }: {
  icon: ReactNode; title: string; description: string; action?: ReactNode
}) {
  return (
    <div className="text-center py-20">
      <div className="inline-flex text-gray-300 mb-4">{icon}</div>
      <h3 className="text-xl font-semibold text-gray-700 mb-2">{title}</h3>
      <p className="text-gray-500 mb-6 max-w-sm mx-auto">{description}</p>
      {action}
    </div>
  )
}

```

### `web-storefront/src/components/common/ErrorMessage.tsx`

```typescript
interface Props { message: string }
export function ErrorMessage({ message }: Props) {
  return (
    <div className="bg-red-50 border border-red-200 rounded-lg p-4 text-red-700 text-sm">
      {message}
    </div>
  )
}

```

### `web-storefront/src/components/catalog/ProductCard.tsx`

```typescript
import { ShoppingCart } from 'lucide-react'
import { Link } from 'react-router-dom'
import type { FeaturedProduct } from '@/types'
import { formatPrice } from '@/utils/format'

interface Props {
  product: FeaturedProduct
  onAddToCart: (product: FeaturedProduct) => void
  isAdding?: boolean
}

export function ProductCard({ product, onAddToCart, isAdding }: Props) {
  return (
    <div className="bg-white rounded-xl border border-gray-100 shadow-sm p-4 flex flex-col hover:shadow-md transition-shadow">
      <div className="bg-gray-100 rounded-lg h-44 mb-4 flex items-center justify-center">
        <span className="text-5xl">🛍️</span>
      </div>
      <div className="flex gap-1 flex-wrap mb-2">
        {product.tagNames.map(t => (
          <span key={t} className="text-xs bg-blue-50 text-blue-700 px-2 py-0.5 rounded-full">{t}</span>
        ))}
      </div>
      <Link to={`/catalog/${product.id}`} className="font-semibold text-gray-900 hover:text-blue-600 mb-1 line-clamp-1">
        {product.name}
      </Link>
      {product.description && (
        <p className="text-sm text-gray-500 line-clamp-2 mb-3 flex-1">{product.description}</p>
      )}
      {!product.inStock && (
        <p className="text-xs text-red-500 mb-2 font-medium">Out of stock</p>
      )}
      <div className="flex items-center justify-between mt-auto pt-3 border-t border-gray-100">
        <span className="text-lg font-bold text-gray-900">{formatPrice(product.price)}</span>
        <button
          onClick={() => onAddToCart(product)}
          disabled={isAdding || !product.inStock}
          className="flex items-center gap-1.5 bg-blue-600 text-white px-3 py-1.5 rounded-lg text-sm font-medium hover:bg-blue-700 disabled:opacity-50 disabled:cursor-not-allowed transition-colors"
        >
          <ShoppingCart className="w-4 h-4" />
          {isAdding ? 'Adding…' : 'Add to cart'}
        </button>
      </div>
    </div>
  )
}

```

### `web-storefront/src/pages/HomePage.tsx`

```typescript
import { useState } from 'react'
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { ShoppingBag } from 'lucide-react'
import { Link } from 'react-router-dom'
import { experienceService } from '@/services/experienceService'
import { cartService } from '@/services/cartService'
import { useAppStore } from '@/store/useAppStore'
import { ProductCard } from '@/components/catalog/ProductCard'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'
import { EmptyState } from '@/components/common/EmptyState'
import type { FeaturedProduct } from '@/types'

export function HomePage() {
  const { customerId, setCartItemCount } = useAppStore()
  const [addingId, setAddingId] = useState<string | null>(null)
  const [selectedTag, setSelectedTag] = useState<string | null>(null)
  const queryClient = useQueryClient()

  const { data, isLoading } = useQuery({
    queryKey: ['homepage', customerId],
    queryFn: () => experienceService.getHomepage(customerId, 12),
    staleTime: 2 * 60 * 1000,
  })

  const addToCart = useMutation({
    mutationFn: (p: FeaturedProduct) =>
      cartService.addItem(customerId, {
        productId: p.id, productName: p.name,
        quantity: 1, unitPrice: p.price,
      }),
    onSuccess: cart => {
      setCartItemCount(cart.totalItemCount)
      queryClient.invalidateQueries({ queryKey: ['cart', customerId] })
    },
  })

  const handleAddToCart = async (p: FeaturedProduct) => {
    setAddingId(p.id)
    await addToCart.mutateAsync(p).catch(() => {})
    setAddingId(null)
  }

  const filteredProducts = selectedTag
    ? data?.featuredProducts.filter(p => p.tagNames.includes(selectedTag)) ?? []
    : data?.featuredProducts ?? []

  if (isLoading) return <LoadingSpinner size="lg" />

  return (
    <div>
      <div className="mb-8">
        <h1 className="text-3xl font-bold text-gray-900 mb-2">Welcome to RetailStore</h1>
        <p className="text-gray-500">Curated products, delivered fast.</p>
      </div>

      {/* Tag filters */}
      {data && (
        <div className="flex gap-2 flex-wrap mb-6">
          <button
            onClick={() => setSelectedTag(null)}
            className={`px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
              !selectedTag ? 'bg-blue-600 text-white' : 'bg-white border border-gray-300 text-gray-600 hover:border-blue-400'
            }`}>
            All
          </button>
          {data.availableTags.map(tag => (
            <button key={tag.name}
              onClick={() => setSelectedTag(selectedTag === tag.name ? null : tag.name)}
              className={`px-4 py-1.5 rounded-full text-sm font-medium transition-colors ${
                selectedTag === tag.name ? 'bg-blue-600 text-white' : 'bg-white border border-gray-300 text-gray-600 hover:border-blue-400'
              }`}>
              {tag.displayName}
            </button>
          ))}
        </div>
      )}

      {filteredProducts.length === 0 ? (
        <EmptyState icon={<ShoppingBag className="w-16 h-16" />}
          title="No products" description="Try a different filter."
          action={<Link to="/catalog" className="text-blue-600 underline">Browse all</Link>} />
      ) : (
        <div className="grid grid-cols-1 sm:grid-cols-2 lg:grid-cols-3 xl:grid-cols-4 gap-6">
          {filteredProducts.map(product => (
            <ProductCard key={product.id} product={product}
              onAddToCart={handleAddToCart}
              isAdding={addingId === product.id} />
          ))}
        </div>
      )}
    </div>
  )
}

```

### `web-storefront/src/pages/CartPage.tsx`

```typescript
import { useQuery, useMutation, useQueryClient } from '@tanstack/react-query'
import { Link, useNavigate } from 'react-router-dom'
import { Trash2, ShoppingBag } from 'lucide-react'
import { cartService } from '@/services/cartService'
import { useAppStore } from '@/store/useAppStore'
import { formatPrice } from '@/utils/format'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'
import { EmptyState } from '@/components/common/EmptyState'

const SHIPPING = 5.99

export function CartPage() {
  const { customerId, setCartItemCount } = useAppStore()
  const navigate = useNavigate()
  const queryClient = useQueryClient()

  const { data: cart, isLoading } = useQuery({
    queryKey: ['cart', customerId],
    queryFn: () => cartService.getCart(customerId),
  })

  const removeItem = useMutation({
    mutationFn: (itemId: string) => cartService.removeItem(customerId, itemId),
    onSuccess: () => queryClient.invalidateQueries({ queryKey: ['cart', customerId] }),
  })

  const updateQty = useMutation({
    mutationFn: ({ itemId, qty }: { itemId: string; qty: number }) =>
      cartService.updateItem(customerId, itemId, qty),
    onSuccess: updated => {
      setCartItemCount(updated.totalItemCount)
      queryClient.setQueryData(['cart', customerId], updated)
    },
  })

  if (isLoading) return <LoadingSpinner />
  const items = cart?.items ?? []

  if (items.length === 0) {
    return (
      <EmptyState icon={<ShoppingBag className="w-16 h-16" />}
        title="Your cart is empty" description="Add some products to get started."
        action={<Link to="/" className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700">Browse Products</Link>} />
    )
  }

  const subtotal = cart?.subtotal ?? 0
  const total = subtotal + SHIPPING

  return (
    <div className="max-w-3xl mx-auto">
      <h1 className="text-2xl font-bold mb-6">Shopping Cart ({cart?.totalItemCount} items)</h1>
      <div className="space-y-3 mb-6">
        {items.map(item => (
          <div key={item.itemId} className="bg-white rounded-xl border border-gray-100 p-4 flex items-center gap-4">
            <div className="text-3xl flex-shrink-0">🛍️</div>
            <div className="flex-1 min-w-0">
              <p className="font-semibold text-gray-900 truncate">{item.productName}</p>
              <p className="text-sm text-gray-500">{formatPrice(item.unitPrice)} each</p>
            </div>
            <div className="flex items-center gap-2 flex-shrink-0">
              <button onClick={() => updateQty.mutate({ itemId: item.itemId, qty: item.quantity - 1 })}
                className="w-8 h-8 rounded-full border border-gray-300 flex items-center justify-center hover:bg-gray-50 font-medium">−</button>
              <span className="w-8 text-center font-semibold">{item.quantity}</span>
              <button onClick={() => updateQty.mutate({ itemId: item.itemId, qty: item.quantity + 1 })}
                className="w-8 h-8 rounded-full border border-gray-300 flex items-center justify-center hover:bg-gray-50 font-medium">+</button>
            </div>
            <div className="w-20 text-right font-semibold flex-shrink-0">{formatPrice(item.lineTotal)}</div>
            <button onClick={() => removeItem.mutate(item.itemId)} className="text-red-400 hover:text-red-600 flex-shrink-0">
              <Trash2 className="w-4 h-4" />
            </button>
          </div>
        ))}
      </div>

      <div className="bg-white rounded-xl border border-gray-100 p-6">
        <div className="space-y-2 mb-4">
          <div className="flex justify-between text-sm text-gray-600"><span>Subtotal</span><span>{formatPrice(subtotal)}</span></div>
          <div className="flex justify-between text-sm text-gray-600"><span>Shipping</span><span>{formatPrice(SHIPPING)}</span></div>
          <div className="flex justify-between font-bold text-lg pt-2 border-t border-gray-100"><span>Total</span><span>{formatPrice(total)}</span></div>
        </div>
        <button onClick={() => navigate('/checkout')}
          className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 transition-colors">
          Proceed to Checkout →
        </button>
      </div>
    </div>
  )
}

```

### `web-storefront/src/pages/CheckoutPage.tsx`

```typescript
import { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { cartService } from '@/services/cartService'
import { orderService } from '@/services/orderService'
import { useAppStore } from '@/store/useAppStore'
import { formatPrice } from '@/utils/format'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'
import type { ShippingAddress } from '@/types'

const SHIPPING = 5.99
const EMPTY: ShippingAddress = {
  fullName: '', addressLine1: '', addressLine2: '',
  city: '', state: '', postalCode: '', country: 'US',
}

export function CheckoutPage() {
  const { customerId, setCartItemCount } = useAppStore()
  const navigate = useNavigate()
  const [addr, setAddr] = useState<ShippingAddress>(EMPTY)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError] = useState('')

  const { data: cart, isLoading } = useQuery({
    queryKey: ['cart', customerId],
    queryFn: () => cartService.getCart(customerId),
  })

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault()
    if (!cart || cart.items.length === 0) return
    setSubmitting(true); setError('')
    try {
      const subtotal = cart.subtotal
      const order = await orderService.placeOrder({
        customerId,
        lineItems: cart.items.map(i => ({
          productId: i.productId, productName: i.productName,
          quantity: i.quantity, unitPrice: i.unitPrice,
        })),
        shippingAddress: addr,
        subtotal,
        shippingCost: SHIPPING,
        total: subtotal + SHIPPING,
      })
      await cartService.clearCart(customerId)
      setCartItemCount(0)
      navigate(`/order-confirmation/${order.id}`)
    } catch {
      setError('Failed to place order. Please try again.')
    } finally {
      setSubmitting(false)
    }
  }

  if (isLoading) return <LoadingSpinner />

  const subtotal = cart?.subtotal ?? 0
  const total = subtotal + SHIPPING

  const field = (key: keyof ShippingAddress, label: string, required = true) => (
    <div key={key}>
      <label className="block text-sm font-medium text-gray-700 mb-1">{label}</label>
      <input type="text" required={required} value={addr[key] ?? ''}
        onChange={e => setAddr(a => ({ ...a, [key]: e.target.value }))}
        className="w-full border border-gray-300 rounded-lg px-3 py-2 focus:outline-none focus:ring-2 focus:ring-blue-500 text-sm" />
    </div>
  )

  return (
    <div className="max-w-5xl mx-auto grid grid-cols-1 lg:grid-cols-2 gap-8">
      <div>
        <h1 className="text-2xl font-bold mb-6">Shipping Details</h1>
        {error && <div className="bg-red-50 border border-red-200 text-red-700 rounded-lg p-3 mb-4 text-sm">{error}</div>}
        <form onSubmit={handleSubmit} className="space-y-4">
          {field('fullName', 'Full Name')}
          {field('addressLine1', 'Address Line 1')}
          {field('addressLine2', 'Address Line 2 (optional)', false)}
          <div className="grid grid-cols-2 gap-4">
            {field('city', 'City')}
            {field('state', 'State')}
          </div>
          <div className="grid grid-cols-2 gap-4">
            {field('postalCode', 'Postal Code')}
            {field('country', 'Country')}
          </div>
          <button type="submit" disabled={submitting}
            className="w-full bg-blue-600 text-white py-3 rounded-lg font-semibold hover:bg-blue-700 disabled:opacity-50 mt-2 transition-colors">
            {submitting ? 'Placing order…' : `Place Order — ${formatPrice(total)}`}
          </button>
        </form>
      </div>
      <div>
        <h2 className="text-xl font-bold mb-4">Order Summary</h2>
        <div className="bg-white rounded-xl border border-gray-100 p-6 space-y-3">
          {cart?.items.map(item => (
            <div key={item.itemId} className="flex justify-between text-sm">
              <span className="text-gray-700">{item.productName} × {item.quantity}</span>
              <span className="font-medium">{formatPrice(item.lineTotal)}</span>
            </div>
          ))}
          <div className="border-t border-gray-100 pt-3 space-y-2">
            <div className="flex justify-between text-sm text-gray-500"><span>Subtotal</span><span>{formatPrice(subtotal)}</span></div>
            <div className="flex justify-between text-sm text-gray-500"><span>Shipping</span><span>{formatPrice(SHIPPING)}</span></div>
            <div className="flex justify-between font-bold text-lg pt-2 border-t border-gray-100"><span>Total</span><span>{formatPrice(total)}</span></div>
          </div>
        </div>
      </div>
    </div>
  )
}

```

### `web-storefront/src/pages/OrderConfirmationPage.tsx`

```typescript
import { useParams, Link } from 'react-router-dom'
import { CheckCircle } from 'lucide-react'
import { useQuery } from '@tanstack/react-query'
import { orderService } from '@/services/orderService'
import { formatDate, formatPrice } from '@/utils/format'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'

export function OrderConfirmationPage() {
  const { orderId } = useParams<{ orderId: string }>()
  const { data: order, isLoading } = useQuery({
    queryKey: ['order', orderId],
    queryFn: () => orderService.getOrder(orderId!),
    enabled: !!orderId,
  })

  if (isLoading) return <LoadingSpinner />

  return (
    <div className="max-w-lg mx-auto text-center py-16">
      <div className="inline-flex text-green-500 mb-4"><CheckCircle className="w-20 h-20" /></div>
      <h1 className="text-3xl font-bold text-gray-900 mb-2">Order Confirmed!</h1>
      <p className="text-gray-500 mb-1">Thank you for your purchase.</p>
      {order && (
        <>
          <p className="text-sm font-mono text-gray-400 mb-2">{order.id}</p>
          <p className="text-sm text-gray-500 mb-2">{formatDate(order.createdAt)}</p>
          <p className="text-lg font-semibold text-gray-800 mb-8">Total: {formatPrice(order.total)}</p>
        </>
      )}
      <div className="flex gap-4 justify-center flex-wrap">
        <Link to="/" className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700">Continue Shopping</Link>
        <Link to="/orders" className="bg-white border border-gray-300 text-gray-700 px-6 py-2 rounded-lg hover:bg-gray-50">View Orders</Link>
      </div>
    </div>
  )
}

```

### `web-storefront/src/pages/OrdersPage.tsx`

```typescript
import { useQuery } from '@tanstack/react-query'
import { Link } from 'react-router-dom'
import { Package } from 'lucide-react'
import { orderService } from '@/services/orderService'
import { useAppStore } from '@/store/useAppStore'
import { formatDate, formatPrice, ORDER_STATUS_LABELS, ORDER_STATUS_COLORS } from '@/utils/format'
import { LoadingSpinner } from '@/components/common/LoadingSpinner'
import { EmptyState } from '@/components/common/EmptyState'

export function OrdersPage() {
  const { customerId } = useAppStore()
  const { data, isLoading } = useQuery({
    queryKey: ['orders', customerId],
    queryFn: () => orderService.getCustomerOrders(customerId),
  })

  if (isLoading) return <LoadingSpinner />
  const orders = data?.content ?? []

  if (orders.length === 0) {
    return (
      <EmptyState icon={<Package className="w-16 h-16" />}
        title="No orders yet" description="Your orders will appear here after checkout."
        action={<Link to="/" className="bg-blue-600 text-white px-6 py-2 rounded-lg hover:bg-blue-700">Start Shopping</Link>} />
    )
  }

  return (
    <div className="max-w-3xl mx-auto">
      <h1 className="text-2xl font-bold mb-6">Your Orders</h1>
      <div className="space-y-4">
        {orders.map(order => (
          <div key={order.id} className="bg-white rounded-xl border border-gray-100 p-5">
            <div className="flex items-center justify-between mb-3">
              <span className="font-mono text-sm text-gray-500">{order.id.slice(0, 8)}…</span>
              <span className={`text-xs px-2 py-1 rounded-full font-medium ${ORDER_STATUS_COLORS[order.status] ?? 'bg-gray-100 text-gray-700'}`}>
                {ORDER_STATUS_LABELS[order.status] ?? order.status}
              </span>
            </div>
            <div className="space-y-1 mb-3">
              {order.lineItems.map((li, i) => (
                <div key={i} className="flex justify-between text-sm">
                  <span className="text-gray-700">{li.productName} × {li.quantity}</span>
                  <span className="text-gray-500">{formatPrice(li.lineTotal)}</span>
                </div>
              ))}
            </div>
            <div className="flex justify-between items-center pt-3 border-t border-gray-100">
              <span className="text-sm text-gray-400">{formatDate(order.createdAt)}</span>
              <span className="font-bold">{formatPrice(order.total)}</span>
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}

```

---

## INSTRUCTIONS FOR CLAUDE CODE

## How to use this context

1. Read the ARCHITECTURE OVERVIEW section completely before making any changes.
2. All decisions in the LOCKED sections are final — do not re-open them.
3. When adding a feature, find the closest existing pattern and follow it exactly.
4. Never add business logic to controllers — controllers delegate to port/in interfaces.
5. Never let domain/model/ import Spring, JPA, or any framework.
6. Never share a database between two services.
7. When adding a new service, register a route in api-gateway application.yml and add a helm override in retailstore-platform/helm/dev/.

## Priority tasks (do these first when starting a Claude Code session)

### Task 1: Add checkout route to api-gateway
In api-gateway/src/main/resources/application.yml, add:
```yaml
- id: checkout-service
  uri: ${RETAIL_GATEWAY_ROUTES_CHECKOUT:http://checkout}
  predicates:
    - Path=/api/v1/checkout/**
  filters:
    - name: CircuitBreaker
      args:
        name: checkout-cb
        fallbackUri: forward:/fallback/checkout
```
And add to gateway helm dev override: RETAIL_GATEWAY_ROUTES_CHECKOUT: "http://checkout"

### Task 2: Add GlobalExceptionHandler to order-service
Follow the exact same pattern as checkout-service/api/rest/v1/controller/GlobalExceptionHandler.java.
Handle: OrderNotFoundException (404), OrderNotCancellableException (422), Exception (500).

### Task 3: Add SqsConfig bean to order-service
Create order-service/src/main/java/com/retailstore/order/infrastructure/config/SqsConfig.java:
```java
@Configuration
public class SqsConfig {
    @Bean
    public SqsClient sqsClient(@Value("${AWS_REGION:us-east-1}") String region) {
        return SqsClient.builder()
            .region(Region.of(region))
            .credentialsProvider(DefaultCredentialsProvider.create())
            .build();
    }
}
```

### Task 4: Build Helm charts for remaining services
cart, checkout, order, identity, experience, gateway — follow catalog-service/chart/ exactly.
Only the fullnameOverride and label names change per service.

### Task 5: Build Terraform
Two projects in retailstore-platform/terraform/:
- network/: VPC, 3 public + 3 private subnets, IGW, NAT Gateway, route tables
- cluster/: EKS cluster, managed node group, Pod Identity, LBC (Helm), EBS CSI (add-on), Secrets Store CSI + ASCP (Helm)
Use separate S3 state files: retailstore/network/{env}/terraform.tfstate and retailstore/cluster/{env}/terraform.tfstate
