package com.ledgerline.payments.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PaymentDomainTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void sameRequestProducesSameHash() {
        assertThat(RequestHasher.hash(a, b, 500, "USD")).isEqualTo(RequestHasher.hash(a, b, 500, "USD"));
    }

    @Test
    void anyChangedFieldChangesTheHash() {
        String base = RequestHasher.hash(a, b, 500, "USD");
        assertThat(RequestHasher.hash(b, a, 500, "USD")).isNotEqualTo(base);
        assertThat(RequestHasher.hash(a, b, 501, "USD")).isNotEqualTo(base);
        assertThat(RequestHasher.hash(a, b, 500, "EUR")).isNotEqualTo(base);
    }

    @Test
    void pendingPaymentCompletesOnlyOnce() {
        Payment p = Payment.pending("k", "h", a, b, 500, "USD", Instant.EPOCH);

        assertThat(p.complete(true, null, Instant.EPOCH)).isTrue();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);

        assertThat(p.complete(false, "INSUFFICIENT_FUNDS", Instant.EPOCH)).isFalse();
        assertThat(p.getStatus()).isEqualTo(PaymentStatus.COMPLETED);
        assertThat(p.getFailureReason()).isNull();
    }

    @Test
    void rejectedPaymentKeepsTheReason() {
        Payment p = Payment.pending("k", "h", a, b, 500, "USD", Instant.EPOCH);
        p.complete(false, "ACCOUNT_NOT_FOUND", Instant.EPOCH);

        assertThat(p.getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(p.getFailureReason()).isEqualTo("ACCOUNT_NOT_FOUND");
    }
}
