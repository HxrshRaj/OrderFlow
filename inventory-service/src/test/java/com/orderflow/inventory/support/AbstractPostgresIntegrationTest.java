package com.orderflow.inventory.support;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Base class for tests that exercise real JPA + real PostgreSQL locking semantics.
 * H2 is deliberately avoided: it does not reproduce PostgreSQL's row-lock behaviour, which
 * is exactly what the reservation concurrency tests depend on.
 *
 * <p>Database resolution order:
 * <ol>
 *   <li>If {@code -Dit.postgres.url} (or env {@code IT_POSTGRES_URL}) is set, that database is
 *       used as-is. Handy for CI runners that provide their own PostgreSQL service, or hosts
 *       where Testcontainers' Docker auto-discovery misbehaves.</li>
 *   <li>Otherwise a single {@code postgres:16-alpine} container is started via Testcontainers
 *       and shared by every integration test in the JVM.</li>
 * </ol>
 * Flyway builds the schema and seeds the demo catalogue on context start either way.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractPostgresIntegrationTest {

    private static final class SharedContainer {
        private static final PostgreSQLContainer<?> INSTANCE = start();

        @SuppressWarnings("resource")
        private static PostgreSQLContainer<?> start() {
            PostgreSQLContainer<?> container = new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("inventory_db")
                    .withUsername("inventory")
                    .withPassword("inventory");
            container.start();
            return container;
        }
    }

    @DynamicPropertySource
    static void datasourceProperties(DynamicPropertyRegistry registry) {
        String externalUrl = resolve("it.postgres.url", "IT_POSTGRES_URL");
        if (externalUrl != null) {
            String user = orDefault(resolve("it.postgres.user", "IT_POSTGRES_USER"), "inventory");
            String password = orDefault(resolve("it.postgres.password", "IT_POSTGRES_PASSWORD"), "inventory");
            registry.add("spring.datasource.url", () -> externalUrl);
            registry.add("spring.datasource.username", () -> user);
            registry.add("spring.datasource.password", () -> password);
        } else {
            PostgreSQLContainer<?> container = SharedContainer.INSTANCE;
            registry.add("spring.datasource.url", container::getJdbcUrl);
            registry.add("spring.datasource.username", container::getUsername);
            registry.add("spring.datasource.password", container::getPassword);
        }
        // Enough connections for the concurrency tests to actually run in parallel.
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> 24);
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
