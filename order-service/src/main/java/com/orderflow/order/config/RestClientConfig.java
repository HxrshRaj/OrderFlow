package com.orderflow.order.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The HTTP client used to reach the Inventory Service.
 *
 * <p>Connect and read timeouts are set here so a slow or hung Inventory Service can never
 * block an order placement indefinitely &mdash; a read timeout surfaces as a
 * {@code ResourceAccessException}, which {@code InventoryClient} turns into a retryable
 * {@code InventoryUnavailableException}.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient inventoryRestClient(@Value("${inventory.base-url}") String baseUrl,
                                   @Value("${inventory.connect-timeout:500ms}") Duration connectTimeout,
                                   @Value("${inventory.read-timeout:2s}") Duration readTimeout) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout((int) connectTimeout.toMillis());
        requestFactory.setReadTimeout((int) readTimeout.toMillis());
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();
    }
}
