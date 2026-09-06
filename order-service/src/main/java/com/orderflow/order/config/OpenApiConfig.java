package com.orderflow.order.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI orderOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("OrderFlow Order Service API")
                .version("v1")
                .description("""
                        Coordinates the order lifecycle: PLACED -> CONFIRMED -> SHIPPED, with
                        REJECTED and CANCELLED terminal states.

                        Placing an order holds stock in the Inventory Service via an idempotent,
                        resilient reservation call (timeout + retry + circuit breaker). If Inventory
                        cannot be reached the order stays PLACED and a background reconciler retries.""")
                .license(new License().name("MIT")));
    }
}
