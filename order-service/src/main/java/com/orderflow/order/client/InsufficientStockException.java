package com.orderflow.order.client;

import java.util.List;
import java.util.UUID;

/**
 * The Inventory Service definitively rejected the reservation (HTTP 409). This is a business
 * outcome, not an infrastructure failure: it is never retried and never trips the circuit
 * breaker (see {@code resilience4j.*.ignore-exceptions} in application.yml).
 */
public class InsufficientStockException extends RuntimeException {

    private final UUID reservationId;
    private final transient List<Shortfall> shortfalls;

    public InsufficientStockException(UUID reservationId, List<Shortfall> shortfalls) {
        super("inventory rejected reservation %s (%d shortfall line(s))".formatted(reservationId, shortfalls.size()));
        this.reservationId = reservationId;
        this.shortfalls = List.copyOf(shortfalls);
    }

    public UUID getReservationId() {
        return reservationId;
    }

    public List<Shortfall> getShortfalls() {
        return shortfalls;
    }

    public String summary() {
        if (shortfalls.isEmpty()) {
            return "insufficient stock";
        }
        return shortfalls.stream()
                .map(s -> "%s (requested %d, available %d)".formatted(s.sku(), s.requested(), s.available()))
                .reduce((a, b) -> a + "; " + b)
                .orElse("insufficient stock");
    }
}
