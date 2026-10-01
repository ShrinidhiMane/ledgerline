package com.ledgerline.ledger.domain;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final PostingRepository postings;
    private final Clock clock;

    public AccountService(AccountRepository accounts, JournalEntryRepository entries,
                          PostingRepository postings, Clock clock) {
        this.accounts = accounts;
        this.entries = entries;
        this.postings = postings;
        this.clock = clock;
    }

    /**
     * Opens an account. An opening balance is never just written into the balance column: it
     * is a FUNDING journal entry from the currency's treasury, so the ledger still sums to zero.
     */
    @Transactional
    public Account open(String ownerName, String currency, long openingBalanceMinor) {
        if (openingBalanceMinor < 0) {
            throw new IllegalArgumentException("opening balance cannot be negative");
        }
        Instant now = clock.instant();
        Account account = accounts.save(Account.customer(ownerName, currency, now));
        if (openingBalanceMinor > 0) {
            ensureTreasury(currency, now);
            UUID treasuryId = Account.treasuryId(currency);
            var locked = accounts.lockAllOrdered(List.of(treasuryId, account.getId()));
            JournalEntry entry = entries.save(new JournalEntry(null, JournalEntry.Kind.FUNDING, now));
            for (DoubleEntry.PostingLine line : DoubleEntry.transfer(treasuryId, account.getId(), openingBalanceMinor, currency)) {
                postings.save(new Posting(entry.getId(), line, now));
                locked.stream().filter(a -> a.getId().equals(line.accountId())).findFirst().orElseThrow()
                        .post(line.amountMinor());
            }
        }
        return accounts.findById(account.getId()).orElseThrow();
    }

    private void ensureTreasury(String currency, Instant now) {
        if (!accounts.existsById(Account.treasuryId(currency))) {
            accounts.save(Account.treasury(currency, now));
            accounts.flush();
        }
    }

    @Transactional(readOnly = true)
    public Account get(UUID id) {
        return accounts.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public List<Posting> recentPostings(UUID accountId, int limit) {
        get(accountId);
        return postings.findByAccountIdOrderByIdDesc(accountId, PageRequest.of(0, limit));
    }
}
