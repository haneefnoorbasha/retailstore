package com.retailstore.order.domain.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "orders",
       indexes = {
           @Index(name = "idx_order_customer",    columnList = "customerId"),
           @Index(name = "idx_order_status",      columnList = "status"),
           @Index(name = "idx_order_created_at",  columnList = "createdAt")
       })
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Order {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 36)
    private String customerId;

    @Column(length = 36)
    private String checkoutSessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private OrderStatus status;

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "order_id")
    @Builder.Default
    private List<OrderLineItem> lineItems = new ArrayList<>();

    @Embedded
    private ShippingAddress shippingAddress;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal subtotal;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal shippingCost;

    @Column(nullable = false, precision = 10, scale = 2)
    private BigDecimal total;

    @Column(length = 500)
    private String cancellationReason;

    @CreationTimestamp
    private Instant createdAt;

    @UpdateTimestamp
    private Instant updatedAt;

    public boolean isCancellable() {
        return status == OrderStatus.PENDING || status == OrderStatus.CONFIRMED;
    }

    public boolean isShipped() {
        return status == OrderStatus.SHIPPED || status == OrderStatus.DELIVERED;
    }
}
