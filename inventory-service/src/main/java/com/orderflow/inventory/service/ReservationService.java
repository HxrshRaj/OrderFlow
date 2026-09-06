package com.orderflow.inventory.service;

import com.orderflow.inventory.domain.InventoryItem;
import com.orderflow.inventory.domain.Reservation;
import com.orderflow.inventory.domain.ReservationLine;
import com.orderflow.inventory.domain.ReservationStatus;
import com.orderflow.inventory.repository.InventoryItemRepository;
import com.orderflow.inventory.repository.ReservationRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.UUID;

/**
 * The concurrency-critical service. It holds, commits and releases stock against an order.
 *
 * <h2>How oversell is prevented</h2>
 * <ol>
 *   <li><b>Idempotency anchor first.</b> {@link #reserve} inserts the {@link Reservation} row
 *       (unique {@code reservation_id}) and flushes <em>before</em> touching stock. Two
 *       requests carrying the same id are serialised on that unique index: the second blocks
 *       until the first transaction ends, then either sees the committed winner (replay) or,
 *       if the winner rolled back, proceeds on its own.</li>
 *   <li><b>Atomic conditional decrement.</b> Each line is held with a single
 *       {@code UPDATE ... WHERE available_quantity >= :qty} (see
 *       {@link InventoryItemRepository#reserve}). The row lock taken by that statement
 *       serialises concurrent reservations for the same SKU, and the guard is always
 *       evaluated against a committed value &mdash; so the classic "both read 1, both write 0"
 *       lost update cannot happen.</li>
 *   <li><b>All-or-nothing.</b> Lines are processed in SKU order (a stable lock-acquisition
 *       order, so concurrent multi-line reservations cannot deadlock). If any line cannot be
 *       held, {@link InsufficientStockException} rolls the whole transaction back &mdash;
 *       including the reservation row and every line already held.</li>
 *   <li><b>Hard floor.</b> {@code CHECK (available_quantity >= 0)} in the schema means even a
 *       logic bug cannot persist negative stock.</li>
 * </ol>
 */
@Service
public class ReservationService {

    private static final Logger log = LoggerFactory.getLogger(ReservationService.class);

    private final ReservationRepository reservationRepository;
    private final InventoryItemRepository inventoryItemRepository;
    private final Clock clock;
    private final Duration ttl;

    public ReservationService(ReservationRepository reservationRepository,
                              InventoryItemRepository inventoryItemRepository,
                              Clock clock,
                              @Value("${inventory.reservation.ttl:PT15M}") Duration ttl) {
        this.reservationRepository = reservationRepository;
        this.inventoryItemRepository = inventoryItemRepository;
        this.clock = clock;
        this.ttl = ttl;
    }

    /**
     * Hold stock for an order. Idempotent on {@code reservationId}.
     *
     * @throws InsufficientStockException        at least one line could not be held (nothing is held)
     * @throws ReservationAlreadyExistsException a committed reservation with this id already exists;
     *                                           the caller should re-read and return it
     */
    @Transactional
    public Reservation reserve(UUID reservationId, List<LineCommand> requestedLines) {
        if (requestedLines == null || requestedLines.isEmpty()) {
            throw new IllegalArgumentException("a reservation needs at least one line");
        }

        // Merge duplicate SKUs and impose a deterministic order (deadlock avoidance).
        SortedMap<String, Integer> quantityBySku = new TreeMap<>();
        for (LineCommand line : requestedLines) {
            quantityBySku.merge(line.sku(), line.quantity(), Integer::sum);
        }

        // 1. Anchor the idempotency key before any stock changes.
        Reservation reservation = new Reservation(reservationId, clock.instant().plus(ttl));
        quantityBySku.forEach(reservation::addLine);
        try {
            reservationRepository.saveAndFlush(reservation);
        } catch (DataIntegrityViolationException duplicate) {
            // The only integrity constraint reachable here (SKUs/quantities are validated
            // upstream) is the unique reservation_id: this is a replay.
            throw new ReservationAlreadyExistsException(reservationId);
        }

        // 2. Hold each line atomically, in SKU order.
        List<Shortfall> shortfalls = new ArrayList<>();
        for (SortedMap.Entry<String, Integer> entry : quantityBySku.entrySet()) {
            int rowsHeld = inventoryItemRepository.reserve(entry.getKey(), entry.getValue());
            if (rowsHeld == 0) {
                shortfalls.add(shortfallFor(entry.getKey(), entry.getValue()));
            }
        }

        // 3. All-or-nothing.
        if (!shortfalls.isEmpty()) {
            log.debug("reservation {} rejected: {}", reservationId, shortfalls);
            throw new InsufficientStockException(reservationId, shortfalls);
        }

        log.debug("reservation {} confirmed for {} line(s)", reservationId, quantityBySku.size());
        return reservation;
    }

