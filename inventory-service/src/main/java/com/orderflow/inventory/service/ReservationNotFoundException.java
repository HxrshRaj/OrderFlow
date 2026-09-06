package com.orderflow.inventory.service;

import java.util.UUID;

public class ReservationNotFoundException extends RuntimeException {

    public ReservationNotFoundException(UUID reservationId) {
        super("reservation not found: " + reservationId);
    }
}
