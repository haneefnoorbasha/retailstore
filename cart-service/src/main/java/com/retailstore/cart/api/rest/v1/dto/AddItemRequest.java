package com.retailstore.cart.api.rest.v1.dto;

import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class AddItemRequest {
    @NotBlank(message = "productId is required")
    private String productId;

    @NotBlank(message = "productName is required")
    private String productName;

    private String imageUrl;

    @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 100, message = "Quantity cannot exceed 100")
    private int quantity;

    @NotNull(message = "unitPrice is required")
    @DecimalMin(value = "0.01", message = "Price must be positive")
    private BigDecimal unitPrice;
}
