package com.orderflow.inventory.service;

import com.orderflow.inventory.cache.CacheEvictions;
import com.orderflow.inventory.cache.SkuCache;
import com.orderflow.inventory.repository.InventoryItemRepository;
import com.orderflow.inventory.repository.ReservationRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationMetricsTest {

    @Mock
    ReservationRepository reservationRepository;
    @Mock
    InventoryItemRepository inventoryItemRepository;

    final List<String> evicted = new ArrayList<>();
    SimpleMeterRegistry meters;
    ReservationService service;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        SkuCache recording = new SkuCache() {
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
        };
        service = new ReservationService(reservationRepository, inventoryItemRepository,
                Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC), Duration.ofMinutes(15),
                new CacheEvictions(recording), meters);
    }

    private double outcome(String name) {
        return meters.counter("inventory.reservations", "outcome", name).count();
    }

    @Test
    void a_confirmed_reservation_is_counted_and_evicts_the_reserved_skus() {
        when(inventoryItemRepository.reserve(anyString(), anyInt())).thenReturn(1);

        service.reserve(UUID.randomUUID(), List.of(new LineCommand("SKU-A", 2), new LineCommand("SKU-B", 1)));

        assertThat(outcome("confirmed")).isEqualTo(1);
        assertThat(evicted).containsExactlyInAnyOrder("SKU-A", "SKU-B");
    }

    @Test
    void a_rejected_reservation_is_counted_and_evicts_nothing() {
        when(inventoryItemRepository.reserve(anyString(), anyInt())).thenReturn(0);
        when(inventoryItemRepository.findBySku("SKU-A")).thenReturn(Optional.empty());

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> service.reserve(UUID.randomUUID(), List.of(new LineCommand("SKU-A", 2))));

        assertThat(outcome("rejected")).isEqualTo(1);
        assertThat(outcome("confirmed")).isZero();
        assertThat(evicted).isEmpty();
    }
}
