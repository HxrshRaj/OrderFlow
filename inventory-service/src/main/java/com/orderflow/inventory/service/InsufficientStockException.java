package com.orderflow.inventory.service;

import java.util.List;
import java.util.UUID;

/**
 * Thrown when at least one line of a reservation cannot be held. Rolling this out of the
 * {@code reserve} transaction rolls back every line that <em>did</em> succeed, so a
 * reservation is all-or-nothing.
 */
public class InsufficientStockException extends RuntimeException {

    private final UUID reservationId;
    private final transient List<Shortfall> shortfalls;

    public InsufficientStockException(UUID reservationId, List<Shortfall> shortfalls) {
        super("reservation %s rejected: %d line(s) could not be held".formatted(reservationId, shortfalls.size()));
        this.reservationId = reservationId;
        this.shortfalls = List.copyOf(shortfalls);
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public List<Shortfall> getShortfalls() {
        return shortfalls;
    }
}
