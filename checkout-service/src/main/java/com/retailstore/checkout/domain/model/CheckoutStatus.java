package com.retailstore.checkout.domain.model;

public enum CheckoutStatus {
    ACTIVE,       // session open, customer still filling in details
    SUBMITTED,    // order placed — session becomes read-only
    EXPIRED,      // TTL elapsed before order placed
    ABANDONED     // customer explicitly abandoned
}
