package com.retailstore.identity.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Entity
@Table(name = "user_accounts",
       indexes = {
           @Index(name = "idx_user_email",    columnList = "email",    unique = true),
           @Index(name = "idx_user_username", columnList = "username", unique = true)
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserAccount {

    @Id
    @Column(length = 36)
    private String id;

    @Column(unique = true, nullable = false, length = 100)
    private String email;

    @Column(unique = true, nullable = false, length = 50)
    private String username;

    @Column(nullable = false)
    private String passwordHash;

    @Column(nullable = false, length = 80)
    private String fullName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private UserRole role = UserRole.CUSTOMER;

    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    @Column(nullable = false)
    @Builder.Default
    private boolean emailVerified = false;

    @CreationTimestamp private Instant createdAt;
    @UpdateTimestamp  private Instant updatedAt;

    public boolean isActive() { return enabled; }
}
