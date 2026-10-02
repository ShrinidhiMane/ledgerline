package com.ledgerline.ledger.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.ledger.config.LedgerlineProperties;
import com.ledgerline.ledger.events.LedgerResultEvent;
import com.ledgerline.ledger.events.PaymentRequestedEvent;
import com.ledgerline.ledger.outbox.OutboxEvent;
import com.ledgerline.ledger.outbox.OutboxRepository;
import com.ledgerline.ledger.outbox.TraceContextCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class LedgerService {

    private static final Logger log = LoggerFactory.getLogger(LedgerService.class);

    public enum Outcome { POSTED, REJECTED, DUPLICATE }

    private final AccountRepository accounts;
    private final JournalEntryRepository entries;
    private final PostingRepository postings;
    private final ProcessedEventRepository processed;
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final LedgerlineProperties props;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TraceContextCodec traces;

    public LedgerService(AccountRepository accounts, JournalEntryRepository entries, PostingRepository postings,
                         ProcessedEventRepository processed, OutboxRepository outbox, ObjectMapper json,
                         LedgerlineProperties props, Clock clock, MeterRegistry metrics,
                         TraceContextCodec traces) {
        this.accounts = accounts;
        this.entries = entries;
        this.postings = postings;
        this.processed = processed;
        this.outbox = outbox;
        this.json = json;
        this.props = props;
        this.clock = clock;
        this.metrics = metrics;
        this.traces = traces;
    }

    /**
     * Posts one payment, all in ONE database transaction:
     * dedupe check, account locks, balance checks, postings, balance updates, and the outbox
     * row for the result event. Either everything commits or nothing does.
     */
    @Transactional
    public Outcome process(PaymentRequestedEvent event) {
        Instant now = clock.instant();

        // 1. Effectively-once: Kafka may redeliver; the same event must never post twice.
        if (processed.existsById(event.eventId()) || entries.existsByPaymentId(event.paymentId())) {
            metrics.counter("ledgerline.ledger.events", "outcome", "DUPLICATE").increment();
            return Outcome.DUPLICATE;
        }
        processed.saveAndFlush(new ProcessedEvent(event.eventId(), now));

        // 2. Validate and lock both accounts in a fixed order (no deadlocks).
        String rejection = validate(event);
        if (rejection != null) {
            return reject(event, rejection, now);
        }
        Map<UUID, Account> locked = accounts.lockAllOrdered(Set.of(event.payerAccountId(), event.payeeAccountId()))
                .stream().collect(Collectors.toMap(Account::getId, Function.identity()));
        Account payer = locked.get(event.payerAccountId());
        Account payee = locked.get(event.payeeAccountId());

        if (payer == null || payee == null) {
            return reject(event, "ACCOUNT_NOT_FOUND", now);
        }
        if (!payer.getCurrency().equals(event.currency()) || !payee.getCurrency().equals(event.currency())) {
            return reject(event, "CURRENCY_MISMATCH", now);
        }
        if (!payer.canDebit(event.amountMinor())) {
            return reject(event, "INSUFFICIENT_FUNDS", now);
        }

        // 3. Write the balanced journal entry and apply it to the balances.
        JournalEntry entry = entries.save(new JournalEntry(event.paymentId(), JournalEntry.Kind.TRANSFER, now));
        List<DoubleEntry.PostingLine> lines = DoubleEntry.transfer(
                payer.getId(), payee.getId(), event.amountMinor(), event.currency());
        for (DoubleEntry.PostingLine line : lines) {
            postings.save(new Posting(entry.getId(), line, now));
            locked.get(line.accountId()).post(line.amountMinor());
        }

        // 4. Tell payments-service, via the outbox (same transaction).
        enqueue(LedgerResultEvent.posted(event.paymentId(), entry.getId(), now));
        metrics.counter("ledgerline.ledger.events", "outcome", "POSTED").increment();
        return Outcome.POSTED;
    }

    private String validate(PaymentRequestedEvent e) {
        if (e.amountMinor() <= 0) {
            return "INVALID_AMOUNT";
        }
        if (e.payerAccountId().equals(e.payeeAccountId())) {
            return "SAME_ACCOUNT";
        }
        return null;
    }

    private Outcome reject(PaymentRequestedEvent event, String reason, Instant now) {
        log.info("Rejecting payment {}: {}", event.paymentId(), reason);
        enqueue(LedgerResultEvent.rejected(event.paymentId(), reason, now));
        metrics.counter("ledgerline.ledger.events", "outcome", "REJECTED").increment();
        return Outcome.REJECTED;
    }

    private void enqueue(LedgerResultEvent result) {
        try {
            outbox.save(new OutboxEvent(result.eventId(), result.paymentId(), "LedgerResult",
                    props.topics().ledgerResults(), result.paymentId().toString(),
                    json.writeValueAsString(result), result.occurredAt(),
                    traces.currentTraceParent()));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize ledger result", e);
        }
    }
}
