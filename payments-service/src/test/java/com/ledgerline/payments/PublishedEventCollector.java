package com.ledgerline.payments;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.payments.events.PaymentRequestedEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.kafka.annotation.KafkaListener;

/** Test-only listener that records every PaymentRequested event the service publishes. */
@TestComponent
public class PublishedEventCollector {

    private final ObjectMapper json;
    private final Map<UUID, List<PaymentRequestedEvent>> byPaymentId = new ConcurrentHashMap<>();

    public PublishedEventCollector(ObjectMapper json) {
        this.json = json;
    }

    @KafkaListener(topics = "${ledgerline.topics.payment-requested}", groupId = "test-collector")
    public void collect(String message) throws Exception {
        PaymentRequestedEvent event = json.readValue(message, PaymentRequestedEvent.class);
        byPaymentId.computeIfAbsent(event.paymentId(), k -> new CopyOnWriteArrayList<>()).add(event);
    }

    public List<PaymentRequestedEvent> eventsFor(UUID paymentId) {
        return byPaymentId.getOrDefault(paymentId, List.of());
    }
}
