package com.retailstore.cart.application.service;

import com.retailstore.cart.api.rest.v1.dto.*;
import com.retailstore.cart.domain.exception.CartItemNotFoundException;
import com.retailstore.cart.domain.model.*;
import com.retailstore.cart.infrastructure.dynamodb.CartDynamoDbRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class CartService {

    private final CartDynamoDbRepository cartRepository;

    public CartResponse getCart(String customerId) {
        Cart cart = cartRepository.findByCustomerId(customerId)
            .orElseGet(() -> Cart.builder().customerId(customerId).items(new ArrayList<>()).build());
        return toResponse(cart);
    }

    public CartResponse addItem(String customerId, AddItemRequest request) {
        Cart cart = cartRepository.findByCustomerId(customerId)
            .orElseGet(() -> Cart.builder().customerId(customerId).items(new ArrayList<>()).build());

        List<CartItem> items = new ArrayList<>(cart.getItems());

        // If same product already in cart, increase quantity
        cart.findItemByProductId(request.getProductId()).ifPresentOrElse(
            existing -> existing.setQuantity(existing.getQuantity() + request.getQuantity()),
            () -> items.add(CartItem.builder()
                .itemId(UUID.randomUUID().toString())
                .productId(request.getProductId())
                .productName(request.getProductName())
                .imageUrl(request.getImageUrl())
                .quantity(request.getQuantity())
                .unitPrice(request.getUnitPrice())
                .build())
        );

        cart.setItems(items);
        cart.setLastModified(Instant.now());
        cartRepository.save(cart);

        log.info("Added item productId={} qty={} to cart customerId={}", request.getProductId(), request.getQuantity(), customerId);
        return toResponse(cart);
    }

    public CartResponse updateItem(String customerId, String itemId, UpdateItemRequest request) {
        Cart cart = cartRepository.findByCustomerId(customerId)
            .orElseGet(() -> Cart.builder().customerId(customerId).items(new ArrayList<>()).build());

        List<CartItem> items = new ArrayList<>(cart.getItems());

        CartItem item = cart.findItem(itemId)
            .orElseThrow(() -> new CartItemNotFoundException(itemId));

        if (request.getQuantity() <= 0) {
            items.remove(item);
            log.info("Removed item itemId={} from cart customerId={}", itemId, customerId);
        } else {
            item.setQuantity(request.getQuantity());
            log.info("Updated item itemId={} qty={} in cart customerId={}", itemId, request.getQuantity(), customerId);
        }

        cart.setItems(items);
        cart.setLastModified(Instant.now());
        cartRepository.save(cart);
        return toResponse(cart);
    }

    public void removeItem(String customerId, String itemId) {
        Cart cart = cartRepository.findByCustomerId(customerId)
            .orElseGet(() -> Cart.builder().customerId(customerId).items(new ArrayList<>()).build());

        boolean removed = cart.getItems().removeIf(i -> i.getItemId().equals(itemId));
        if (!removed) throw new CartItemNotFoundException(itemId);

        cart.setLastModified(Instant.now());
        cartRepository.save(cart);
    }

    public void clearCart(String customerId) {
        cartRepository.deleteByCustomerId(customerId);
        log.info("Cleared cart for customerId={}", customerId);
    }

    private CartResponse toResponse(Cart cart) {
        List<CartResponse.CartItemResponse> itemResponses = cart.getItems().stream()
            .map(i -> CartResponse.CartItemResponse.builder()
                .itemId(i.getItemId())
                .productId(i.getProductId())
                .productName(i.getProductName())
                .imageUrl(i.getImageUrl())
                .quantity(i.getQuantity())
                .unitPrice(i.getUnitPrice())
                .lineTotal(i.getLineTotal())
                .build())
            .collect(Collectors.toList());

        return CartResponse.builder()
            .customerId(cart.getCustomerId())
            .items(itemResponses)
            .totalItemCount(cart.getTotalItemCount())
            .lineItemCount(cart.getLineItemCount())
            .subtotal(cart.getSubtotal())
            .build();
    }
}
