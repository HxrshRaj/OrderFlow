package com.orderflow.inventory.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.service.ItemSnapshot;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisSkuCacheTest {

    static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    static final ItemSnapshot ITEM = new ItemSnapshot("SKU-1", "Thing", 7, 2, Instant.parse("2026-01-01T00:00:00Z"));

    @Mock
    StringRedisTemplate redis;
    @Mock
    ValueOperations<String, String> ops;

    SimpleMeterRegistry meters;
    RedisSkuCache cache;

    @BeforeEach
    void setUp() {
        meters = new SimpleMeterRegistry();
        cache = new RedisSkuCache(redis, MAPPER, Duration.ofSeconds(10), meters);
    }

    private double count(String result) {
        return meters.counter("inventory.cache.requests", "result", result).count();
    }

    @Test
    void hit_returns_the_snapshot_and_counts_a_hit() throws Exception {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get("inventory:item:SKU-1")).thenReturn(MAPPER.writeValueAsString(ITEM));

        assertThat(cache.get("SKU-1")).contains(ITEM);
        assertThat(count("hit")).isEqualTo(1);
    }

    @Test
    void missing_key_counts_a_miss() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn(null);

        assertThat(cache.get("SKU-1")).isEmpty();
        assertThat(count("miss")).isEqualTo(1);
    }

    @Test
    void redis_down_on_read_degrades_to_empty_instead_of_throwing() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));

        assertThat(cache.get("SKU-1")).isEmpty();
        assertThat(count("error")).isEqualTo(1);
    }

    @Test
    void corrupt_json_is_treated_as_an_error_not_a_crash() {
        when(redis.opsForValue()).thenReturn(ops);
        when(ops.get(anyString())).thenReturn("{not json");

        assertThat(cache.get("SKU-1")).isEmpty();
        assertThat(count("error")).isEqualTo(1);
    }

    @Test
    void put_writes_json_with_the_configured_ttl() throws Exception {
        when(redis.opsForValue()).thenReturn(ops);

        cache.put(ITEM);

        verify(ops).set("inventory:item:SKU-1", MAPPER.writeValueAsString(ITEM), Duration.ofSeconds(10));
    }

    @Test
    void write_and_evict_failures_are_swallowed() {
        when(redis.opsForValue()).thenThrow(new RedisConnectionFailureException("down"));
        when(redis.delete(anyString())).thenThrow(new RedisConnectionFailureException("down"));

        cache.put(ITEM);
        cache.evict("SKU-1");

        assertThat(count("error")).isEqualTo(2);
    }
}
