package com.retailstore.cart.domain.exception;

public class CartItemNotFoundException extends RuntimeException {
    public CartItemNotFoundException(String itemId) {
        super("Cart item not found: " + itemId);
    }
}
