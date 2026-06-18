package com.retailstore.experience.infrastructure.client;

import com.retailstore.experience.infrastructure.security.ServiceTokenProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpHeaders;
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
    private final ServiceTokenProvider serviceTokenProvider;

    public Mono<Map> getProducts(int page, int size, String tags) {
        return serviceTokenProvider.getToken()
            .flatMap(token -> webClient.get()
                .uri(u -> u.path("/api/v1/catalog/products")
                    .queryParam("page", page).queryParam("size", size)
                    .queryParamIfPresent("tags", java.util.Optional.ofNullable(tags))
                    .build())
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("catalog getProducts error", e))
                .onErrorReturn(Map.of("products", java.util.List.of())));
    }

    public Mono<Map> getProduct(String id) {
        return serviceTokenProvider.getToken()
            .flatMap(token -> webClient.get()
                .uri("/api/v1/catalog/products/{id}", id)
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(Map.class)
                .doOnError(e -> log.error("catalog getProduct {} error", id, e)));
    }

    public Mono<java.util.List> getTags() {
        return serviceTokenProvider.getToken()
            .flatMap(token -> webClient.get()
                .uri("/api/v1/catalog/tags")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
                .retrieve()
                .bodyToMono(java.util.List.class)
                .onErrorReturn(java.util.List.of()));
    }
}
