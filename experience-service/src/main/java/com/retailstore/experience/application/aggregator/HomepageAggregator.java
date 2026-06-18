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
