package com.orderflow.inventory.cache;

import com.orderflow.inventory.service.ItemSnapshot;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class CacheEvictionsTest {

    static class RecordingCache implements SkuCache {
        final List<String> evicted = new ArrayList<>();

        @Override
        public Optional<ItemSnapshot> get(String sku) {
            return Optional.empty();
        }

        @Override
        public void put(ItemSnapshot snapshot) {
        }

        @Override
        public void evict(String sku) {
            evicted.add(sku);
        }
    }

    @Test
    void evicts_immediately_when_there_is_no_transaction() {
        RecordingCache cache = new RecordingCache();

        new CacheEvictions(cache).afterCommit(List.of("A", "B"));

        assertThat(cache.evicted).containsExactly("A", "B");
    }

    @Test
    void waits_for_commit_inside_a_transaction() {
        RecordingCache cache = new RecordingCache();
        TransactionSynchronizationManager.initSynchronization();
        try {
            new CacheEvictions(cache).afterCommit(List.of("A"));
            assertThat(cache.evicted).isEmpty();

            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCommit();
            }
            assertThat(cache.evicted).containsExactly("A");
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void a_rolled_back_transaction_never_evicts() {
        RecordingCache cache = new RecordingCache();
        TransactionSynchronizationManager.initSynchronization();
        try {
            new CacheEvictions(cache).afterCommit(List.of("A"));
            for (TransactionSynchronization sync : TransactionSynchronizationManager.getSynchronizations()) {
                sync.afterCompletion(TransactionSynchronization.STATUS_ROLLED_BACK);
            }
            assertThat(cache.evicted).isEmpty();
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
