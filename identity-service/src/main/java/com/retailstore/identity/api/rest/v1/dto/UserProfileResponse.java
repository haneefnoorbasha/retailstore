package com.retailstore.identity.api.rest.v1.dto;

import lombok.*;
import java.time.Instant;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserProfileResponse {
    private String id;
    private String email;
    private String username;
    private String fullName;
    private String role;
    private boolean emailVerified;
    private Instant createdAt;
}
