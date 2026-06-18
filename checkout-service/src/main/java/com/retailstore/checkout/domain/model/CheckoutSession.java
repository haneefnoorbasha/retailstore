package com.retailstore.checkout.domain.model;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.*;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Checkout session — stored in Redis with TTL.
 * Holds everything the customer has committed to before placing the order.
 * Immutable once submitted (status = SUBMITTED).
 */
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class CheckoutSession implements Serializable {

    private String sessionId;
    private String customerId;
    private CheckoutStatus status;

    @Builder.Default
    private List<CheckoutLineItem> lineItems = new ArrayList<>();

    private ShippingDetails shippingDetails;
    private PriceSummary priceSummary;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant createdAt;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant expiresAt;

    private String submittedOrderId;

    public boolean isExpired() {
        return expiresAt != null && Instant.now().isAfter(expiresAt);
    }

    public boolean isSubmittable() {
        return status == CheckoutStatus.ACTIVE
            && !isExpired()
            && lineItems != null && !lineItems.isEmpty()
            && shippingDetails != null;
    }

    // ── Nested value objects ────────────────────────────────────────────────

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class CheckoutLineItem implements Serializable {
        private String productId;
        private String productName;
        private int quantity;
        private BigDecimal unitPrice;

        public BigDecimal lineTotal() {
            return unitPrice != null ? unitPrice.multiply(BigDecimal.valueOf(quantity)) : BigDecimal.ZERO;
        }
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class ShippingDetails implements Serializable {
        private String fullName;
        private String addressLine1;
        private String addressLine2;
        private String city;
        private String state;
        private String postalCode;
        private String country;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
    public static class PriceSummary implements Serializable {
        private BigDecimal subtotal;
        private BigDecimal shippingCost;
        private BigDecimal taxAmount;
        private BigDecimal total;
    }
}
