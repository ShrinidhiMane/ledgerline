package com.ledgerline.payments.events;

import java.time.Instant;
import java.util.UUID;

/** Published by ledger-service once a payment is posted or rejected. */
public record LedgerResultEvent(
        UUID eventId,
        UUID paymentId,
        String status, // POSTED | REJECTED
        String reason,
        UUID journalEntryId,
        Instant occurredAt) {

    public boolean posted() {
        return "POSTED".equals(status);
    }
}
