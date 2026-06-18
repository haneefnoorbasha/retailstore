package com.retailstore.catalog.api.rest.v1.dto.response;

import lombok.*;

@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class TagResponse {
    private Long id;
    private String name;
    private String displayName;
}
