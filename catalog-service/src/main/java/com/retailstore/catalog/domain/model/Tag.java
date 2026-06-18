package com.retailstore.catalog.domain.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "tags",
       indexes = @Index(name = "idx_tag_name", columnList = "name"))
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Tag {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false, length = 60)
    private String name;

    @Column(nullable = false, length = 100)
    private String displayName;
}
