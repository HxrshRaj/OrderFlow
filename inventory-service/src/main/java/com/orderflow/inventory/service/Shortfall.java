package com.orderflow.inventory.service;

/**
 * Why one line of a reservation could not be held.
 *
 * @param sku       the SKU that failed
 * @param requested units asked for
 * @param available units actually available at rejection time (0 for an unknown SKU)
 * @param reason    machine-readable cause
 */
public record Shortfall(String sku, int requested, int available, ShortfallReason reason) {
}
