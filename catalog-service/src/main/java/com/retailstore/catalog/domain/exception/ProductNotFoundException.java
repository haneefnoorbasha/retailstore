package com.retailstore.catalog.domain.exception;

public class ProductNotFoundException extends RuntimeException {
    private final String productId;

    public ProductNotFoundException(String productId) {
        super("Product not found with id: " + productId);
        this.productId = productId;
    }

    public String getProductId() { return productId; }
}
