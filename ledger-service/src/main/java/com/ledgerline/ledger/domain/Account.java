package com.ledgerline.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "accounts")
public class Account {

    @Id
    private UUID id;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(nullable = false)
    private String currency;

    /** Denormalised for fast reads; always equals the sum of this account's postings. */
    @Column(name = "balance_minor", nullable = false)
    private long balanceMinor;

    /** System accounts (the treasury) may go negative; customer accounts may not. */
    @Column(name = "system_account", nullable = false)
    private boolean systemAccount;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Version
    private long version;

    protected Account() {
        // for JPA
    }

    public static Account customer(String ownerName, String currency, Instant now) {
        Account a = new Account();
        a.id = UUID.randomUUID();
        a.ownerName = ownerName;
        a.currency = currency;
        a.createdAt = now;
        return a;
    }

    /** One treasury per currency, with a stable id so it is created only once. */
    public static UUID treasuryId(String currency) {
        return UUID.nameUUIDFromBytes(("treasury:" + currency).getBytes(StandardCharsets.UTF_8));
    }

    public static Account treasury(String currency, Instant now) {
        Account a = new Account();
        a.id = treasuryId(currency);
        a.ownerName = "Treasury (" + currency + ")";
        a.currency = currency;
        a.systemAccount = true;
        a.createdAt = now;
        return a;
    }

    public boolean canDebit(long amountMinor) {
        return systemAccount || balanceMinor >= amountMinor;
    }

    public void post(long amountMinor) {
        balanceMinor = DoubleEntry.apply(balanceMinor, amountMinor);
    }

    public UUID getId() { return id; }
    public String getOwnerName() { return ownerName; }
    public String getCurrency() { return currency; }
    public long getBalanceMinor() { return balanceMinor; }
    public boolean isSystemAccount() { return systemAccount; }
    public Instant getCreatedAt() { return createdAt; }
}
