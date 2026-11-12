package com.orderflow.inventory.cache;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Collection;

/**
 * Evicts cached SKUs once the surrounding database transaction has committed. Evicting before
 * commit would let a concurrent read re-cache the old row; with no active transaction the
 * eviction happens immediately.
 */
@Component
public class CacheEvictions {

    private final SkuCache cache;

    public CacheEvictions(SkuCache cache) {
        this.cache = cache;
    }

    public void afterCommit(Collection<String> skus) {
        if (skus.isEmpty()) {
            return;
        }
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    skus.forEach(cache::evict);
                }
            });
        } else {
            skus.forEach(cache::evict);
        }
    }
}
