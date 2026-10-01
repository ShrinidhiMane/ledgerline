package com.ledgerline.ledger.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "ledgerline")
public record LedgerlineProperties(Topics topics, Outbox outbox) {

    public record Topics(String paymentRequested, String ledgerResults) {}

    public record Outbox(long pollIntervalMs, int batchSize) {}
}
