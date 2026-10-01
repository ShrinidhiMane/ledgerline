package com.ledgerline.payments.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "payments")
public class Payment {

    @Id
    private UUID id;

    @Column(name = "idempotency_key", nullable = false, unique = true)
    private String idempotencyKey;

    @Column(name = "request_hash", nullable = false)
    private String requestHash;

    @Column(name = "payer_account_id", nullable = false)
    private UUID payerAccountId;

    @Column(name = "payee_account_id", nullable = false)
    private UUID payeeAccountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PaymentStatus status;

    @Column(name = "failure_reason")
    private String failureReason;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected Payment() {
        // for JPA
    }

    public static Payment pending(String idempotencyKey, String requestHash, UUID payer, UUID payee,
                                  long amountMinor, String currency, Instant now) {
        Payment p = new Payment();
        p.id = UUID.randomUUID();
        p.idempotencyKey = idempotencyKey;
        p.requestHash = requestHash;
        p.payerAccountId = payer;
        p.payeeAccountId = payee;
        p.amountMinor = amountMinor;
        p.currency = currency;
        p.status = PaymentStatus.PENDING;
        p.createdAt = now;
        p.updatedAt = now;
        return p;
    }

    /**
     * Moves PENDING to a final state. Returns false if the payment is already final, which
     * makes duplicate result events harmless.
     */
    public boolean complete(boolean posted, String reason, Instant now) {
        if (status != PaymentStatus.PENDING) {
            return false;
        }
        status = posted ? PaymentStatus.COMPLETED : PaymentStatus.FAILED;
        failureReason = posted ? null : reason;
        updatedAt = now;
        return true;
    }

    public UUID getId() { return id; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getRequestHash() { return requestHash; }
    public UUID getPayerAccountId() { return payerAccountId; }
    public UUID getPayeeAccountId() { return payeeAccountId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public PaymentStatus getStatus() { return status; }
    public String getFailureReason() { return failureReason; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
