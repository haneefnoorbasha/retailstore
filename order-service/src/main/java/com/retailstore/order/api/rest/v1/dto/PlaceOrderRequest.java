package com.retailstore.order.api.rest.v1.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class PlaceOrderRequest {

    @NotBlank(message = "customerId is required")
    private String customerId;

    private String checkoutSessionId;

    @NotEmpty(message = "At least one line item required")
    @Valid
    private List<LineItemRequest> lineItems;

    @Valid @NotNull
    private ShippingAddressRequest shippingAddress;

    @NotNull @DecimalMin("0.01")
    private BigDecimal subtotal;

    @NotNull @DecimalMin("0.00")
    private BigDecimal shippingCost;

    @NotNull @DecimalMin("0.01")
    private BigDecimal total;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class LineItemRequest {
        @NotBlank private String productId;
        @NotBlank private String productName;
        @Min(1) private int quantity;
        @NotNull @DecimalMin("0.01") private BigDecimal unitPrice;
    }

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class ShippingAddressRequest {
        @NotBlank private String fullName;
        @NotBlank private String addressLine1;
        private String addressLine2;
        @NotBlank private String city;
        @NotBlank private String state;
        @NotBlank private String postalCode;
        @NotBlank private String country;
    }
}
