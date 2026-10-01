package com.ledgerline.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/** One line of a journal entry. Postings are append-only: never updated, never deleted. */
@Entity
@Table(name = "postings")
public class Posting {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @Column(nullable = false)
    private String currency;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Posting() {
        // for JPA
    }

    public Posting(UUID journalEntryId, DoubleEntry.PostingLine line, Instant createdAt) {
        this.journalEntryId = journalEntryId;
        this.accountId = line.accountId();
        this.amountMinor = line.amountMinor();
        this.currency = line.currency();
        this.createdAt = createdAt;
    }

    public Long getId() { return id; }
    public UUID getJournalEntryId() { return journalEntryId; }
    public UUID getAccountId() { return accountId; }
    public long getAmountMinor() { return amountMinor; }
    public String getCurrency() { return currency; }
    public Instant getCreatedAt() { return createdAt; }
}
