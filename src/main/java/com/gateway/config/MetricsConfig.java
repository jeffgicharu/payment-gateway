package com.gateway.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MetricsConfig {

    @Bean
    public Counter paymentInitiatedCounter(MeterRegistry registry) {
        return Counter.builder("payments.initiated.total")
                .description("Total payment initiations")
                .register(registry);
    }

    @Bean
    public Counter paymentCompletedCounter(MeterRegistry registry) {
        return Counter.builder("payments.completed.total")
                .description("Total successful payments")
                .register(registry);
    }

    @Bean
    public Counter paymentFailedCounter(MeterRegistry registry) {
        return Counter.builder("payments.failed.total")
                .description("Total failed payments")
                .register(registry);
    }

    @Bean
    public Counter refundCounter(MeterRegistry registry) {
        return Counter.builder("payments.refunds.total")
                .description("Total refunds processed")
                .register(registry);
    }

    @Bean
    public Timer paymentProcessingTimer(MeterRegistry registry) {
        return Timer.builder("payments.processing.duration")
                .description("Payment processing latency")
                .register(registry);
    }
}
