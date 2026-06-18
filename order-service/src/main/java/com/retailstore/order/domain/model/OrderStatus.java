package com.retailstore.order.domain.model;

public enum OrderStatus {
    PENDING,       // just placed
    CONFIRMED,     // payment captured
    PROCESSING,    // picking and packing
    SHIPPED,       // dispatched
    DELIVERED,     // confirmed delivered
    CANCELLED,     // cancelled before dispatch
    REFUNDED       // returned and refunded
}
