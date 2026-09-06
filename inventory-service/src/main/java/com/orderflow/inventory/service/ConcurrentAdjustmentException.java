package com.orderflow.inventory.service;

/** The optimistic-locked stock adjustment lost too many version races in a row. */
public class ConcurrentAdjustmentException extends RuntimeException {

    public ConcurrentAdjustmentException(String sku, int attempts) {
        super("could not adjust stock for %s after %d attempts due to concurrent updates".formatted(sku, attempts));
    }
}
