package com.ledgerline.payments.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.payments.domain.PaymentService;
import com.ledgerline.payments.events.LedgerResultEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/** Final step of the saga: the ledger tells us whether the money actually moved. */
@Component
public class LedgerResultListener {

    private static final Logger log = LoggerFactory.getLogger(LedgerResultListener.class);

    private final ObjectMapper json;
    private final PaymentService payments;

    public LedgerResultListener(ObjectMapper json, PaymentService payments) {
        this.json = json;
        this.payments = payments;
    }

    @KafkaListener(topics = "${ledgerline.topics.ledger-results}", groupId = "payments-service")
    public void onLedgerResult(String message) {
        LedgerResultEvent event;
        try {
            event = json.readValue(message, LedgerResultEvent.class);
        } catch (JsonProcessingException e) {
            // A malformed message will never parse, so retrying is pointless: log and skip.
            log.error("Dropping malformed ledger result: {}", message, e);
            return;
        }
        payments.applyLedgerResult(event);
    }
}
