package com.orderflow.order.domain;

/** An order lifecycle transition that is not allowed from the current status. */
public class IllegalOrderStateException extends RuntimeException {

    public IllegalOrderStateException(String orderNumber, OrderStatus current, String attemptedAction) {
        super("order %s is %s; cannot %s".formatted(orderNumber, current, attemptedAction));
    }
}
