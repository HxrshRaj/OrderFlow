package com.orderflow.inventory.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI inventoryOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("OrderFlow Inventory Service API")
                .version("v1")
                .description("""
                        Tracks stock levels and issues concurrency-safe reservations (holds).

                        Reservation lifecycle: CONFIRMED -> COMMITTED (order shipped) or RELEASED
                        (order cancelled / failed / hold expired). The POST /api/v1/reservations
                        endpoint is idempotent on `reservationId` and atomic across all lines.""")
                .license(new License().name("MIT")));
    }
}
