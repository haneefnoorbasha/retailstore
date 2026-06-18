package com.retailstore.order.domain.exception;
public class OrderNotCancellableException extends RuntimeException {
    public OrderNotCancellableException(String id, String status) {
        super("Order " + id + " cannot be cancelled in status: " + status);
    }
}
