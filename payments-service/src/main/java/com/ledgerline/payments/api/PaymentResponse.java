package com.ledgerline.payments.api;

import com.ledgerline.payments.domain.Payment;
import java.time.Instant;
import java.util.UUID;

public record PaymentResponse(
        UUID id,
        UUID payerAccountId,
        UUID payeeAccountId,
        long amountMinor,
        String currency,
        String status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    static PaymentResponse from(Payment p) {
        return new PaymentResponse(p.getId(), p.getPayerAccountId(), p.getPayeeAccountId(),
                p.getAmountMinor(), p.getCurrency(), p.getStatus().name(), p.getFailureReason(),
                p.getCreatedAt(), p.getUpdatedAt());
    }
}
