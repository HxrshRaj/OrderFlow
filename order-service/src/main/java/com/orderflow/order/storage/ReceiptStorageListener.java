package com.orderflow.order.storage;

import com.orderflow.order.domain.Order;
import com.orderflow.order.domain.OrderItem;
import com.orderflow.order.domain.event.OrderConfirmed;
import com.orderflow.order.repository.OrderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.time.Instant;
import java.util.List;

/**
 * One concrete, internal consumer of {@link OrderConfirmed} — same shape and the same reason to
 * exist as {@link com.orderflow.order.domain.event.OrderShippedMetricsListener}: it turns "an
 * order confirmed" into a stored receipt, with zero coupling to the {@code Order} aggregate or
 * {@code OrderService} — neither of those knows this class, or S3, exist.
 *
 * <p>Bound to {@link TransactionPhase#AFTER_COMMIT}: if the transaction that confirmed the order
 * were to roll back, this never runs, so a receipt can never be uploaded for a confirmation that
 * didn't actually happen. Re-reads the order via {@link OrderRepository} rather than carrying
 * line items on the event itself — the event stays a small, stable fact ("this order confirmed"),
 * and reading the now-committed order back is safe and cheap at this point.
 */
@Component
public class ReceiptStorageListener {

    private static final Logger log = LoggerFactory.getLogger(ReceiptStorageListener.class);

    private final ReceiptStorageService receiptStorageService;
    private final OrderRepository orderRepository;

    public ReceiptStorageListener(ReceiptStorageService receiptStorageService, OrderRepository orderRepository) {
        this.receiptStorageService = receiptStorageService;
        this.orderRepository = orderRepository;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onOrderConfirmed(OrderConfirmed event) {
        if (!receiptStorageService.enabled()) {
            return;
        }
        List<OrderItem> items = orderRepository.findByOrderNumber(event.orderNumber())
                .map(Order::getItems)
                .orElse(List.of());
        String body = ReceiptJson.render(event, items, Instant.now());
        log.info("built receipt for order {} ({} item(s)); handing off to S3", event.orderNumber(), items.size());
        receiptStorageService.store(event.orderNumber(), body);
    }
}
