package com.orderflow.inventory.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.orderflow.inventory.service.ItemSnapshot;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Optional;

/**
 * Cache-aside store for {@link ItemSnapshot}s in Redis (JSON string per SKU, with a TTL).
 *
 * <p>Every Redis or (de)serialisation failure is caught, logged and counted, and the caller
 * falls back to PostgreSQL. A short TTL bounds how stale an entry can be if an invalidation is
 * ever missed (for example, a read repopulating the key between a write and its commit).
 */
public class RedisSkuCache implements SkuCache {

    private static final Logger log = LoggerFactory.getLogger(RedisSkuCache.class);
    static final String KEY_PREFIX = "inventory:item:";

    private final StringRedisTemplate redis;
    private final ObjectMapper mapper;
    private final Duration ttl;
    private final MeterRegistry meters;

    public RedisSkuCache(StringRedisTemplate redis, ObjectMapper mapper, Duration ttl, MeterRegistry meters) {
        this.redis = redis;
        this.mapper = mapper;
        this.ttl = ttl;
        this.meters = meters;
    }

    @Override
    public Optional<ItemSnapshot> get(String sku) {
        try {
            String json = redis.opsForValue().get(KEY_PREFIX + sku);
            if (json == null) {
                count("miss");
                return Optional.empty();
            }
            ItemSnapshot snapshot = mapper.readValue(json, ItemSnapshot.class);
            count("hit");
            return Optional.of(snapshot);
        } catch (RuntimeException | JsonProcessingException e) {
            return failed("get", sku, e);
        }
    }

    @Override
    public void put(ItemSnapshot snapshot) {
        try {
            redis.opsForValue().set(KEY_PREFIX + snapshot.sku(), mapper.writeValueAsString(snapshot), ttl);
        } catch (RuntimeException | JsonProcessingException e) {
            failed("put", snapshot.sku(), e);
        }
    }

    @Override
    public void evict(String sku) {
        try {
            redis.delete(KEY_PREFIX + sku);
        } catch (RuntimeException e) {
            failed("evict", sku, e);
        }
    }

    private Optional<ItemSnapshot> failed(String op, String sku, Exception e) {
        count("error");
        log.warn("sku cache {} failed for {}; falling back to the database: {}", op, sku, e.toString());
        return Optional.empty();
    }

    private void count(String result) {
        meters.counter("inventory.cache.requests", "result", result).increment();
    }
}
