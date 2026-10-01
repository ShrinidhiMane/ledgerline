package com.ledgerline.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.ledger.events.LedgerResultEvent;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.springframework.boot.test.context.TestComponent;
import org.springframework.kafka.annotation.KafkaListener;

/** Test-only listener recording what the ledger publishes, including dead-lettered messages. */
@TestComponent
public class ResultCollector {

    private final ObjectMapper json;
    private final Map<UUID, List<LedgerResultEvent>> results = new ConcurrentHashMap<>();
    private final List<String> deadLetters = new CopyOnWriteArrayList<>();

    public ResultCollector(ObjectMapper json) {
        this.json = json;
    }

    @KafkaListener(topics = "${ledgerline.topics.ledger-results}", groupId = "test-results")
    public void onResult(String message) throws Exception {
        LedgerResultEvent event = json.readValue(message, LedgerResultEvent.class);
        results.computeIfAbsent(event.paymentId(), k -> new CopyOnWriteArrayList<>()).add(event);
    }

    @KafkaListener(topics = "${ledgerline.topics.payment-requested}.DLT", groupId = "test-dlt")
    public void onDeadLetter(String message) {
        deadLetters.add(message);
    }

    public List<LedgerResultEvent> resultsFor(UUID paymentId) {
        return results.getOrDefault(paymentId, List.of());
    }

    public List<String> deadLetters() {
        return deadLetters;
    }
}
