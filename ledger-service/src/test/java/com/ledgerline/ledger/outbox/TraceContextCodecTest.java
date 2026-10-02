package com.ledgerline.ledger.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import io.micrometer.tracing.otel.bridge.OtelCurrentTraceContext;
import io.micrometer.tracing.otel.bridge.OtelPropagator;
import io.micrometer.tracing.otel.bridge.OtelTracer;
import io.micrometer.tracing.propagation.Propagator;
import io.opentelemetry.api.trace.propagation.W3CTraceContextPropagator;
import io.opentelemetry.context.propagation.ContextPropagators;
import io.opentelemetry.sdk.trace.SdkTracerProvider;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TraceContextCodecTest {

    private final SdkTracerProvider provider = SdkTracerProvider.builder().build();
    private final io.opentelemetry.api.trace.Tracer otel = provider.get("test");
    private final Tracer tracer = new OtelTracer(otel, new OtelCurrentTraceContext(), event -> { });
    private final Propagator propagator =
            new OtelPropagator(ContextPropagators.create(W3CTraceContextPropagator.getInstance()), otel);
    private final TraceContextCodec codec = new TraceContextCodec(tracer, propagator);

    @AfterEach
    void shutdown() {
        provider.close();
    }

    @Test
    void noActiveSpanMeansNoTraceParent() {
        assertThat(codec.currentTraceParent()).isNull();
    }

    @Test
    void capturesTheActiveSpanAsW3cTraceParent() {
        Span request = tracer.nextSpan().name("http request").start();
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            assertThat(codec.currentTraceParent()).isEqualTo(
                    "00-" + request.context().traceId() + "-" + request.context().spanId() + "-01");
        } finally {
            request.end();
        }
    }

    @Test
    void publishSpanContinuesTheStoredTrace() {
        Span request = tracer.nextSpan().name("http request").start();
        String stored;
        try (Tracer.SpanInScope ignored = tracer.withSpan(request)) {
            stored = codec.currentTraceParent();
        } finally {
            request.end();
        }

        // Later, on the relay thread, with no span active:
        Span publish = codec.startSpan("outbox publish", stored);
        try {
            assertThat(publish.context().traceId()).isEqualTo(request.context().traceId());
            assertThat(publish.context().parentId()).isEqualTo(request.context().spanId());
        } finally {
            publish.end();
        }
    }

    @Test
    void rowWithoutTraceParentStartsANewTrace() {
        Span publish = codec.startSpan("outbox publish", null);
        try {
            assertThat(publish.context().traceId()).isNotBlank();
            // OpenTelemetry reports "no parent" as an all-zero span id.
            assertThat(publish.context().parentId()).satisfiesAnyOf(
                    parent -> assertThat(parent).isNull(),
                    parent -> assertThat(parent).matches("0+"));
        } finally {
            publish.end();
        }
    }
}
