package com.ledgerline.ledger.events;

import java.time.Instant;
import java.util.UUID;

/** Published back to payments-service once a payment is posted or rejected. */
public record LedgerResultEvent(
        UUID eventId,
        UUID paymentId,
        String status, // POSTED | REJECTED
        String reason,
        UUID journalEntryId,
        Instant occurredAt) {

    public static LedgerResultEvent posted(UUID paymentId, UUID journalEntryId, Instant now) {
        return new LedgerResultEvent(UUID.randomUUID(), paymentId, "POSTED", null, journalEntryId, now);
    }

    public static LedgerResultEvent rejected(UUID paymentId, String reason, Instant now) {
        return new LedgerResultEvent(UUID.randomUUID(), paymentId, "REJECTED", reason, null, now);
    }
}
