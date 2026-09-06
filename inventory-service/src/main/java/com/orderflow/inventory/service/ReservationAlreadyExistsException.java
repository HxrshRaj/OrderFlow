package com.orderflow.inventory.service;

import java.util.UUID;

/**
 * Signals that a reservation with this id is already committed. The caller treats it as an
 * idempotent replay: re-read the existing reservation and return it unchanged.
 */
public class ReservationAlreadyExistsException extends RuntimeException {

    private final UUID reservationId;

    public ReservationAlreadyExistsException(UUID reservationId) {
        super("reservation already exists: " + reservationId);
        this.reservationId = reservationId;
    }

    public UUID getReservationId() {
        return reservationId;
    }
}
