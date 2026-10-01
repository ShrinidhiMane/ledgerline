package com.ledgerline.ledger.domain;

import java.util.List;
import java.util.UUID;

/**
 * Double-entry bookkeeping rules, kept free of Spring and the database so they are easy to
 * read and unit-test.
 *
 * <p>Convention: a posting's amount is signed. Positive = credit (balance goes up),
 * negative = debit (balance goes down). Every journal entry's postings must sum to exactly
 * zero, which means money is only ever moved, never created or destroyed.
 */
public final class DoubleEntry {

    private DoubleEntry() {}

    public record PostingLine(UUID accountId, long amountMinor, String currency) {}

    /** Postings for moving {@code amountMinor} from one account to another. */
    public static List<PostingLine> transfer(UUID from, UUID to, long amountMinor, String currency) {
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount must be positive");
        }
        if (from.equals(to)) {
            throw new IllegalArgumentException("cannot transfer to the same account");
        }
        List<PostingLine> lines = List.of(
                new PostingLine(from, Math.negateExact(amountMinor), currency),
                new PostingLine(to, amountMinor, currency));
        assertBalanced(lines);
        return lines;
    }

    /** Throws if the postings do not sum to zero per currency, or mix currencies. */
    public static void assertBalanced(List<PostingLine> lines) {
        if (lines.size() < 2) {
            throw new IllegalStateException("a journal entry needs at least two postings");
        }
        String currency = lines.get(0).currency();
        long sum = 0;
        for (PostingLine line : lines) {
            if (!line.currency().equals(currency)) {
                throw new IllegalStateException("postings in one entry must share a currency");
            }
            sum = Math.addExact(sum, line.amountMinor());
        }
        if (sum != 0) {
            throw new IllegalStateException("unbalanced journal entry: postings sum to " + sum);
        }
    }

    /** Applies a posting to a balance, refusing to overflow. */
    public static long apply(long balanceMinor, long postingAmountMinor) {
        return Math.addExact(balanceMinor, postingAmountMinor);
    }
}