    /** Convert a hold into a physical deduction (order shipped). Idempotent. */
    @Transactional
    public Reservation commit(UUID reservationId) {
        Reservation reservation = reservationRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (reservation.getStatus() == ReservationStatus.COMMITTED) {
            return reservation;
        }
        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            throw new IllegalReservationStateException(reservationId, ReservationStatus.RELEASED, "commit");
        }

        for (ReservationLine line : reservation.getLines()) {
            int rows = inventoryItemRepository.commitReservation(line.getSku(), line.getQuantity());
            if (rows == 0) {
                throw new IllegalStateException(
                        "reserved quantity underflow committing %s for reservation %s"
                                .formatted(line.getSku(), reservationId));
            }
        }
        reservation.markCommitted();
        return reservationRepository.save(reservation);
    }

    /** Return a hold to available stock (order cancelled / failed). Idempotent. */
    @Transactional
    public Reservation release(UUID reservationId) {
        Reservation reservation = reservationRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));

        if (reservation.getStatus() == ReservationStatus.RELEASED) {
            return reservation;
        }
        if (reservation.getStatus() == ReservationStatus.COMMITTED) {
            throw new IllegalReservationStateException(reservationId, ReservationStatus.COMMITTED, "release");
        }

        releaseLines(reservation);
        reservation.markReleased();
        return reservationRepository.save(reservation);
    }

    @Transactional(readOnly = true)
    public Reservation getByReservationId(UUID reservationId) {
        return reservationRepository.findByReservationId(reservationId)
                .orElseThrow(() -> new ReservationNotFoundException(reservationId));
    }

    /**
     * Release every CONFIRMED hold whose {@code expiresAt} is in the past. Invoked by the
     * scheduled sweeper so a crashed Order Service cannot strand stock forever.
     *
     * @return number of reservations released
     */
    @Transactional
    public int sweepExpired() {
        List<Reservation> expired = reservationRepository
                .findByStatusAndExpiresAtBefore(ReservationStatus.CONFIRMED, clock.instant());
        for (Reservation reservation : expired) {
            releaseLines(reservation);
            reservation.markReleased();
            reservationRepository.save(reservation);
        }
        if (!expired.isEmpty()) {
            log.info("expiry sweep released {} reservation(s)", expired.size());
        }
        return expired.size();
    }

    private void releaseLines(Reservation reservation) {
        for (ReservationLine line : reservation.getLines()) {
            inventoryItemRepository.releaseReservation(line.getSku(), line.getQuantity());
        }
    }

    private Shortfall shortfallFor(String sku, int requested) {
        return inventoryItemRepository.findBySku(sku)
                .map(item -> new Shortfall(sku, requested, item.getAvailableQuantity(), ShortfallReason.INSUFFICIENT_STOCK))
                .orElseGet(() -> new Shortfall(sku, requested, 0, ShortfallReason.UNKNOWN_SKU));
    }

    Duration ttl() {
        return ttl;
    }
}
