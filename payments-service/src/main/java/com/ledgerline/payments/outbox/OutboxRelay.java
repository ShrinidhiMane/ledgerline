package com.ledgerline.payments.outbox;

import com.ledgerline.payments.config.LedgerlineProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Publishes outbox rows to Kafka. Delivery is at-least-once: if Kafka accepts a message but the
 * process dies before the row is marked published, the row is sent again on the next poll.
 * Every event carries a unique eventId so consumers can drop duplicates.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxRepository outbox;
    private final KafkaTemplate<String, String> kafka;
    private final LedgerlineProperties props;
    private final Clock clock;
    private final Counter published;
    private final TraceContextCodec traces;

    public OutboxRelay(OutboxRepository outbox, KafkaTemplate<String, String> kafka,
                       LedgerlineProperties props, Clock clock, MeterRegistry registry,
                       TraceContextCodec traces) {
        this.outbox = outbox;
        this.kafka = kafka;
        this.props = props;
        this.clock = clock;
        this.traces = traces;
        this.published = Counter.builder("ledgerline.outbox.published")
                .description("Outbox events published to Kafka").register(registry);
        Gauge.builder("ledgerline.outbox.backlog", outbox, OutboxRepository::countByPublishedAtIsNull)
                .description("Outbox events not yet published").register(registry);
    }

    @Scheduled(fixedDelayString = "${ledgerline.outbox.poll-interval-ms}")
    @Transactional
    public void publishPending() {
        List<OutboxEvent> batch = outbox.lockNextBatch(props.outbox().batchSize());
        if (batch.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        for (OutboxEvent event : batch) {
            // Continue the trace of the request that wrote this row. The Kafka producer span
            // becomes a child of this one and carries the context on in the record headers.
            Span span = traces.startSpan("outbox publish " + event.getEventType(), event.getTraceParent())
                    .tag("messaging.destination.name", event.getTopic())
                    .tag("ledgerline.event_id", event.getEventId().toString());
            try (Tracer.SpanInScope ignored = traces.withSpan(span)) {
                // Wait for the broker ack so we only mark rows that Kafka really has.
                kafka.send(event.getTopic(), event.getEventKey(), event.getPayload()).get(10, TimeUnit.SECONDS);
            } catch (Exception e) {
                span.error(e);
                span.end();
                // Leave this and later rows unpublished; the transaction keeps the rows already
                // marked in this batch, and the rest are retried on the next poll.
                log.warn("Outbox publish failed for event {}: {}", event.getEventId(), e.toString());
                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }
                return;
            }
            span.end();
            event.markPublished(now);
            published.increment();
        }
    }
}
