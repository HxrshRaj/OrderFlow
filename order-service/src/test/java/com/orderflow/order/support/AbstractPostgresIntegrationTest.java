package com.orderflow.order.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real PostgreSQL for integration tests. Resolution order:
 * <ol>
 *   <li>{@code -Dit.postgres.url} / env {@code IT_POSTGRES_URL} if set;</li>
 *   <li>otherwise a shared {@code postgres:16-alpine} Testcontainer.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
public abstract class AbstractPostgresIntegrationTest {

    private static final class SharedContainer {
        private static final PostgreSQLContainer<?> INSTANCE = start();

        @SuppressWarnings("resource")
        private static PostgreSQLContainer<?> start() {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("order_db")
                    .withUsername("orders")
                    .withPassword("orders");
            container.start();
            return container;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        String externalUrl = resolve("it.postgres.url", "IT_POSTGRES_URL");
        if (externalUrl != null) {
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username",
                    () -> orDefault(resolve("it.postgres.user", "IT_POSTGRES_USER"), "orders"));
            registry.add("spring.datasource.password",
                    () -> orDefault(resolve("it.postgres.password", "IT_POSTGRES_PASSWORD"), "orders"));
        } else {
            PostgreSQLContainer<?> container = SharedContainer.INSTANCE;
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
    }

    private static String resolve(String systemProperty, String envVar) {
        String value = System.getProperty(systemProperty);
        if (value == null || value.isBlank()) {
            value = System.getenv(envVar);
        }
        return (value == null || value.isBlank()) ? null : value;
    }

    private static String orDefault(String value, String fallback) {
        return value != null ? value : fallback;
    }
}
