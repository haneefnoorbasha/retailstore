package com.retailstore.checkout.domain.exception;
public class CheckoutSessionNotSubmittableException extends RuntimeException {
    public CheckoutSessionNotSubmittableException(String reason) {
        super("Checkout session cannot be submitted: " + reason);
    }
}
