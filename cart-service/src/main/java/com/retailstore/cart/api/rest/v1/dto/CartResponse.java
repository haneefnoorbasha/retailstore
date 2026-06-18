package com.retailstore.cart.api.rest.v1.dto;

import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CartResponse {
    private String customerId;
    private List<CartItemResponse> items;
    private int totalItemCount;
    private int lineItemCount;
    private BigDecimal subtotal;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CartItemResponse {
        private String itemId;
        private String productId;
        private String productName;
        private String imageUrl;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }
}
