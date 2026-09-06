package com.orderflow.order.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/** Scheduling is on by default; tests set {@code order.reconciler.enabled=false} to disable it. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "order.reconciler.enabled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {
}
