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
