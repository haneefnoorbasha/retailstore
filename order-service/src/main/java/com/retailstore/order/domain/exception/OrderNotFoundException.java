package com.retailstore.order.domain.exception;
public class OrderNotFoundException extends RuntimeException {
    public OrderNotFoundException(String id) { super("Order not found: " + id); }
}
