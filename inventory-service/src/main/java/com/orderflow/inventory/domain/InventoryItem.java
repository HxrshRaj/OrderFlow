package com.orderflow.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.Instant;

/**
 * Stock position for a single SKU.
 *
 * <p>Physical on-hand quantity is {@code availableQuantity + reservedQuantity}. Only
 * {@code availableQuantity} is sellable; {@code reservedQuantity} is held against
 * confirmed-but-not-yet-shipped orders.
 *
 * <p>The hot path (reserve / commit / release) mutates this row through a single
 * conditional UPDATE in {@code InventoryItemRepository} and never loads the entity to
 * change quantities. The {@link Version} field guards the cold path only
 * ({@link #applyDelta(int)} for admin restock), where a genuine read-modify-write happens.
 */
@Entity
@Table(name = "inventory_item")
public class InventoryItem {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, updatable = false)
    private String sku;

    @Column(nullable = false)
    private String name;

    @Column(name = "available_quantity", nullable = false)
    private int availableQuantity;

    @Column(name = "reserved_quantity", nullable = false)
    private int reservedQuantity;

    @Version
    @Column(nullable = false)
    private long version;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected InventoryItem() {
        // for JPA
    }

    public InventoryItem(String sku, String name, int availableQuantity) {
        if (availableQuantity < 0) {
            throw new IllegalArgumentException("availableQuantity must not be negative");
        }
        this.sku = sku;
        this.name = name;
        this.availableQuantity = availableQuantity;
        this.reservedQuantity = 0;
    }

    /**
     * Adjust on-hand stock by {@code delta} (positive = restock, negative = shrinkage/correction).
     * Runs on the optimistic-locked path: concurrent adjustments collide on {@link #version}
     * and the caller retries.
     *
     * @throws IllegalStockAdjustmentException if the change would make available stock negative
     */
    public void applyDelta(int delta) {
        long result = (long) this.availableQuantity + delta;
        if (result < 0) {
            throw new IllegalStockAdjustmentException(
                    "adjustment of %d would take %s below zero (available=%d)".formatted(delta, sku, availableQuantity));
        }
        this.availableQuantity = (int) result;
    }

    public Long getId() {
        return id;
    }

    public String getSku() {
        return sku;
    }

    public String getName() {
        return name;
    }

    public int getAvailableQuantity() {
        return availableQuantity;
    }

    public int getReservedQuantity() {
        return reservedQuantity;
    }

    public long getVersion() {
        return version;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
