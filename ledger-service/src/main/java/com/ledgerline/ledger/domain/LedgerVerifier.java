package com.ledgerline.ledger.domain;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Checks the two invariants that prove the ledger is correct. */
@Service
public class LedgerVerifier {

    private final PostingRepository postings;

    public LedgerVerifier(PostingRepository postings) {
        this.postings = postings;
    }

    public record Mismatch(UUID accountId, long balanceMinor, long postedMinor) {}

    public record Report(boolean consistent, long sumOfAllPostingsMinor, List<Mismatch> mismatches) {}

    /**
     * 1. All postings ever written sum to zero (money is conserved).
     * 2. Every account's stored balance equals the sum of its postings.
     */
    @Transactional(readOnly = true)
    public Report verify() {
        long sum = postings.sumOfAllPostings().longValue();
        List<Mismatch> mismatches = postings.balanceMismatches().stream()
                .map(row -> new Mismatch((UUID) row[0], ((Number) row[1]).longValue(), ((Number) row[2]).longValue()))
                .toList();
        return new Report(sum == 0 && mismatches.isEmpty(), sum, mismatches);
    }
}
