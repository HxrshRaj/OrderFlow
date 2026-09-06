package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.InventoryItem;
import com.orderflow.inventory.domain.Reservation;
import com.orderflow.inventory.domain.ReservationStatus;
import com.orderflow.inventory.repository.InventoryItemRepository;
import com.orderflow.inventory.repository.ReservationRepository;
import com.orderflow.inventory.support.AbstractPostgresIntegrationTest;
import com.orderflow.inventory.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Reservation lifecycle against a real database: commit, release, idempotency, expiry sweep. */
class ReservationLifecycleIT extends AbstractPostgresIntegrationTest {

    @TestConfiguration
    static class FixedClockConfig {
        @Bean
        @Primary
        Clock testClock() {
            return new MutableClock(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    @Autowired
    ReservationService reservationService;
    @Autowired
    InventoryItemRepository inventoryItemRepository;
    @Autowired
    ReservationRepository reservationRepository;
    @Autowired
    Clock clock;

    private String sku;

    @BeforeEach
    void seed() {
        sku = "LC-" + UUID.randomUUID();
        inventoryItemRepository.save(new InventoryItem(sku, "Lifecycle Fixture", 20));
    }

    @AfterEach
    void cleanUp() {
        reservationRepository.deleteAll();
        inventoryItemRepository.findBySku(sku).ifPresent(inventoryItemRepository::delete);
    }

    @Test
    void reserve_then_commit_reduces_on_hand_stock() {
        UUID id = UUID.randomUUID();
        reservationService.reserve(id, List.of(new LineCommand(sku, 3)));

        InventoryItem afterReserve = inventoryItemRepository.findBySku(sku).orElseThrow();
        assertThat(afterReserve.getAvailableQuantity()).isEqualTo(17);
        assertThat(afterReserve.getReservedQuantity()).isEqualTo(3);

        reservationService.commit(id);

        InventoryItem afterCommit = inventoryItemRepository.findBySku(sku).orElseThrow();
        assertThat(afterCommit.getAvailableQuantity()).isEqualTo(17);
        assertThat(afterCommit.getReservedQuantity()).as("hold converted to a real deduction").isZero();
        assertThat(reservationRepository.findByReservationId(id).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.COMMITTED);
    }

    @Test
    void reserve_then_release_restores_available_stock() {
        UUID id = UUID.randomUUID();
        reservationService.reserve(id, List.of(new LineCommand(sku, 4)));

        reservationService.release(id);

        InventoryItem item = inventoryItemRepository.findBySku(sku).orElseThrow();
        assertThat(item.getAvailableQuantity()).isEqualTo(20);
        assertThat(item.getReservedQuantity()).isZero();
        assertThat(reservationRepository.findByReservationId(id).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
    }

    @Test
    void commit_is_idempotent_across_repeated_calls() {
        UUID id = UUID.randomUUID();
        reservationService.reserve(id, List.of(new LineCommand(sku, 2)));

        reservationService.commit(id);
        reservationService.commit(id);
        reservationService.commit(id);

        InventoryItem item = inventoryItemRepository.findBySku(sku).orElseThrow();
        assertThat(item.getAvailableQuantity()).isEqualTo(18);
        assertThat(item.getReservedQuantity()).isZero();
    }

    @Test
    void a_replayed_reserve_returns_the_original_without_touching_stock() {
        UUID id = UUID.randomUUID();
        reservationService.reserve(id, List.of(new LineCommand(sku, 5)));

        assertThatExceptionOfType(ReservationAlreadyExistsException.class)
                .isThrownBy(() -> reservationService.reserve(id, List.of(new LineCommand(sku, 5))));

        assertThat(inventoryItemRepository.findBySku(sku).orElseThrow().getAvailableQuantity())
                .as("second reserve with the same id must not decrement again").isEqualTo(15);
    }

    @Test
    void expiry_sweep_releases_holds_whose_ttl_has_passed() {
        UUID id = UUID.randomUUID();
        reservationService.reserve(id, List.of(new LineCommand(sku, 6)));
        assertThat(inventoryItemRepository.findBySku(sku).orElseThrow().getAvailableQuantity()).isEqualTo(14);

        // Nothing is expired yet.
        assertThat(reservationService.sweepExpired()).isZero();

        // Jump past the 15-minute TTL.
        ((MutableClock) clock).advance(Duration.ofMinutes(20));

        int released = reservationService.sweepExpired();

        assertThat(released).isEqualTo(1);
        InventoryItem item = inventoryItemRepository.findBySku(sku).orElseThrow();
        assertThat(item.getAvailableQuantity()).as("expired hold returned to available").isEqualTo(20);
        assertThat(item.getReservedQuantity()).isZero();
        assertThat(reservationRepository.findByReservationId(id).orElseThrow().getStatus())
                .isEqualTo(ReservationStatus.RELEASED);
    }
}
