package com.orderflow.order.client;

/** One line the Inventory Service could not hold, as reported back to the order caller. */
public record Shortfall(String sku, int requested, int available, String reason) {
}
