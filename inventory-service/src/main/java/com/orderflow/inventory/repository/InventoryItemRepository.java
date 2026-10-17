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
     * Catalogue search. A row matches when its name matches the query as a full-text prefix
     * ("mou" finds "Mouse"), is trigram-similar to it ("keybord" finds "Keyboard"), or the SKU
     * contains it. Ranked by full-text rank (weighted 2x) plus word similarity, then by name so
     * ordering is deterministic. {@code tsQuery} and {@code q} must come from
     * {@code SearchQuery}, never raw user input.
     */
    @Query(value = """
            SELECT i.*
              FROM inventory_item i
             WHERE (to_tsvector('simple', i.name) @@ to_tsquery('simple', :tsQuery)
                    OR word_similarity(:q, i.name) >= :minSimilarity
                    OR lower(i.sku) LIKE '%' || replace(:q, ' ', '-') || '%')
               AND (:inStockOnly = false OR i.available_quantity > 0)
             ORDER BY (ts_rank(to_tsvector('simple', i.name), to_tsquery('simple', :tsQuery)) * 2
                       + word_similarity(:q, i.name)) DESC,
                      i.name
             LIMIT :limit
            """, nativeQuery = true)
    List<InventoryItem> search(@Param("q") String q,
                               @Param("tsQuery") String tsQuery,
                               @Param("minSimilarity") double minSimilarity,
                               @Param("inStockOnly") boolean inStockOnly,
                               @Param("limit") int limit);


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
                   i.reservedQuantity  = i.reservedQuantity  + :qty,
                   i.updatedAt         = CURRENT_TIMESTAMP
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
               SET i.reservedQuantity = i.reservedQuantity - :qty,
                   i.updatedAt        = CURRENT_TIMESTAMP
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
                   i.reservedQuantity  = i.reservedQuantity  - :qty,
                   i.updatedAt         = CURRENT_TIMESTAMP
             WHERE i.sku = :sku
               AND i.reservedQuantity >= :qty
            """)
    int releaseReservation(@Param("sku") String sku, @Param("qty") int qty);
}
