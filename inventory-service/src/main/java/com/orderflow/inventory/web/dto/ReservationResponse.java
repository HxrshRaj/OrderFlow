package com.orderflow.inventory.web.dto;

import com.orderflow.inventory.domain.Reservation;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record ReservationResponse(
        UUID reservationId,
        String status,
        List<ReservationLineResponse> lines,
        Instant expiresAt,
        boolean replayed) {

    public static ReservationResponse from(Reservation reservation, boolean replayed) {
        return new ReservationResponse(
                reservation.getReservationId(),
                reservation.getStatus().name(),
                reservation.getLines().stream().map(ReservationLineResponse::from).toList(),
                reservation.getExpiresAt(),
                replayed);
    }
}
