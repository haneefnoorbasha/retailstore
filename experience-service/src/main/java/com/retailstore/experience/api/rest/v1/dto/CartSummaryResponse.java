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
