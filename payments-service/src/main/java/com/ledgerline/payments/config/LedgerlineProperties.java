package com.ledgerline.payments.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** All service-specific settings live under the {@code ledgerline.*} prefix in application.yml. */
@ConfigurationProperties(prefix = "ledgerline")
public record LedgerlineProperties(Topics topics, Outbox outbox, RateLimit rateLimit) {

    public record Topics(String paymentRequested, String ledgerResults) {}

    /** How often the outbox relay polls, and how many rows it publishes per poll. */
    public record Outbox(long pollIntervalMs, int batchSize) {}

    /** Token bucket per client: {@code capacity} burst size, refilled at {@code refillPerSecond}. */
    public record RateLimit(boolean enabled, int capacity, int refillPerSecond) {}
}
