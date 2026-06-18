package com.retailstore.cart.api.rest.v1.controller;

import com.retailstore.cart.api.rest.v1.dto.*;
import com.retailstore.cart.application.service.CartService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/carts/{customerId}")
@RequiredArgsConstructor
@Tag(name = "Cart", description = "Shopping cart — add, update, remove items")
public class CartController {

    private final CartService cartService;

    @GetMapping
    @Operation(summary = "Get cart for a customer")
    public ResponseEntity<CartResponse> getCart(@PathVariable String customerId) {
        return ResponseEntity.ok(cartService.getCart(customerId));
    }

    @PostMapping("/items")
    @Operation(summary = "Add item to cart (merges if product already exists)")
    public ResponseEntity<CartResponse> addItem(
            @PathVariable String customerId,
            @Valid @RequestBody AddItemRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(cartService.addItem(customerId, request));
    }

    @PutMapping("/items/{itemId}")
    @Operation(summary = "Update item quantity (set to 0 to remove)")
    public ResponseEntity<CartResponse> updateItem(
            @PathVariable String customerId,
            @PathVariable String itemId,
            @Valid @RequestBody UpdateItemRequest request) {
        return ResponseEntity.ok(cartService.updateItem(customerId, itemId, request));
    }

    @DeleteMapping("/items/{itemId}")
    @Operation(summary = "Remove a specific item from cart")
    public ResponseEntity<Void> removeItem(
            @PathVariable String customerId,
            @PathVariable String itemId) {
        cartService.removeItem(customerId, itemId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping
    @Operation(summary = "Clear entire cart")
    public ResponseEntity<Void> clearCart(@PathVariable String customerId) {
        cartService.clearCart(customerId);
        return ResponseEntity.noContent().build();
    }
}
