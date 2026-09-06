package com.orderflow.order.scheduler;

import com.orderflow.order.service.OrderService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Drives orders out of {@code PLACED} when the reservation call failed at placement time
 * (Inventory slow, down, or the circuit was open). Retries are safe because each order keeps
 * its original {@code reservationId} and the Inventory Service deduplicates on it.
 */
@Component
@ConditionalOnProperty(name = "order.reconciler.enabled", havingValue = "true", matchIfMissing = true)
public class PendingOrderReconciler {

    private final OrderService orderService;
    private final Duration staleAfter;

    public PendingOrderReconciler(OrderService orderService,
                                  @Value("${order.reconciler.stale-after:PT10S}") Duration staleAfter) {
        this.orderService = orderService;
        this.staleAfter = staleAfter;
    }

    @Scheduled(fixedDelayString = "${order.reconciler.interval:PT15S}")
    public void reconcile() {
        orderService.reconcileStaleOrders(staleAfter);
    }
}
