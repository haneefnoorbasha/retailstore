package com.retailstore.order.api.rest.v1.dto;

import com.retailstore.order.domain.model.OrderStatus;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class OrderResponse {
    private String id;
    private String customerId;
    private OrderStatus status;
    private List<LineItemResponse> lineItems;
    private ShippingAddressResponse shippingAddress;
    private BigDecimal subtotal;
    private BigDecimal shippingCost;
    private BigDecimal total;
    private boolean cancellable;
    private String cancellationReason;
    private Instant createdAt;
    private Instant updatedAt;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LineItemResponse {
        private String productId;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ShippingAddressResponse {
        private String fullName;
        private String addressLine1;
        private String addressLine2;
        private String city;
        private String state;
        private String postalCode;
        private String country;
    }
}
