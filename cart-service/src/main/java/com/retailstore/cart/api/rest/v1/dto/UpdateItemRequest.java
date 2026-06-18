package com.retailstore.cart.api.rest.v1.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class UpdateItemRequest {
    @Min(value = 0, message = "Quantity cannot be negative")
    @Max(value = 100, message = "Quantity cannot exceed 100")
    private int quantity;
}
