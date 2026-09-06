package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.InventoryItem;
import com.orderflow.inventory.domain.Reservation;
import com.orderflow.inventory.domain.ReservationStatus;
import com.orderflow.inventory.repository.InventoryItemRepository;
import com.orderflow.inventory.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Duration TTL = Duration.ofMinutes(15);

    @Mock
    ReservationRepository reservationRepository;
    @Mock
    InventoryItemRepository inventoryItemRepository;

    ReservationService service;

    @BeforeEach
    void setUp() {
        service = new ReservationService(reservationRepository, inventoryItemRepository,
                Clock.fixed(NOW, ZoneOffset.UTC), TTL);
    }

    @Test
    void reserve_holds_every_line_and_sets_expiry() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryItemRepository.reserve("SKU-A", 2)).thenReturn(1);
        when(inventoryItemRepository.reserve("SKU-B", 1)).thenReturn(1);

        Reservation result = service.reserve(id, List.of(
                new LineCommand("SKU-A", 2), new LineCommand("SKU-B", 1)));

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(result.getExpiresAt()).isEqualTo(NOW.plus(TTL));
        assertThat(result.getLines()).extracting("sku", "quantity")
                .containsExactlyInAnyOrder(tuple("SKU-A", 2), tuple("SKU-B", 1));
    }

    @Test
    void reserve_merges_duplicate_skus_into_one_decrement() {
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryItemRepository.reserve("SKU-A", 5)).thenReturn(1);

        service.reserve(UUID.randomUUID(), List.of(
                new LineCommand("SKU-A", 2), new LineCommand("SKU-A", 3)));

        verify(inventoryItemRepository).reserve("SKU-A", 5);
        verify(inventoryItemRepository, never()).reserve("SKU-A", 2);
        verify(inventoryItemRepository, never()).reserve("SKU-A", 3);
    }

    @Test
    void reserve_rejects_with_shortfall_when_stock_is_insufficient() {
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryItemRepository.reserve("SKU-A", 5)).thenReturn(0);
        when(inventoryItemRepository.findBySku("SKU-A"))
                .thenReturn(Optional.of(new InventoryItem("SKU-A", "Thing", 2)));

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> service.reserve(UUID.randomUUID(), List.of(new LineCommand("SKU-A", 5))))
                .satisfies(ex -> assertThat(ex.getShortfalls()).singleElement().satisfies(s -> {
                    assertThat(s.sku()).isEqualTo("SKU-A");
                    assertThat(s.requested()).isEqualTo(5);
                    assertThat(s.available()).isEqualTo(2);
                    assertThat(s.reason()).isEqualTo(ShortfallReason.INSUFFICIENT_STOCK);
                }));
    }

    @Test
    void reserve_reports_unknown_sku_distinctly() {
        when(reservationRepository.saveAndFlush(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));
        when(inventoryItemRepository.reserve("SKU-GHOST", 1)).thenReturn(0);
        when(inventoryItemRepository.findBySku("SKU-GHOST")).thenReturn(Optional.empty());

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> service.reserve(UUID.randomUUID(), List.of(new LineCommand("SKU-GHOST", 1))))
                .satisfies(ex -> assertThat(ex.getShortfalls()).singleElement()
                        .satisfies(s -> assertThat(s.reason()).isEqualTo(ShortfallReason.UNKNOWN_SKU)));
    }

    @Test
    void reserve_treats_a_unique_key_violation_as_an_idempotent_replay() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.saveAndFlush(any(Reservation.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatExceptionOfType(ReservationAlreadyExistsException.class)
                .isThrownBy(() -> service.reserve(id, List.of(new LineCommand("SKU-A", 1))))
                .satisfies(ex -> assertThat(ex.getReservationId()).isEqualTo(id));

        verify(inventoryItemRepository, never()).reserve(any(), anyInt());
    }

    @Test
    void commit_converts_holds_to_deductions() {
        UUID id = UUID.randomUUID();
        Reservation reservation = confirmedReservation(id, "SKU-A", 2, "SKU-B", 1);
        when(reservationRepository.findByReservationId(id)).thenReturn(Optional.of(reservation));
        when(inventoryItemRepository.commitReservation("SKU-A", 2)).thenReturn(1);
        when(inventoryItemRepository.commitReservation("SKU-B", 1)).thenReturn(1);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        Reservation result = service.commit(id);

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        verify(inventoryItemRepository).commitReservation("SKU-A", 2);
        verify(inventoryItemRepository).commitReservation("SKU-B", 1);
    }

    @Test
    void commit_is_idempotent() {
        UUID id = UUID.randomUUID();
        Reservation reservation = confirmedReservation(id, "SKU-A", 1);
        reservation.markCommitted();
        when(reservationRepository.findByReservationId(id)).thenReturn(Optional.of(reservation));

        Reservation result = service.commit(id);

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.COMMITTED);
        verify(inventoryItemRepository, never()).commitReservation(any(), anyInt());
    }

    @Test
    void commit_rejects_a_released_reservation() {
        UUID id = UUID.randomUUID();
        Reservation reservation = confirmedReservation(id, "SKU-A", 1);
        reservation.markReleased();
        when(reservationRepository.findByReservationId(id)).thenReturn(Optional.of(reservation));

        assertThatExceptionOfType(IllegalReservationStateException.class).isThrownBy(() -> service.commit(id));
    }

    @Test
    void commit_throws_when_reservation_is_unknown() {
        UUID id = UUID.randomUUID();
        when(reservationRepository.findByReservationId(id)).thenReturn(Optional.empty());

        assertThatExceptionOfType(ReservationNotFoundException.class).isThrownBy(() -> service.commit(id));
        verifyNoInteractions(inventoryItemRepository);
    }

    @Test
    void release_returns_holds_to_available_stock() {
        UUID id = UUID.randomUUID();
        Reservation reservation = confirmedReservation(id, "SKU-A", 3);
        when(reservationRepository.findByReservationId(id)).thenReturn(Optional.of(reservation));
        when(inventoryItemRepository.releaseReservation("SKU-A", 3)).thenReturn(1);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        Reservation result = service.release(id);

        assertThat(result.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        verify(inventoryItemRepository).releaseReservation("SKU-A", 3);
    }

    @Test
    void release_rejects_a_committed_reservation() {
        UUID id = UUID.randomUUID();
        Reservation reservation = confirmedReservation(id, "SKU-A", 1);
        reservation.markCommitted();
        when(reservationRepository.findByReservationId(id)).thenReturn(Optional.of(reservation));

        assertThatExceptionOfType(IllegalReservationStateException.class).isThrownBy(() -> service.release(id));
    }

    @Test
    void sweepExpired_releases_each_expired_hold() {
        Reservation a = confirmedReservation(UUID.randomUUID(), "SKU-A", 1);
        Reservation b = confirmedReservation(UUID.randomUUID(), "SKU-B", 2);
        when(reservationRepository.findByStatusAndExpiresAtBefore(eq(ReservationStatus.CONFIRMED), any()))
                .thenReturn(List.of(a, b));
        when(inventoryItemRepository.releaseReservation(any(), anyInt())).thenReturn(1);
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(inv -> inv.getArgument(0));

        int released = service.sweepExpired();

        assertThat(released).isEqualTo(2);
        assertThat(a.getStatus()).isEqualTo(ReservationStatus.RELEASED);
        assertThat(b.getStatus()).isEqualTo(ReservationStatus.RELEASED);
    }

    private Reservation confirmedReservation(UUID id, Object... skuThenQty) {
        Reservation reservation = new Reservation(id, NOW.plus(TTL));
        for (int i = 0; i < skuThenQty.length; i += 2) {
            reservation.addLine((String) skuThenQty[i], (Integer) skuThenQty[i + 1]);
        }
        return reservation;
    }
}
