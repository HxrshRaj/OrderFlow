package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.ReservationStatus;

import java.util.UUID;

/** A lifecycle transition that is not allowed from the reservation's current state. */
public class IllegalReservationStateException extends RuntimeException {

    public IllegalReservationStateException(UUID reservationId, ReservationStatus current, String attemptedAction) {
        super("reservation %s is %s; cannot %s".formatted(reservationId, current, attemptedAction));
    }
}
