package com.retailstore.identity.api.rest.v1.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor
public class LoginRequest {
    @NotBlank private String email;
    @NotBlank private String password;
}
