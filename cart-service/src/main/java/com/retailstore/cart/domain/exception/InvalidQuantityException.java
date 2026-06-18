package com.retailstore.cart.domain.exception;

public class InvalidQuantityException extends RuntimeException {
    public InvalidQuantityException(int quantity) {
        super("Quantity must be between 1 and 100, got: " + quantity);
    }
}
