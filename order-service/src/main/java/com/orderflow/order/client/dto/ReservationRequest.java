package com.orderflow.order.client.dto;

import java.util.List;
import java.util.UUID;

/** Body of {@code POST /api/v1/reservations} on the Inventory Service. */
public record ReservationRequest(UUID reservationId, List<ReservationLinePayload> lines) {
}
