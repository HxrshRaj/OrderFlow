package com.orderflow.inventory.domain;

/** Raised when a stock adjustment would drive available quantity below zero. */
public class IllegalStockAdjustmentException extends RuntimeException {

    public IllegalStockAdjustmentException(String message) {
        super(message);
    }
}
