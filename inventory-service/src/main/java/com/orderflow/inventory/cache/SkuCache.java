package com.orderflow.inventory.cache;

import com.orderflow.inventory.service.ItemSnapshot;

import java.util.Optional;

/**
 * Read-through cache port for single-SKU lookups. Implementations must never throw: a cache
 * failure must degrade to a database read, not to a failed request.
 */
public interface SkuCache {

    Optional<ItemSnapshot> get(String sku);

    void put(ItemSnapshot snapshot);

    void evict(String sku);
}
