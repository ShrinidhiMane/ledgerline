package com.ledgerline.payments.outbox;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.propagation.Propagator;
import java.util.HashMap;
import java.util.Map;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

/**
 * Saves and restores W3C trace context across the transactional outbox.
 *
 * <p>The outbox breaks the call chain on purpose: the request (or Kafka consumer) commits the
 * row and moves on, and the relay publishes it later on a scheduler thread. Left alone, the
 * trace would stop at the commit and an unrelated trace would start at the publish. Instead we
 * store the {@code traceparent} on the row, and the relay starts its publish span as a child
 * of it, so a single trace follows a payment through both services.
 */
@Component
public class TraceContextCodec {

    static final String TRACEPARENT = "traceparent";

    private final Tracer tracer;
    private final Propagator propagator;

    /** Falls back to no-op tracing when tracing is switched off (e.g. in some test contexts). */
    @Autowired
    public TraceContextCodec(ObjectProvider<Tracer> tracer, ObjectProvider<Propagator> propagator) {
        this(tracer.getIfAvailable(() -> Tracer.NOOP), propagator.getIfAvailable(() -> Propagator.NOOP));
    }

    TraceContextCodec(Tracer tracer, Propagator propagator) {
        this.tracer = tracer;
        this.propagator = propagator;
    }

    /** The active span's {@code traceparent} header value, or null if no span is active. */
    public String currentTraceParent() {
        Span span = tracer.currentSpan();
        if (span == null) {
            return null;
        }
        Map<String, String> carrier = new HashMap<>();
        propagator.inject(span.context(), carrier, Map::put);
        return carrier.get(TRACEPARENT);
    }

    /** Starts a span continuing the stored trace, or a new trace when nothing was stored. */
    public Span startSpan(String name, String traceParent) {
        if (traceParent == null || traceParent.isBlank()) {
            return tracer.nextSpan().name(name).start();
        }
        return propagator.extract(Map.of(TRACEPARENT, traceParent), Map::get).name(name).start();
    }

    public Tracer.SpanInScope withSpan(Span span) {
        return tracer.withSpan(span);
    }
}
