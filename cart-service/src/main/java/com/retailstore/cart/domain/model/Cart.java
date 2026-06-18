package com.retailstore.cart.domain.model;

import lombok.*;
import software.amazon.awssdk.enhanced.dynamodb.mapper.annotations.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

@DynamoDbBean
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Cart {

    private String customerId;
    @Builder.Default
    private List<CartItem> items = new ArrayList<>();
    private Instant lastModified;

    @DynamoDbPartitionKey
    @DynamoDbAttribute("customerId")
    public String getCustomerId() { return customerId; }

    public BigDecimal getSubtotal() {
        return items.stream()
            .map(CartItem::getLineTotal)
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public int getTotalItemCount() {
        return items.stream().mapToInt(CartItem::getQuantity).sum();
    }

    public int getLineItemCount() { return items.size(); }

    public Optional<CartItem> findItem(String itemId) {
        return items.stream().filter(i -> i.getItemId().equals(itemId)).findFirst();
    }

    public Optional<CartItem> findItemByProductId(String productId) {
        return items.stream().filter(i -> i.getProductId().equals(productId)).findFirst();
    }
}
