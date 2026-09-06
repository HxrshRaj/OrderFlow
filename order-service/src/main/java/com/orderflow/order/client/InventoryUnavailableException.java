package com.orderflow.order.client;

/**
 * The Inventory Service could not be reached, or answered with a server error / timeout.
 * This <em>is</em> retryable: Resilience4j retries it, and repeated failures open the circuit.
 * When it finally propagates, the order is left in PLACED for the reconciler to pick up.
 */
public class InventoryUnavailableException extends RuntimeException {

    public InventoryUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
