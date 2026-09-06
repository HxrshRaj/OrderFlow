package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.InventoryItem;
import com.orderflow.inventory.repository.InventoryItemRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.SimpleTransactionStatus;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class InventoryServiceTest {

    @Mock
    InventoryItemRepository repository;
    @Mock
    PlatformTransactionManager transactionManager;

    InventoryService service;

    @BeforeEach
    void setUp() {
        when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
        service = new InventoryService(repository, transactionManager, 3);
    }

    @Test
    void adjustStock_applies_delta_and_persists() {
        InventoryItem item = new InventoryItem("SKU-1", "Thing", 5);
        when(repository.findBySku("SKU-1")).thenReturn(Optional.of(item));
        when(repository.saveAndFlush(any(InventoryItem.class))).thenAnswer(inv -> inv.getArgument(0));

        InventoryItem result = service.adjustStock("SKU-1", 4);

        assertThat(result.getAvailableQuantity()).isEqualTo(9);
        verify(repository).saveAndFlush(item);
    }

    @Test
    void adjustStock_retries_on_optimistic_lock_conflict_then_succeeds() {
        when(repository.findBySku("SKU-1"))
                .thenReturn(Optional.of(new InventoryItem("SKU-1", "Thing", 5)))
                .thenReturn(Optional.of(new InventoryItem("SKU-1", "Thing", 5)))
                .thenReturn(Optional.of(new InventoryItem("SKU-1", "Thing", 5)));
        when(repository.saveAndFlush(any(InventoryItem.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(InventoryItem.class, 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException(InventoryItem.class, 1L))
                .thenAnswer(inv -> inv.getArgument(0));

        InventoryItem result = service.adjustStock("SKU-1", 2);

        assertThat(result.getAvailableQuantity()).isEqualTo(7);
        verify(repository, times(3)).saveAndFlush(any(InventoryItem.class));
    }

    @Test
    void adjustStock_gives_up_after_max_retries() {
        when(repository.findBySku("SKU-1")).thenReturn(Optional.of(new InventoryItem("SKU-1", "Thing", 5)));
        when(repository.saveAndFlush(any(InventoryItem.class)))
                .thenThrow(new ObjectOptimisticLockingFailureException(InventoryItem.class, 1L));

        assertThatExceptionOfType(ConcurrentAdjustmentException.class)
                .isThrownBy(() -> service.adjustStock("SKU-1", 2));

        verify(repository, times(3)).saveAndFlush(any(InventoryItem.class));
    }

    @Test
    void create_rejects_a_duplicate_sku() {
        when(repository.findBySku("SKU-1")).thenReturn(Optional.of(new InventoryItem("SKU-1", "Thing", 1)));

        assertThatExceptionOfType(DuplicateSkuException.class)
                .isThrownBy(() -> service.create("SKU-1", "Thing", 10));
    }

    @Test
    void getBySku_throws_when_missing() {
        when(repository.findBySku("nope")).thenReturn(Optional.empty());

        assertThatExceptionOfType(InventoryItemNotFoundException.class)
                .isThrownBy(() -> service.getBySku("nope"));
    }
}
