package com.orderflow.inventory.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;

@Configuration
public class CacheConfig {

    /** Redis-backed cache, only when explicitly enabled. */
    @Bean
    @ConditionalOnProperty(name = "inventory.cache.enabled", havingValue = "true")
    SkuCache redisSkuCache(StringRedisTemplate redis, ObjectMapper mapper, MeterRegistry meters,
                           @Value("${inventory.cache.ttl:PT10S}") Duration ttl) {
        return new RedisSkuCache(redis, mapper, ttl, meters);
    }

    @Bean
    @ConditionalOnProperty(name = "inventory.cache.enabled", havingValue = "false", matchIfMissing = true)
    SkuCache noopSkuCache() {
        return new NoopSkuCache();
    }
}
