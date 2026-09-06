package com.orderflow.inventory.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Scheduling is only switched on when {@code inventory.reservation.sweep-enabled=true}
 * (the default). Tests disable it so the expiry sweeper never races with assertions.
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "inventory.reservation.sweep-enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
