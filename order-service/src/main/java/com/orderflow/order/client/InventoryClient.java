package com.orderflow.order.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.order.client.dto.InventoryProblemDetail;
import com.orderflow.order.client.dto.ReservationLinePayload;
import com.orderflow.order.client.dto.ReservationRequest;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.retry.annotation.Retry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Talks to the Inventory Service over REST, wrapped in Resilience4j retry + circuit breaker
 * (configured under {@code resilience4j.*} in application.yml).
 *
 * <p>Two failure categories, treated very differently:
 * <ul>
 *   <li>{@link InsufficientStockException} (HTTP 409) &mdash; a final business answer. Never
 *       retried, never counts against the circuit breaker.</li>
 *   <li>{@link InventoryUnavailableException} (timeout, connection refused, 5xx) &mdash;
 *       retried with exponential backoff; sustained failures open the circuit.</li>
 * </ul>
 */
@Component
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);

    private final RestClient restClient;
    private final ObjectMapper objectMapper;

    public InventoryClient(RestClient inventoryRestClient, ObjectMapper objectMapper) {
        this.restClient = inventoryRestClient;
        this.objectMapper = objectMapper;
    }

    @Retry(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public void reserve(UUID reservationId, List<ReservationLinePayload> lines) {
        try {
            restClient.post()
                    .uri("/api/v1/reservations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ReservationRequest(reservationId, lines))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 409) {
                throw toInsufficientStock(reservationId, e.getResponseBodyAsString());
            }
            throw new InventoryUnavailableException("inventory reserve returned " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new InventoryUnavailableException("inventory unreachable during reserve", e);
        }
    }

    @Retry(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public void commit(UUID reservationId) {
        try {
            restClient.post()
                    .uri("/api/v1/reservations/{id}/commit", reservationId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            throw new InventoryUnavailableException("inventory commit returned " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new InventoryUnavailableException("inventory unreachable during commit", e);
        }
    }

    @Retry(name = "inventory")
    @CircuitBreaker(name = "inventory")
    public void release(UUID reservationId) {
        try {
            restClient.post()
                    .uri("/api/v1/reservations/{id}/release", reservationId)
                    .retrieve()
                    .toBodilessEntity();
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == 404) {
                // No hold was ever created (the order failed before reserve succeeded).
                // Releasing nothing is success.
                log.debug("release of reservation {} returned 404; treating as no-op", reservationId);
                return;
            }
            throw new InventoryUnavailableException("inventory release returned " + e.getStatusCode(), e);
        } catch (ResourceAccessException e) {
            throw new InventoryUnavailableException("inventory unreachable during release", e);
        }
    }

    private InsufficientStockException toInsufficientStock(UUID reservationId, String body) {
        List<Shortfall> shortfalls = new ArrayList<>();
        try {
            InventoryProblemDetail problem = objectMapper.readValue(body, InventoryProblemDetail.class);
            if (problem.shortfalls() != null) {
                problem.shortfalls().forEach(s ->
                        shortfalls.add(new Shortfall(s.sku(), s.requested(), s.available(), s.reason())));
            }
        } catch (Exception parseFailure) {
            log.warn("could not parse inventory 409 body for reservation {}: {}",
                    reservationId, parseFailure.toString());
        }
        return new InsufficientStockException(reservationId, shortfalls);
    }
}
