package com.orderflow.inventory.service;

import com.orderflow.inventory.cache.SkuCache;
import com.orderflow.inventory.domain.InventoryItem;
import com.orderflow.inventory.repository.InventoryItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceCacheTest {

    /** Minimal in-memory stand-in for the Redis cache. */
    static class MapCache implements SkuCache {
        final Map<String, ItemSnapshot> entries = new HashMap<>();

        @Override
        public Optional<ItemSnapshot> get(String sku) {
            return Optional.ofNullable(entries.get(sku));
        }

        @Override
        public void put(ItemSnapshot snapshot) {
            entries.put(snapshot.sku(), snapshot);
        }

        @Override
        public void evict(String sku) {
            entries.remove(sku);
        }
    }

    @Mock
    InventoryItemRepository repository;
    @Mock
    PlatformTransactionManager transactionManager;

    MapCache cache;
    InventoryService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        cache = new MapCache();
        service = new InventoryService(repository, transactionManager, 3, cache);
    }

    @Test
    void second_read_is_served_from_the_cache_without_touching_the_database() {
        when(repository.findBySku("SKU-1")).thenReturn(Optional.of(new InventoryItem("SKU-1", "Thing", 5)));

        ItemSnapshot first = service.getItem("SKU-1");
        ItemSnapshot second = service.getItem("SKU-1");

        assertThat(second).isEqualTo(first);
        assertThat(first.availableQuantity()).isEqualTo(5);
        verify(repository, times(1)).findBySku("SKU-1");
    }

    @Test
    void adjusting_stock_evicts_the_cached_item_so_the_next_read_sees_the_new_quantity() {
        InventoryItem item = new InventoryItem("SKU-1", "Thing", 5);
        when(repository.findBySku("SKU-1")).thenReturn(Optional.of(item));
        when(repository.saveAndFlush(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(service.getItem("SKU-1").availableQuantity()).isEqualTo(5);
        service.adjustStock("SKU-1", 4);

        assertThat(cache.entries).doesNotContainKey("SKU-1");
        assertThat(service.getItem("SKU-1").availableQuantity()).isEqualTo(9);
    }

    @Test
    void unknown_sku_is_not_cached_and_still_throws_not_found() {
        when(repository.findBySku("nope")).thenReturn(Optional.empty());

        assertThatExceptionOfType(InventoryItemNotFoundException.class).isThrownBy(() -> service.getItem("nope"));
        assertThatExceptionOfType(InventoryItemNotFoundException.class).isThrownBy(() -> service.getItem("nope"));

        assertThat(cache.entries).isEmpty();
        verify(repository, times(2)).findBySku("nope");
    }
}
