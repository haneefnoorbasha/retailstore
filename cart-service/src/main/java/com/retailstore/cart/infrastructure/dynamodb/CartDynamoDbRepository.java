package com.retailstore.cart.infrastructure.dynamodb;

import com.retailstore.cart.domain.model.Cart;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import software.amazon.awssdk.enhanced.dynamodb.DynamoDbTable;
import software.amazon.awssdk.enhanced.dynamodb.Key;
import java.util.Optional;

@Slf4j
@Repository
@RequiredArgsConstructor
public class CartDynamoDbRepository {

    private final DynamoDbTable<Cart> cartTable;

    public Optional<Cart> findByCustomerId(String customerId) {
        try {
            Cart cart = cartTable.getItem(Key.builder().partitionValue(customerId).build());
            return Optional.ofNullable(cart);
        } catch (Exception e) {
            log.error("Error reading cart for customer {}", customerId, e);
            return Optional.empty();
        }
    }

    public Cart save(Cart cart) {
        cartTable.putItem(cart);
        return cart;
    }

    public void deleteByCustomerId(String customerId) {
        cartTable.deleteItem(Key.builder().partitionValue(customerId).build());
    }
}
