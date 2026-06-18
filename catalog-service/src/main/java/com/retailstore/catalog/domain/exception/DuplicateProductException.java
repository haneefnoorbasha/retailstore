package com.retailstore.catalog.domain.exception;

public class DuplicateProductException extends RuntimeException {
    public DuplicateProductException(String id) {
        super("Product already exists with id: " + id);
    }
}
