package com.orderflow.inventory.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

@Configuration
public class ClockConfig {

    /** A real UTC clock in production; tests can supply a fixed {@link Clock} instead. */
    @Bean
    @ConditionalOnMissingBean
    public Clock clock() {
        return Clock.systemUTC();
    }
}
