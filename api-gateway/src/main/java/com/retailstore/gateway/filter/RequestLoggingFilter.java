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
    public Mono<Void> filter(ServerWebExchange exchange, GatewayFilterChain chain) {
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

        return chain.filter(exchange.mutate().request(mutated).build())
            .doFinally(signal -> {
                long duration = System.currentTimeMillis() - start;
                int status = exchange.getResponse().getStatusCode() != null
                    ? exchange.getResponse().getStatusCode().value() : 0;
                log.info("{} {} → {} ({}ms) channel={}", method, path, status, duration,
                    clientChannel != null ? clientChannel : "WEB");
            });
    }

    @Override public int getOrder() { return -1; }
}
