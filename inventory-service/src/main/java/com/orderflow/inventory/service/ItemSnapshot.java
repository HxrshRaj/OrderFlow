package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.InventoryItem;

import java.time.Instant;

/** Immutable view of one stock item; this is what is cached (never the JPA entity). */
public record ItemSnapshot(
        String sku,
        String name,
        int availableQuantity,
        int reservedQuantity,
        Instant updatedAt) {

    public static ItemSnapshot from(InventoryItem item) {
        return new ItemSnapshot(
                item.getSku(),
                item.getName(),
                item.getAvailableQuantity(),
                item.getReservedQuantity(),
                item.getUpdatedAt());
    }
}
