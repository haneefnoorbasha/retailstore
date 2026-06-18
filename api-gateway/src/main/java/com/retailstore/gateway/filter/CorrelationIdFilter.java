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
