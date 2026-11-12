package com.orderflow.inventory.cache;

import com.orderflow.inventory.service.ItemSnapshot;

import java.util.Optional;

/** Used when {@code inventory.cache.enabled} is false: every read goes to the database. */
public class NoopSkuCache implements SkuCache {

    @Override
    public Optional<ItemSnapshot> get(String sku) {
        return Optional.empty();
    }

    @Override
    public void put(ItemSnapshot snapshot) {
        // intentionally nothing
    }

    @Override
    public void evict(String sku) {
        // intentionally nothing
    }
}
