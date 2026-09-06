package com.orderflow.inventory.web.dto;

/**
 * Change on-hand stock by {@code quantityDelta} (positive = restock, negative = correction).
 * Zero is rejected by the controller.
 */
public record AdjustStockRequest(int quantityDelta) {
}
