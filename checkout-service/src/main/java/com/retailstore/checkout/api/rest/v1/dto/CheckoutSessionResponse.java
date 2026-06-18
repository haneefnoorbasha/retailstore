package com.retailstore.checkout.api.rest.v1.dto;

import com.retailstore.checkout.domain.model.CheckoutSession;
import com.retailstore.checkout.domain.model.CheckoutStatus;
import lombok.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CheckoutSessionResponse {
    private String sessionId;
    private String customerId;
    private CheckoutStatus status;
    private List<LineItemDto> lineItems;
    private ShippingDto shippingDetails;
    private PriceSummaryDto priceSummary;
    private Instant createdAt;
    private Instant expiresAt;
    private boolean submittable;
    private String submittedOrderId;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class LineItemDto {
        private String productId; private String productName;
        private int quantity; private BigDecimal unitPrice; private BigDecimal lineTotal;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ShippingDto {
        private String fullName; private String addressLine1; private String addressLine2;
        private String city; private String state; private String postalCode; private String country;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PriceSummaryDto {
        private BigDecimal subtotal; private BigDecimal shippingCost;
        private BigDecimal taxAmount; private BigDecimal total;
    }
}
