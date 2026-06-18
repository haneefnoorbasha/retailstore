package com.retailstore.catalog.api.rest.v1.dto.response;

import lombok.*;
import java.time.Instant;
import java.util.Map;

@Getter @Setter @Builder
public class ApiErrorResponse {
    private int status;
    private String error;
    private String message;
    private String path;
    @Builder.Default
    private Instant timestamp = Instant.now();
    private Map<String, String> fieldErrors;
}
