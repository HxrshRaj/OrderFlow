package com.orderflow.order.domain;

public enum OrderStatus {
    /** Order persisted; stock reservation not yet confirmed. */
    PLACED,
    /** Stock reserved in the Inventory Service. */
    CONFIRMED,
    /** Reservation committed; order dispatched. */
    SHIPPED,
    /** Stock could not be reserved. Terminal. */
    REJECTED,
    /** Cancelled after placement; any hold has been released. Terminal. */
    CANCELLED
}
