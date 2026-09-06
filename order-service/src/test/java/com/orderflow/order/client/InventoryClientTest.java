package com.orderflow.order.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.orderflow.order.client.dto.ReservationLinePayload;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

/** Verifies how {@link InventoryClient} maps Inventory Service HTTP responses to exceptions. */
class InventoryClientTest {

    private WireMockServer wireMock;
    private InventoryClient client;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();

        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(300);
        requestFactory.setReadTimeout(600);
        RestClient restClient = RestClient.builder()
                .baseUrl("http://localhost:" + wireMock.port())
                .requestFactory(requestFactory)
                .build();
        client = new InventoryClient(restClient, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    private static List<ReservationLinePayload> lines() {
        return List.of(new ReservationLinePayload("SKU-A", 2));
    }

    @Test
    void reserve_succeeds_on_201() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/reservations"))
                .willReturn(aResponse().withStatus(201)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"CONFIRMED\"}")));

        assertThatCode(() -> client.reserve(UUID.randomUUID(), lines())).doesNotThrowAnyException();
    }

    @Test
    void reserve_succeeds_on_200_replay() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/reservations"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"status\":\"CONFIRMED\",\"replayed\":true}")));

        assertThatCode(() -> client.reserve(UUID.randomUUID(), lines())).doesNotThrowAnyException();
    }

    @Test
    void reserve_maps_409_to_InsufficientStockException_with_shortfalls() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/reservations"))
                .willReturn(aResponse().withStatus(409)
                        .withHeader("Content-Type", "application/problem+json")
                        .withBody("""
                                {"title":"Insufficient stock","status":409,
                                 "shortfalls":[{"sku":"SKU-A","requested":2,"available":0,"reason":"INSUFFICIENT_STOCK"}]}
                                """)));

        assertThatExceptionOfType(InsufficientStockException.class)
                .isThrownBy(() -> client.reserve(UUID.randomUUID(), lines()))
                .satisfies(ex -> {
                    assertThat(ex.getShortfalls()).hasSize(1);
                    assertThat(ex.getShortfalls().get(0).sku()).isEqualTo("SKU-A");
                    assertThat(ex.getShortfalls().get(0).available()).isZero();
                });
    }

    @Test
    void reserve_maps_500_to_InventoryUnavailableException() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/reservations"))
                .willReturn(aResponse().withStatus(500)));

        assertThatExceptionOfType(InventoryUnavailableException.class)
                .isThrownBy(() -> client.reserve(UUID.randomUUID(), lines()));
    }

    @Test
    void reserve_maps_a_read_timeout_to_InventoryUnavailableException() {
        wireMock.stubFor(post(urlEqualTo("/api/v1/reservations"))
                .willReturn(aResponse().withStatus(201).withFixedDelay(1500)));

        assertThatExceptionOfType(InventoryUnavailableException.class)
                .isThrownBy(() -> client.reserve(UUID.randomUUID(), lines()));
    }

    @Test
    void release_treats_404_as_a_no_op() {
        wireMock.stubFor(post(urlPathMatching("/api/v1/reservations/.*/release"))
                .willReturn(aResponse().withStatus(404)));

        assertThatCode(() -> client.release(UUID.randomUUID())).doesNotThrowAnyException();
    }

    @Test
    void commit_succeeds_on_200() {
        wireMock.stubFor(post(urlPathMatching("/api/v1/reservations/.*/commit"))
                .willReturn(aResponse().withStatus(200)));

        assertThatCode(() -> client.commit(UUID.randomUUID())).doesNotThrowAnyException();
    }
}
