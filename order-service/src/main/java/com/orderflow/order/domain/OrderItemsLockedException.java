package com.orderflow.order.domain;

/**
 * Raised by the {@link Order} aggregate itself when a caller tries to add a line item after
 * the order has left {@code PLACED} (i.e. after the order was confirmed, rejected, shipped,
 * or cancelled). Line composition only makes sense while the order is still being placed;
 * once it starts down the reservation/shipment path its contents are locked.
 *
 * <p>This is enforced by {@link Order#addItem} itself, not by service-layer discipline: no
 * caller, present or future, can put an order into an inconsistent state by adding items too
 * late, because the aggregate refuses to allow it.
 */
public class OrderItemsLockedException extends RuntimeException {

    public OrderItemsLockedException(String orderNumber, OrderStatus currentStatus) {
        super("order %s no longer accepts new line items: status is %s, not PLACED"
                .formatted(orderNumber, currentStatus));
    }
}
