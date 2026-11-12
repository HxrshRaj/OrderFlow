package com.orderflow.inventory.web.dto;

import com.orderflow.inventory.domain.InventoryItem;
import com.orderflow.inventory.service.ItemSnapshot;

import java.time.Instant;

public record InventoryItemResponse(
        String sku,
        String name,
        int availableQuantity,
        int reservedQuantity,
        int onHandQuantity,
        Instant updatedAt) {

    public static InventoryItemResponse from(InventoryItem item) {
        return new InventoryItemResponse(
                item.getSku(),
                item.getName(),
                item.getAvailableQuantity(),
                item.getReservedQuantity(),
                item.getAvailableQuantity() + item.getReservedQuantity(),
                item.getUpdatedAt());
    }

    public static InventoryItemResponse from(ItemSnapshot item) {
        return new InventoryItemResponse(
                item.sku(),
                item.name(),
                item.availableQuantity(),
                item.reservedQuantity(),
                item.availableQuantity() + item.reservedQuantity(),
                item.updatedAt());
    }
}
