package com.retailstore.identity.api.rest.v1.dto;

import jakarta.validation.constraints.*;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class RegisterRequest {
    @NotBlank @Email(message = "Must be a valid email")
    private String email;

    @NotBlank @Size(min = 3, max = 50, message = "Username must be 3-50 characters")
    private String username;

    @NotBlank @Size(min = 8, message = "Password must be at least 8 characters")
    private String password;

    @NotBlank @Size(max = 80)
    private String fullName;
}
