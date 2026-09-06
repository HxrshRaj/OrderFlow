package com.orderflow.inventory.service;

public class DuplicateSkuException extends RuntimeException {

    public DuplicateSkuException(String sku) {
        super("inventory item already exists: " + sku);
    }
}
