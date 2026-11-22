package com.orderflow.order.domain.event;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Raised by {@link com.orderflow.order.domain.Order#confirm()} the moment Inventory's
 * reservation succeeds and the order moves {@code PLACED -> CONFIRMED}. Same shape and the same
 * reason to exist as {@link OrderShipped}: the aggregate records that it confirmed, and has no
 * idea who — today, {@link com.orderflow.order.storage.ReceiptStorageListener} — reacts to that.
 *
 * <p>Published only after the transition has been durably committed (see
 * {@code OrderService#transition}), so a listener only ever sees a confirmation that genuinely
 * happened.
 */
public record OrderConfirmed(String orderNumber, UUID reservationId, String customerId, BigDecimal totalAmount) {
}
