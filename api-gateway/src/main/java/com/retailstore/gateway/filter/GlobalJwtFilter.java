package com.retailstore.gateway.filter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cloud.gateway.filter.GatewayFilterChain;
import org.springframework.cloud.gateway.filter.GlobalFilter;
import org.springframework.core.Ordered;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.stereotype.Component;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.server.ServerWebExchange;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class GlobalJwtFilter implements GlobalFilter, Ordered {

    private final ReactiveJwtDecoder jwtDecoder;
    private final ObjectMapper objectMapper;

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();
    private static final List<String> PUBLIC_PATHS = List.of(
        "/actuator/**",
        "/fallback/**"
    );

    @Override
    public int getOrder() {
        return -3;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
        String path = exchange.getRequest().getURI().getPath();

        if (isPublicPath(path)) {
            return chain.filter(exchange);
        }

        String authHeader = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            return unauthorized(exchange, "Missing or invalid Authorization header");
        }

        String token = authHeader.substring(7);
        return jwtDecoder.decode(token)
            .flatMap(jwt -> {
                String userId    = jwt.getSubject();
                String email     = jwt.getClaimAsString("email");
                String name      = jwt.getClaimAsString("name");
                String role      = extractPrimaryRole(jwt);

                ServerHttpRequest enriched = exchange.getRequest().mutate()
                    .header("X-User-Id",    userId    != null ? userId    : "")
                    .header("X-User-Email", email     != null ? email     : "")
                    .header("X-User-Name",  name      != null ? name      : "")
                    .header("X-User-Role",  role      != null ? role      : "")
                    .build();

                return chain.filter(exchange.mutate().request(enriched).build());
            })
            .onErrorResume(e -> {
                log.warn("JWT validation failed for path {}: {}", path, e.getMessage());
                return unauthorized(exchange, "Token validation failed: " + e.getMessage());
            });
    }

    @SuppressWarnings("unchecked")
    private String extractPrimaryRole(org.springframework.security.oauth2.jwt.Jwt jwt) {
        Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
        if (realmAccess == null) return null;
        List<String> roles = (List<String>) realmAccess.get("roles");
        if (roles == null || roles.isEmpty()) return null;
        // Prefer explicit business roles over default Keycloak roles
        return roles.stream()
            .filter(r -> List.of("ADMIN", "CUSTOMER", "SUPPORT", "service-role").contains(r))
            .findFirst()
            .orElse(roles.get(0));
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.stream().anyMatch(pattern -> PATH_MATCHER.match(pattern, path));
    }

    private Mono<Void> unauthorized(ServerWebExchange exchange, String message) {
        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
        exchange.getResponse().getHeaders().setContentType(MediaType.APPLICATION_JSON);
        String body;
        try {
            body = objectMapper.writeValueAsString(Map.of(
                "status",  401,
                "error",   "Unauthorized",
                "message", message,
                "path",    exchange.getRequest().getURI().getPath()
            ));
        } catch (JsonProcessingException e) {
            body = "{\"status\":401,\"error\":\"Unauthorized\"}";
        }
        DataBuffer buffer = exchange.getResponse().bufferFactory()
            .wrap(body.getBytes(StandardCharsets.UTF_8));
        return exchange.getResponse().writeWith(Mono.just(buffer));
    }
}
