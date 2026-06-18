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
