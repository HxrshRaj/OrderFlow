package com.orderflow.inventory.web;

import com.orderflow.inventory.domain.Reservation;
import com.orderflow.inventory.service.ReservationAlreadyExistsException;
import com.orderflow.inventory.service.ReservationService;
import com.orderflow.inventory.web.dto.ReservationResponse;
import com.orderflow.inventory.web.dto.ReserveRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/reservations")
@Tag(name = "Reservations", description = "Concurrency-safe stock holds for orders")
public class ReservationController {

    private final ReservationService reservationService;

    public ReservationController(ReservationService reservationService) {
        this.reservationService = reservationService;
    }

    @PostMapping
    @Operation(summary = "Hold stock for an order",
            description = "Idempotent on reservationId. Atomic across all lines: if any line "
                    + "cannot be held, nothing is held and 409 is returned with per-SKU shortfalls.")
    public ResponseEntity<ReservationResponse> reserve(@Valid @RequestBody ReserveRequest request) {
        Reservation reservation = reservationService.reserve(request.reservationId(), request.toCommands());
        return ResponseEntity.status(HttpStatus.CREATED).body(ReservationResponse.from(reservation, false));
    }

    /**
     * A reservation with this id already exists (a retried call, or a genuine duplicate).
     * Re-read it and return 200 &mdash; the client sees the same outcome as the original call.
     */
    @ExceptionHandler(ReservationAlreadyExistsException.class)
    public ResponseEntity<ReservationResponse> handleReplay(ReservationAlreadyExistsException ex) {
        Reservation existing = reservationService.getByReservationId(ex.getReservationId());
        return ResponseEntity.ok(ReservationResponse.from(existing, true));
    }

    @PostMapping("/{reservationId}/commit")
    @Operation(summary = "Convert a hold into a physical stock deduction (order shipped)")
    public ReservationResponse commit(@PathVariable UUID reservationId) {
        return ReservationResponse.from(reservationService.commit(reservationId), false);
    }

    @PostMapping("/{reservationId}/release")
    @Operation(summary = "Return a hold to available stock (order cancelled / failed)")
    public ReservationResponse release(@PathVariable UUID reservationId) {
        return ReservationResponse.from(reservationService.release(reservationId), false);
    }

    @GetMapping("/{reservationId}")
    @Operation(summary = "Get a reservation's current state")
    public ReservationResponse get(@PathVariable UUID reservationId) {
        return ReservationResponse.from(reservationService.getByReservationId(reservationId), false);
    }
}
