package com.orderflow.order.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.util.Timeout;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.time.Duration;

/**
 * The HTTP client used to reach the Inventory Service, backed by Apache HttpClient 5.
 *
 * <p>Connect and response timeouts are set here so a slow or hung Inventory Service can never
 * block an order placement: a timeout surfaces as a {@code ResourceAccessException}, which
 * {@code InventoryClient} turns into a retryable {@code InventoryUnavailableException}.
 * Redirect handling is disabled, so a misconfigured base URL fails loudly rather than
 * silently downgrading a POST.
 */
@Configuration
public class RestClientConfig {

    @Bean
    RestClient inventoryRestClient(@Value("${inventory.base-url}") String baseUrl,
                                   @Value("${inventory.connect-timeout:500ms}") Duration connectTimeout,
                                   @Value("${inventory.read-timeout:2s}") Duration readTimeout) {
        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout(Timeout.ofMilliseconds(connectTimeout.toMillis()))
                .setResponseTimeout(Timeout.ofMilliseconds(readTimeout.toMillis()))
                .build();
        CloseableHttpClient httpClient = HttpClients.custom()
                .setDefaultRequestConfig(requestConfig)
                .disableRedirectHandling()
                .disableAutomaticRetries()
                .build();
        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(new HttpComponentsClientHttpRequestFactory(httpClient))
                .build();
    }
}
