package com.orderflow.inventory.domain;

public enum ReservationStatus {
    /** Stock is held. Available decremented, reserved incremented. */
    CONFIRMED,
    /** Hold converted into a physical deduction (order shipped). Reserved decremented. */
    COMMITTED,
    /** Hold returned to available stock (order cancelled / failed / hold expired). */
    RELEASED
}
