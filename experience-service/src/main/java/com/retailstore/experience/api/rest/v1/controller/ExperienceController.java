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
