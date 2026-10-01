package com.ledgerline.payments.events;

import java.time.Instant;
import java.util.UUID;

/** Published by payments-service, consumed by ledger-service. */
public record PaymentRequestedEvent(
        UUID eventId,
        UUID paymentId,
        UUID payerAccountId,
        UUID payeeAccountId,
        long amountMinor,
        String currency,
        Instant occurredAt) {}
