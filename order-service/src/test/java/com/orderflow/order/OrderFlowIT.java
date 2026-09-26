package com.orderflow.order;

import com.orderflow.order.repository.OrderRepository;
import com.orderflow.order.service.OrderService;
import com.orderflow.order.support.AbstractPostgresIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end order flow with a real database and the Inventory Service stubbed by WireMock.
 * Covers the failure modes that matter: definitive rejection (409), inventory outage leaving
 * the order PLACED, and the reconciler recovering it once inventory is back.
 */
class OrderFlowIT extends AbstractPostgresIntegrationTest {

    /**
     * Pin the service clock far in the future so {@code reconcileStaleOrders(Duration.ZERO)}
     * unambiguously treats a just-placed order as stale, regardless of any clock skew between
     * the JVM and the Postgres container.
     */
    @TestConfiguration
    static class FutureClockConfig {
        @Bean
        @Primary
        Clock futureClock() {
            return Clock.fixed(Instant.parse("2030-01-01T00:00:00Z"), ZoneOffset.UTC);
        }
    }

    private static final WireMockServer INVENTORY = new WireMockServer(options().dynamicPort());

    @DynamicPropertySource
    static void inventoryProperties(DynamicPropertyRegistry registry) {
        if (!INVENTORY.isRunning()) {
            INVENTORY.start();
        }
        registry.add("inventory.base-url", () -> "http://localhost:" + INVENTORY.port());
    }

    @AfterAll
    static void stopWireMock() {
        INVENTORY.stop();
    }

    @Autowired
    TestRestTemplate http;
    @Autowired
    OrderService orderService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    CircuitBreakerRegistry circuitBreakerRegistry;
    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void reset() {
        INVENTORY.resetAll();
        orderRepository.deleteAll();
        resetCircuitBreaker();
    }

    private void resetCircuitBreaker() {
        circuitBreakerRegistry.circuitBreaker("inventory").reset();
    }

    private static Map<String, Object> orderBody(String customerId) {
        return Map.of(
                "customerId", customerId,
                "lines", new Object[]{Map.of("sku", "SKU-A", "quantity", 2, "unitPrice", 9.99)});
    }

    private void stubReserve(int status, String body) {
        INVENTORY.stubFor(post(urlEqualTo("/api/v1/reservations")).willReturn(aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)));
    }

    @Test
    void order_is_confirmed_when_inventory_has_stock() {
        stubReserve(201, "{\"status\":\"CONFIRMED\"}");

        ResponseEntity<Map> response = http.postForEntity("/api/v1/orders", orderBody("cust-confirm"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "CONFIRMED");
        assertThat((String) response.getBody().get("orderNumber")).startsWith("ORD-");
    }

    @Test
    void order_is_rejected_when_inventory_is_out_of_stock() {
        stubReserve(409, """
                {"title":"Insufficient stock","status":409,
                 "shortfalls":[{"sku":"SKU-A","requested":2,"available":1,"reason":"INSUFFICIENT_STOCK"}]}
                """);

        ResponseEntity<Map> response = http.postForEntity("/api/v1/orders", orderBody("cust-reject"), Map.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).containsEntry("status", "REJECTED");
        assertThat((String) response.getBody().get("rejectionReason")).contains("SKU-A");
    }

    @Test
    void order_stays_placed_when_inventory_is_down_then_reconciles_once_it_recovers() {
        stubReserve(500, "");

        ResponseEntity<Map> placed = http.postForEntity("/api/v1/orders", orderBody("cust-defer"), Map.class);
        assertThat(placed.getBody()).containsEntry("status", "PLACED");
        String orderNumber = (String) placed.getBody().get("orderNumber");

        // Inventory recovers and the circuit breaker has had time to close.
        stubReserve(201, "{\"status\":\"CONFIRMED\"}");
        resetCircuitBreaker();
        int resolved = orderService.reconcileStaleOrders(Duration.ZERO);

        assertThat(resolved).isEqualTo(1);
        ResponseEntity<Map> after = http.getForEntity("/api/v1/orders/{n}", Map.class, orderNumber);
        assertThat(after.getBody()).containsEntry("status", "CONFIRMED");
    }

    @Test
    void shipping_a_confirmed_order_commits_the_reservation_and_publishes_the_domain_event() {
        stubReserve(201, "{\"status\":\"CONFIRMED\"}");
        INVENTORY.stubFor(post(urlPathMatching("/api/v1/reservations/.*/commit"))
                .willReturn(aResponse().withStatus(200)));
        String orderNumber = (String) http.postForEntity("/api/v1/orders", orderBody("cust-ship"), Map.class)
                .getBody().get("orderNumber");

        double shippedBefore = counterValue("orderflow.orders.shipped");

        ResponseEntity<Map> shipped = http.postForEntity("/api/v1/orders/{n}/ship", null, Map.class, orderNumber);

        assertThat(shipped.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(shipped.getBody()).containsEntry("status", "SHIPPED");
        INVENTORY.verify(postRequestedFor(urlPathMatching("/api/v1/reservations/.*/commit")));

        // Proves the OrderShipped domain event genuinely round-tripped through Spring's
        // transactional-event machinery end to end: Order.ship() recorded it,
        // OrderService.transition() published it after the commit, and
        // OrderShippedMetricsListener (a class the aggregate and service know nothing about)
        // reacted to it - by the time the HTTP call above returned, since
        // @TransactionalEventListener(AFTER_COMMIT) runs synchronously in the committing thread.
        assertThat(counterValue("orderflow.orders.shipped"))
                .as("the AFTER_COMMIT listener should have incremented the shipped counter exactly once")
                .isEqualTo(shippedBefore + 1.0);
    }

    private double counterValue(String meterName) {
        var counter = meterRegistry.find(meterName).counter();
        return counter == null ? 0.0 : counter.count();
    }

    @Test
    void cancelling_a_confirmed_order_releases_the_hold() {
        stubReserve(201, "{\"status\":\"CONFIRMED\"}");
        INVENTORY.stubFor(post(urlPathMatching("/api/v1/reservations/.*/release"))
                .willReturn(aResponse().withStatus(200)));
        String orderNumber = (String) http.postForEntity("/api/v1/orders", orderBody("cust-cancel"), Map.class)
                .getBody().get("orderNumber");

        ResponseEntity<Map> cancelled = http.postForEntity("/api/v1/orders/{n}/cancel", null, Map.class, orderNumber);

        assertThat(cancelled.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(cancelled.getBody()).containsEntry("status", "CANCELLED");
        INVENTORY.verify(postRequestedFor(urlPathMatching("/api/v1/reservations/.*/release")));
    }
}
