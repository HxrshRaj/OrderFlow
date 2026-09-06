package com.orderflow.inventory.web;

import com.orderflow.inventory.domain.Reservation;
import com.orderflow.inventory.service.InsufficientStockException;
import com.orderflow.inventory.service.ReservationAlreadyExistsException;
import com.orderflow.inventory.service.ReservationService;
import com.orderflow.inventory.service.Shortfall;
import com.orderflow.inventory.service.ShortfallReason;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ReservationController.class)
class ReservationControllerTest {

    @Autowired
    MockMvc mockMvc;
    @MockitoBean
    ReservationService reservationService;

    @Test
    void reserve_returns_201_with_confirmed_body() throws Exception {
        UUID id = UUID.randomUUID();
        Reservation reservation = new Reservation(id, Instant.parse("2026-01-01T00:15:00Z"));
        reservation.addLine("SKU-A", 2);
        when(reservationService.reserve(eq(id), any())).thenReturn(reservation);

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"%s","lines":[{"sku":"SKU-A","quantity":2}]}
                                """.formatted(id)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.lines[0].sku").value("SKU-A"));
    }

    @Test
    void reserve_returns_409_with_shortfalls_when_stock_is_insufficient() throws Exception {
        UUID id = UUID.randomUUID();
        when(reservationService.reserve(eq(id), any())).thenThrow(new InsufficientStockException(id,
                List.of(new Shortfall("SKU-A", 5, 2, ShortfallReason.INSUFFICIENT_STOCK))));

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"%s","lines":[{"sku":"SKU-A","quantity":5}]}
                                """.formatted(id)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.title").value("Insufficient stock"))
                .andExpect(jsonPath("$.shortfalls[0].sku").value("SKU-A"))
                .andExpect(jsonPath("$.shortfalls[0].available").value(2))
                .andExpect(jsonPath("$.shortfalls[0].reason").value("INSUFFICIENT_STOCK"));
    }

    @Test
    void reserve_replay_returns_200_and_flags_it() throws Exception {
        UUID id = UUID.randomUUID();
        Reservation existing = new Reservation(id, Instant.parse("2026-01-01T00:15:00Z"));
        existing.addLine("SKU-A", 2);
        when(reservationService.reserve(eq(id), any())).thenThrow(new ReservationAlreadyExistsException(id));
        when(reservationService.getByReservationId(id)).thenReturn(existing);

        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"%s","lines":[{"sku":"SKU-A","quantity":2}]}
                                """.formatted(id)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true));
    }

    @Test
    void reserve_rejects_an_empty_line_list() throws Exception {
        mockMvc.perform(post("/api/v1/reservations")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"reservationId":"%s","lines":[]}
                                """.formatted(UUID.randomUUID())))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation error"));
    }

    @Test
    void get_unknown_reservation_returns_404() throws Exception {
        UUID id = UUID.randomUUID();
        when(reservationService.getByReservationId(id))
                .thenThrow(new com.orderflow.inventory.service.ReservationNotFoundException(id));

        mockMvc.perform(get("/api/v1/reservations/{id}", id))
                .andExpect(status().isNotFound());
    }
}
