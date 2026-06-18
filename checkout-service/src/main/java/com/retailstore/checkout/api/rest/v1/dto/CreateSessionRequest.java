package com.retailstore.checkout.api.rest.v1.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.*;
import java.math.BigDecimal;
import java.util.List;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class CreateSessionRequest {

    @NotBlank(message = "customerId is required")
    private String customerId;

    @NotEmpty(message = "At least one line item is required")
    @Valid
    private List<LineItemDto> lineItems;

    @Getter @Setter @NoArgsConstructor @AllArgsConstructor
    public static class LineItemDto {
        @NotBlank private String productId;
        @NotBlank private String productName;
        @Min(1)   private int quantity;
        @NotNull @DecimalMin("0.01") private BigDecimal unitPrice;
    }
}
