package com.orderflow.selenium.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * A thin, independent HTTP client to the Order Service's own REST API.
 *
 * <p>Used so a UI test can cross-check the backend's actual state, not just what got
 * rendered in the DOM &mdash; the browser and this client are two separate paths to the
 * same server, so agreement between them is a real end-to-end proof, not a tautology.
 */
public final class ApiProbe {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    private ApiProbe() {
    }

    /** @return the {@code status} field of {@code GET /api/v1/orders/{orderNumber}} */
    public static String fetchOrderStatus(String orderServiceBaseUrl, String orderNumber) {
        String url = orderServiceBaseUrl.replaceAll("/+$", "") + "/api/v1/orders/" + orderNumber;
        HttpRequest request = HttpRequest.newBuilder(URI.create(url))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        try {
            HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException(
                        "GET %s returned %d: %s".formatted(url, response.statusCode(), response.body()));
            }
            JsonNode body = MAPPER.readTree(response.body());
            JsonNode status = body.get("status");
            if (status == null) {
                throw new IllegalStateException("no 'status' field in response body: " + response.body());
            }
            return status.asText();
        } catch (IOException e) {
            throw new IllegalStateException("failed to reach order service at " + url, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while calling " + url, e);
        }
    }
}
