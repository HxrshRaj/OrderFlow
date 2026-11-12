package com.orderflow.inventory.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.service.ItemSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.lettuce.core.ClientOptions;
import io.lettuce.core.SocketOptions;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

/**
 * Exercises {@link RedisSkuCache} against a real Redis. Uses {@code IT_REDIS_HOST} /
 * {@code IT_REDIS_PORT} when set (CI service container), otherwise starts {@code redis:7-alpine}
 * through Testcontainers.
 */
class RedisSkuCacheIT {

    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    static final ItemSnapshot ITEM = new ItemSnapshot("IT-SKU-1", "Thing", 7, 2, Instant.parse("2026-01-01T00:00:00Z"));

    static GenericContainer<?> container;
    static LettuceConnectionFactory factory;
    static StringRedisTemplate template;

    @BeforeAll
    @SuppressWarnings("resource")
    static void connect() {
        String host = System.getenv("IT_REDIS_HOST");
        int port;
        if (host != null && !host.isBlank()) {
            port = Integer.parseInt(System.getenv().getOrDefault("IT_REDIS_PORT", "6379"));
        } else {
            container = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);
            container.start();
            host = container.getHost();
            port = container.getMappedPort(6379);
        }
        factory = newFactory(host, port);
        template = new StringRedisTemplate(factory);
        template.afterPropertiesSet();
    }

    static LettuceConnectionFactory newFactory(String host, int port) {
        LettuceClientConfiguration clientConfig = LettuceClientConfiguration.builder()
                .commandTimeout(Duration.ofMillis(300))
                .clientOptions(ClientOptions.builder()
                        .socketOptions(SocketOptions.builder().connectTimeout(Duration.ofMillis(300)).build())
                        .build())
                .build();
        LettuceConnectionFactory f = new LettuceConnectionFactory(new RedisStandaloneConfiguration(host, port), clientConfig);
        f.afterPropertiesSet();
        return f;
    }

    @AfterAll
    static void disconnect() {
        if (factory != null) {
            factory.destroy();
        }
        if (container != null) {
            container.stop();
        }
    }

    private RedisSkuCache cache(StringRedisTemplate redis, Duration ttl, SimpleMeterRegistry meters) {
        return new RedisSkuCache(redis, MAPPER, ttl, meters);
    }

    @Test
    void round_trips_a_snapshot_and_evicts_it() {
        RedisSkuCache cache = cache(template, Duration.ofSeconds(30), new SimpleMeterRegistry());

        cache.put(ITEM);
        assertThat(cache.get("IT-SKU-1")).contains(ITEM);

        cache.evict("IT-SKU-1");
        assertThat(cache.get("IT-SKU-1")).isEmpty();
    }

    @Test
    void entries_expire_after_the_ttl() {
        RedisSkuCache cache = cache(template, Duration.ofMillis(300), new SimpleMeterRegistry());
        ItemSnapshot shortLived = new ItemSnapshot("IT-SKU-TTL", "Thing", 1, 0, Instant.parse("2026-01-01T00:00:00Z"));

        cache.put(shortLived);
        assertThat(cache.get("IT-SKU-TTL")).contains(shortLived);

        await().atMost(Duration.ofSeconds(5)).untilAsserted(
                () -> assertThat(cache.get("IT-SKU-TTL")).isEmpty());
    }

    @Test
    void an_unreachable_redis_degrades_to_a_miss_instead_of_failing() {
        LettuceConnectionFactory dead = newFactory("127.0.0.1", 1);
        try {
            StringRedisTemplate deadTemplate = new StringRedisTemplate(dead);
            deadTemplate.afterPropertiesSet();
            SimpleMeterRegistry meters = new SimpleMeterRegistry();
            RedisSkuCache cache = cache(deadTemplate, Duration.ofSeconds(10), meters);

            cache.put(ITEM);
            assertThat(cache.get("IT-SKU-1")).isEmpty();
            cache.evict("IT-SKU-1");

            // put, get and evict each fail once and are each counted
            assertThat(meters.counter("inventory.cache.requests", "result", "error").count()).isEqualTo(3);
        } finally {
            dead.destroy();
        }
    }
}
