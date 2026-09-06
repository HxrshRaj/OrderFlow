package com.orderflow.inventory.repository;

import com.orderflow.inventory.domain.InventoryItem;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface InventoryItemRepository extends JpaRepository<InventoryItem, Long> {

    Optional<InventoryItem> findBySku(String sku);

    List<InventoryItem> findBySkuInOrderBySku(List<String> skus);

    /**
     * Atomically hold {@code qty} units of {@code sku}, but only if that many are available.
     *
     * <p>This is the core of the oversell defence. It is a single statement, so PostgreSQL
     * holds a row-level lock for its duration and the {@code available_quantity >= :qty}
     * predicate is evaluated against a committed value. Two callers racing for the last unit
     * are serialised on the row: the first wins (returns 1), the second sees the updated
     * value and is rejected (returns 0). No lost update, no negative stock.
     *
     * @return number of rows updated: 1 = held, 0 = insufficient stock or unknown SKU
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE InventoryItem i
               SET i.availableQuantity = i.availableQuantity - :qty,
                   i.reservedQuantity  = i.reservedQuantity  + :qty
             WHERE i.sku = :sku
               AND i.availableQuantity >= :qty
            """)
    int reserve(@Param("sku") String sku, @Param("qty") int qty);

    /**
     * Convert an existing hold into a physical deduction (order shipped): reserved drops,
     * on-hand drops with it. Guarded by {@code reserved_quantity >= :qty} so a double
     * commit is a no-op (returns 0) rather than driving reserved negative.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE InventoryItem i
               SET i.reservedQuantity = i.reservedQuantity - :qty
             WHERE i.sku = :sku
               AND i.reservedQuantity >= :qty
            """)
    int commitReservation(@Param("sku") String sku, @Param("qty") int qty);

    /**
     * Return a hold to available stock (order cancelled / failed / hold expired).
     * Guarded so a double release is a no-op.
     */
    @Modifying(flushAutomatically = true)
    @Query("""
            UPDATE InventoryItem i
               SET i.availableQuantity = i.availableQuantity + :qty,
                   i.reservedQuantity  = i.reservedQuantity  - :qty
             WHERE i.sku = :sku
               AND i.reservedQuantity >= :qty
            """)
    int releaseReservation(@Param("sku") String sku, @Param("qty") int qty);
}
