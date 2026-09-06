package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.InventoryItem;
import com.orderflow.inventory.domain.ReservationStatus;
import com.orderflow.inventory.repository.InventoryItemRepository;
import com.orderflow.inventory.repository.ReservationRepository;
import com.orderflow.inventory.support.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The centrepiece test. Proves the Inventory Service never oversells under concurrent
 * reservation pressure, and that the reserve endpoint is idempotent.
 *
 * <p>Every case fires many real transactions at once through {@link ReservationService},
 * released simultaneously by a {@link CountDownLatch}, against a real PostgreSQL instance.
 */
class ReservationConcurrencyIT extends AbstractPostgresIntegrationTest {

    @Autowired
    ReservationService reservationService;
    @Autowired
    InventoryItemRepository inventoryItemRepository;
    @Autowired
    ReservationRepository reservationRepository;

    private String sku;

    @BeforeEach
    void createIsolatedSku() {
        sku = "IT-" + UUID.randomUUID();
    }

    @AfterEach
    void cleanUp() {
        reservationRepository.deleteAll();
        inventoryItemRepository.findBySku(sku).ifPresent(inventoryItemRepository::delete);
    }

    @Test
    void concurrent_reservations_for_the_last_unit_never_oversell() throws Exception {
        seed(1);
        int racers = 40;

        Result result = runConcurrently(racers, () -> reserveOne(sku));

        assertThat(result.successes()).as("exactly one racer may take the last unit").isEqualTo(1);
        assertThat(result.insufficientStock()).isEqualTo(racers - 1);
        assertThat(result.unexpected()).isEmpty();

        InventoryItem item = inventoryItemRepository.findBySku(sku).orElseThrow();
        assertThat(item.getAvailableQuantity()).isZero();
        assertThat(item.getReservedQuantity()).isEqualTo(1);
        assertThat(reservationRepository.findAll()).hasSize(1)
                .allSatisfy(r -> assertThat(r.getStatus()).isEqualTo(ReservationStatus.CONFIRMED));
    }

    @Test
    void concurrent_reservations_hand_out_exactly_the_available_quantity() throws Exception {
        seed(10);
        int racers = 50;

        Result result = runConcurrently(racers, () -> reserveOne(sku));

        assertThat(result.successes()).isEqualTo(10);
        assertThat(result.insufficientStock()).isEqualTo(racers - 10);
        assertThat(result.unexpected()).isEmpty();

        InventoryItem item = inventoryItemRepository.findBySku(sku).orElseThrow();
        assertThat(item.getAvailableQuantity()).isZero();
        assertThat(item.getReservedQuantity()).isEqualTo(10);
    }

    @Test
    void same_reservation_id_submitted_concurrently_decrements_stock_once() throws Exception {
        seed(10);
        UUID sharedId = UUID.randomUUID();
        int racers = 20;

        Result result = runConcurrently(racers, () -> {
            try {
                reservationService.reserve(sharedId, List.of(new LineCommand(sku, 1)));
                return Outcome.SUCCESS;
            } catch (ReservationAlreadyExistsException replay) {
                return Outcome.REPLAY;
            }
        });

        assertThat(result.get(Outcome.SUCCESS)).as("only one transaction creates the reservation").isEqualTo(1);
        assertThat(result.get(Outcome.REPLAY)).isEqualTo(racers - 1);
        assertThat(result.unexpected()).isEmpty();

        InventoryItem item = inventoryItemRepository.findBySku(sku).orElseThrow();
        assertThat(item.getAvailableQuantity()).as("stock moved exactly once despite %d calls", racers).isEqualTo(9);
        assertThat(item.getReservedQuantity()).isEqualTo(1);
        assertThat(reservationRepository.findByReservationId(sharedId)).isPresent();
    }

    @Test
    void multi_line_reservation_is_all_or_nothing_when_one_line_is_short() {
        String plentiful = "IT-PLENTY-" + UUID.randomUUID();
        inventoryItemRepository.save(new InventoryItem(plentiful, "Plentiful", 100));
        seed(0); // the scarce line

        UUID reservationId = UUID.randomUUID();
        try {
            reservationService.reserve(reservationId, List.of(
                    new LineCommand(plentiful, 5),
                    new LineCommand(sku, 1)));
        } catch (InsufficientStockException expected) {
            assertThat(expected.getShortfalls()).singleElement().satisfies(s -> {
                assertThat(s.sku()).isEqualTo(sku);
                assertThat(s.reason()).isEqualTo(ShortfallReason.INSUFFICIENT_STOCK);
            });
        }

        assertThat(inventoryItemRepository.findBySku(plentiful).orElseThrow().getAvailableQuantity())
                .as("the line that could be held must be rolled back too").isEqualTo(100);
        assertThat(reservationRepository.findByReservationId(reservationId))
                .as("no reservation row survives a rejection").isEmpty();

        inventoryItemRepository.delete(inventoryItemRepository.findBySku(plentiful).orElseThrow());
    }

    // --- helpers -------------------------------------------------------------

    private void seed(int quantity) {
        inventoryItemRepository.save(new InventoryItem(sku, "Concurrency Fixture", quantity));
    }

    private Outcome reserveOne(String targetSku) {
        try {
            reservationService.reserve(UUID.randomUUID(), List.of(new LineCommand(targetSku, 1)));
            return Outcome.SUCCESS;
        } catch (InsufficientStockException e) {
            return Outcome.INSUFFICIENT_STOCK;
        }
    }

    private Result runConcurrently(int threads, Callable<Outcome> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<OutcomeOrError>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    try {
                        return OutcomeOrError.of(task.call());
                    } catch (Throwable t) {
                        return OutcomeOrError.error(t);
                    }
                }));
            }
            startGate.countDown();

            AtomicInteger success = new AtomicInteger();
            AtomicInteger insufficient = new AtomicInteger();
            AtomicInteger replay = new AtomicInteger();
            List<Throwable> unexpected = new java.util.ArrayList<>();
            for (Future<OutcomeOrError> f : futures) {
                OutcomeOrError r = f.get(30, TimeUnit.SECONDS);
                if (r.error() != null) {
                    unexpected.add(r.error());
                } else {
                    switch (r.outcome()) {
                        case SUCCESS -> success.incrementAndGet();
                        case INSUFFICIENT_STOCK -> insufficient.incrementAndGet();
                        case REPLAY -> replay.incrementAndGet();
                    }
                }
            }
            return new Result(success.get(), insufficient.get(), replay.get(), unexpected);
        } finally {
            pool.shutdownNow();
        }
    }

    private enum Outcome {SUCCESS, INSUFFICIENT_STOCK, REPLAY}

    private record OutcomeOrError(Outcome outcome, Throwable error) {
        static OutcomeOrError of(Outcome o) {
            return new OutcomeOrError(o, null);
        }

        static OutcomeOrError error(Throwable t) {
            return new OutcomeOrError(null, t);
        }
    }

    private record Result(int successes, int insufficientStock, int replays, List<Throwable> unexpected) {
        int get(Outcome o) {
            return switch (o) {
                case SUCCESS -> successes;
                case INSUFFICIENT_STOCK -> insufficientStock;
                case REPLAY -> replays;
            };
        }
    }
}
