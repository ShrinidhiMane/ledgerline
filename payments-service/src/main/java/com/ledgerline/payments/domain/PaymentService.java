package com.ledgerline.payments.domain;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.payments.config.LedgerlineProperties;
import com.ledgerline.payments.events.LedgerResultEvent;
import com.ledgerline.payments.events.PaymentRequestedEvent;
import com.ledgerline.payments.outbox.OutboxEvent;
import com.ledgerline.payments.outbox.OutboxRepository;
import com.ledgerline.payments.outbox.TraceContextCodec;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    private final PaymentRepository payments;
    private final OutboxRepository outbox;
    private final ObjectMapper json;
    private final LedgerlineProperties props;
    private final TransactionTemplate tx;
    private final Clock clock;
    private final MeterRegistry metrics;
    private final TraceContextCodec traces;

    public PaymentService(PaymentRepository payments, OutboxRepository outbox, ObjectMapper json,
                          LedgerlineProperties props, TransactionTemplate tx, Clock clock,
                          MeterRegistry metrics, TraceContextCodec traces) {
        this.payments = payments;
        this.outbox = outbox;
        this.json = json;
        this.props = props;
        this.tx = tx;
        this.clock = clock;
        this.metrics = metrics;
        this.traces = traces;
    }

    public record CreateResult(Payment payment, boolean replayed) {}

    /**
     * Creates a payment exactly once per Idempotency-Key.
     *
     * <ol>
     *   <li>Key seen before with the same body: return the original payment (safe client retry).</li>
     *   <li>Key seen before with a different body: reject with a conflict.</li>
     *   <li>New key: save the payment AND its outbox event in one transaction.</li>
     * </ol>
     * Two concurrent requests with the same new key race on the unique index; the loser catches
     * the constraint violation and falls back to case 1 or 2.
     */
    public CreateResult create(String idempotencyKey, UUID payer, UUID payee, long amountMinor, String currency) {
        String hash = RequestHasher.hash(payer, payee, amountMinor, currency);

        var existing = payments.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return replay(existing.get(), idempotencyKey, hash);
        }
        try {
            Payment created = tx.execute(status -> insertWithOutbox(idempotencyKey, hash, payer, payee, amountMinor, currency));
            metrics.counter("ledgerline.payments.created").increment();
            return new CreateResult(created, false);
        } catch (DataIntegrityViolationException race) {
            log.info("Concurrent request with Idempotency-Key {} won the race; replaying", idempotencyKey);
            Payment winner = payments.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> race);
            return replay(winner, idempotencyKey, hash);
        }
    }

    private CreateResult replay(Payment existing, String key, String hash) {
        if (!existing.getRequestHash().equals(hash)) {
            metrics.counter("ledgerline.payments.idempotency_conflicts").increment();
            throw new IdempotencyConflictException(key);
        }
        metrics.counter("ledgerline.payments.replayed").increment();
        return new CreateResult(existing, true);
    }

    private Payment insertWithOutbox(String key, String hash, UUID payer, UUID payee, long amountMinor, String currency) {
        Instant now = clock.instant();
        Payment payment = payments.saveAndFlush(Payment.pending(key, hash, payer, payee, amountMinor, currency, now));

        var event = new PaymentRequestedEvent(UUID.randomUUID(), payment.getId(), payer, payee, amountMinor, currency, now);
        outbox.save(new OutboxEvent(event.eventId(), payment.getId(), "PaymentRequested",
                props.topics().paymentRequested(), payer.toString(), toJson(event), now,
                traces.currentTraceParent()));
        return payment;
    }

    /** Applies the ledger's verdict. Safe to call more than once for the same payment. */
    @Transactional
    public void applyLedgerResult(LedgerResultEvent result) {
        var payment = payments.findById(result.paymentId());
        if (payment.isEmpty()) {
            log.warn("Ledger result for unknown payment {}", result.paymentId());
            return;
        }
        boolean changed = payment.get().complete(result.posted(), result.reason(), clock.instant());
        if (changed) {
            metrics.counter("ledgerline.payments.finalized", "status", payment.get().getStatus().name()).increment();
        }
    }

    @Transactional(readOnly = true)
    public Payment get(UUID id) {
        return payments.findById(id).orElseThrow(() -> new PaymentNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public Map<PaymentStatus, Long> statusCounts() {
        Map<PaymentStatus, Long> counts = new EnumMap<>(PaymentStatus.class);
        for (PaymentStatus s : PaymentStatus.values()) {
            counts.put(s, 0L);
        }
        payments.countByStatus().forEach(c -> counts.put(c.getStatus(), c.getCount()));
        return counts;
    }

    private String toJson(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not serialize event", e);
        }
    }
}
