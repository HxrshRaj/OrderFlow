package com.orderflow.order.domain.event;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * One concrete, internal consumer of {@link OrderShipped}: it turns "an order shipped" into an
 * observable metric, with zero coupling to the {@code Order} aggregate or {@code OrderService}
 * — neither of those knows this class exists. That decoupling is the actual point of a domain
 * event; a log line alone wouldn't demonstrate it.
 *
 * <p>Bound to {@link TransactionPhase#AFTER_COMMIT}: if the transaction that shipped the order
 * were to roll back for any reason, this never runs, so the counter can never over-count a
 * shipment that didn't really happen.
 *
 * <p>Exposed at {@code /actuator/metrics/orderflow.orders.shipped} (Actuator's metrics endpoint
 * is already enabled — see {@code application.yml}).
 */
@Component
public class OrderShippedMetricsListener {

    private static final Logger log = LoggerFactory.getLogger(OrderShippedMetricsListener.class);

    private final Counter shippedCounter;

    public OrderShippedMetricsListener(MeterRegistry registry) {
        this.shippedCounter = Counter.builder("orderflow.orders.shipped")
                .description("Number of orders that have completed the ship transition")
                .register(registry);
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderShipped(OrderShipped event) {
        shippedCounter.increment();
        log.info("domain event OrderShipped: order {} (reservation {}) shipped for customer {}",
                event.orderNumber(), event.reservationId(), event.customerId());
    }
}
