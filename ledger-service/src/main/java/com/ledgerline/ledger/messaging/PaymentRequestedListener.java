package com.ledgerline.ledger.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.ledger.domain.LedgerService;
import com.ledgerline.ledger.events.PaymentRequestedEvent;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class PaymentRequestedListener {

    private final ObjectMapper json;
    private final LedgerService ledger;

    public PaymentRequestedListener(ObjectMapper json, LedgerService ledger) {
        this.json = json;
        this.ledger = ledger;
    }

    @KafkaListener(topics = "${ledgerline.topics.payment-requested}", groupId = "ledger-service",
            concurrency = "${ledgerline.consumer-concurrency:3}")
    public void onPaymentRequested(String message) {
        PaymentRequestedEvent event;
        try {
            event = json.readValue(message, PaymentRequestedEvent.class);
        } catch (JsonProcessingException e) {
            throw new MalformedEventException("Unparseable payment event", e);
        }
        ledger.process(event);
    }
}
