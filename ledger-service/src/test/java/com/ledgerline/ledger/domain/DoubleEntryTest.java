package com.ledgerline.ledger.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DoubleEntryTest {

    private final UUID a = UUID.randomUUID();
    private final UUID b = UUID.randomUUID();

    @Test
    void transferDebitsPayerAndCreditsPayeeSummingToZero() {
        var lines = DoubleEntry.transfer(a, b, 2_500, "USD");

        assertThat(lines).containsExactly(
                new DoubleEntry.PostingLine(a, -2_500, "USD"),
                new DoubleEntry.PostingLine(b, 2_500, "USD"));
        assertThat(lines.stream().mapToLong(DoubleEntry.PostingLine::amountMinor).sum()).isZero();
    }

    @Test
    void rejectsNonPositiveAmountsAndSelfTransfers() {
        assertThatThrownBy(() -> DoubleEntry.transfer(a, b, 0, "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DoubleEntry.transfer(a, b, -1, "USD")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> DoubleEntry.transfer(a, a, 5, "USD")).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unbalancedOrMixedCurrencyEntriesAreRefused() {
        assertThatThrownBy(() -> DoubleEntry.assertBalanced(List.of(
                new DoubleEntry.PostingLine(a, -100, "USD"),
                new DoubleEntry.PostingLine(b, 99, "USD")))).hasMessageContaining("unbalanced");

        assertThatThrownBy(() -> DoubleEntry.assertBalanced(List.of(
                new DoubleEntry.PostingLine(a, -100, "USD"),
                new DoubleEntry.PostingLine(b, 100, "EUR")))).hasMessageContaining("currency");
    }

    @Test
    void balanceOverflowIsDetectedInsteadOfWrappingAround() {
        assertThatThrownBy(() -> DoubleEntry.apply(Long.MAX_VALUE, 1)).isInstanceOf(ArithmeticException.class);
    }

    @Test
    void customerCannotOverdrawButTreasuryCan() {
        Account customer = Account.customer("alice", "USD", Instant.EPOCH);
        customer.post(1_000);
        assertThat(customer.canDebit(1_000)).isTrue();
        assertThat(customer.canDebit(1_001)).isFalse();

        Account treasury = Account.treasury("USD", Instant.EPOCH);
        assertThat(treasury.canDebit(Long.MAX_VALUE)).isTrue();
    }

    @Test
    void treasuryIdIsStableAndMatchesTheMigrationSeed() {
        assertThat(Account.treasuryId("USD")).isEqualTo(UUID.fromString("4f5540b5-25e9-3d49-83fe-cb338c1417cf"));
        assertThat(Account.treasuryId("EUR")).isNotEqualTo(Account.treasuryId("USD"));
    }
}
