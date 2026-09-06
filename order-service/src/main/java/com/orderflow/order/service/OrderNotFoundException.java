package com.orderflow.order.service;

public class OrderNotFoundException extends RuntimeException {

    public OrderNotFoundException(String orderNumber) {
        super("order not found: " + orderNumber);
    }

    public OrderNotFoundException(Long orderId) {
        super("order not found: id=" + orderId);
    }
}
