package com.retailstore.experience.infrastructure.client;

import com.retailstore.experience.infrastructure.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
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
    private final ServiceTokenProvider serviceTokenProvider;

    public Mono<Map> getCart(String customerId) {
        return serviceTokenProvider.getToken()
            .flatMap(token -> webClient.get()
                .uri("/api/v1/carts/{customerId}", customerId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("cart getCart {} error", customerId, e))
                .onErrorReturn(Map.of("customerId", customerId, "items", java.util.List.of(),
                    "totalItemCount", 0, "subtotal", "0")));
    }

    public Mono<Map> addItem(String customerId, Map<String, Object> item) {
        return serviceTokenProvider.getToken()
            .flatMap(token -> webClient.post()
                .uri("/api/v1/carts/{customerId}/items", customerId)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(item)
                .retrieve()
                .bodyToMono(Map.class));
    }
}
