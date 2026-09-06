package com.orderflow.inventory.scheduler;

import com.orderflow.inventory.service.ReservationService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically releases CONFIRMED holds whose TTL has passed, so a crashed or partitioned
 * Order Service cannot strand stock indefinitely. Disabled in tests via
 * {@code inventory.reservation.sweep-enabled=false}.
 */
@Component
@ConditionalOnProperty(name = "inventory.reservation.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class ReservationExpiryJob {

    private final ReservationService reservationService;

    public ReservationExpiryJob(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @Scheduled(fixedDelayString = "${inventory.reservation.sweep-interval:PT1M}")
    public void releaseExpiredHolds() {
        reservationService.sweepExpired();
    }
}
