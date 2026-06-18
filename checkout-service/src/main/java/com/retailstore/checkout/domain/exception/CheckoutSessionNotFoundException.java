package com.retailstore.checkout.domain.exception;
public class CheckoutSessionNotFoundException extends RuntimeException {
    public CheckoutSessionNotFoundException(String id) {
        super("Checkout session not found or expired: " + id);
    }
}
