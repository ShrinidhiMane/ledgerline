package com.ledgerline.ledger.events;

import java.time.Instant;
import java.util.UUID;

/** Consumed from payments-service. Field names must match its PaymentRequestedEvent. */
public record PaymentRequestedEvent(
        UUID eventId,
        UUID paymentId,
        UUID payerAccountId,
        UUID payeeAccountId,
        long amountMinor,
        String currency,
        Instant occurredAt) {}
