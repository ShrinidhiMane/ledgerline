package com.ledgerline.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One business event in the ledger (a transfer or an account funding), made of postings. */
@Entity
@Table(name = "journal_entries")
public class JournalEntry {

    public enum Kind { TRANSFER, FUNDING }

    @Id
    private UUID id;

    /** Unique: a payment can be posted at most once, enforced by the database too. */
    @Column(name = "payment_id", unique = true)
    private UUID paymentId;

    @Column(nullable = false)
    private String kind;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected JournalEntry() {
        // for JPA
    }

    public JournalEntry(UUID paymentId, Kind kind, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.paymentId = paymentId;
        this.kind = kind.name();
        this.createdAt = createdAt;
    }

    public UUID getId() { return id; }
    public UUID getPaymentId() { return paymentId; }
    public String getKind() { return kind; }
    public Instant getCreatedAt() { return createdAt; }
}
