package com.ledgerline.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.payments.events.LedgerResultEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "ledgerline.rate-limit.capacity=5",
        "ledgerline.rate-limit.refill-per-second=1"
})
@Import({TestcontainersConfiguration.class, PublishedEventCollector.class})
class PaymentsIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(30);

    @Autowired TestRestTemplate http;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired PublishedEventCollector published;

    private final UUID payer = UUID.randomUUID();
    private final UUID payee = UUID.randomUUID();

    private ResponseEntity<JsonNode> post(String idempotencyKey, String clientId, Map<String, Object> body) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        headers.set("X-Client-Id", clientId);
        return http.exchange("/api/v1/payments", HttpMethod.POST, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private Map<String, Object> body(long amount) {
        return Map.of("payerAccountId", payer, "payeeAccountId", payee, "amountMinor", amount, "currency", "USD");
    }

    private static String client() {
        return "client-" + UUID.randomUUID();
    }

    @Test
    void newPaymentIsAcceptedAndPublishedThroughTheOutbox() {
        var res = post("key-" + UUID.randomUUID(), client(), body(2_500));

        assertThat(res.getStatusCode().value()).isEqualTo(202);
        assertThat(res.getBody().get("status").asText()).isEqualTo("PENDING");
        UUID id = UUID.fromString(res.getBody().get("id").asText());

        await().atMost(TIMEOUT).untilAsserted(() -> {
            var events = published.eventsFor(id);
            assertThat(events).isNotEmpty();
            assertThat(events.get(0).amountMinor()).isEqualTo(2_500);
            assertThat(events.get(0).payerAccountId()).isEqualTo(payer);
        });
    }

    @Test
    void retryWithSameKeyAndBodyReturnsTheOriginalPayment() {
        String key = "key-" + UUID.randomUUID();
        String clientId = client();
        var first = post(key, clientId, body(1_000));
        var retry = post(key, clientId, body(1_000));

        assertThat(first.getStatusCode().value()).isEqualTo(202);
        assertThat(retry.getStatusCode().value()).isEqualTo(200);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody().get("id").asText()).isEqualTo(first.getBody().get("id").asText());
    }

    @Test
    void sameKeyWithDifferentBodyIsRejected() {
        String key = "key-" + UUID.randomUUID();
        String clientId = client();
        post(key, clientId, body(1_000));
        var conflicting = post(key, clientId, body(9_999));

        assertThat(conflicting.getStatusCode().value()).isEqualTo(422);
    }

    @Test
    void ledgerResultCompletesThePaymentAndLaterDuplicatesAreIgnored() throws Exception {
        var res = post("key-" + UUID.randomUUID(), client(), body(700));
        UUID id = UUID.fromString(res.getBody().get("id").asText());

        var posted = new LedgerResultEvent(UUID.randomUUID(), id, "POSTED", null, UUID.randomUUID(), Instant.now());
        kafka.send("ledger.results", id.toString(), json.writeValueAsString(posted)).get();

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(
                http.getForObject("/api/v1/payments/" + id, JsonNode.class).get("status").asText())
                .isEqualTo("COMPLETED"));

        // A late, contradictory duplicate must not change a final payment.
        var lateReject = new LedgerResultEvent(UUID.randomUUID(), id, "REJECTED", "INSUFFICIENT_FUNDS", null, Instant.now());
        kafka.send("ledger.results", id.toString(), json.writeValueAsString(lateReject)).get();
        Thread.sleep(1_500);
        assertThat(http.getForObject("/api/v1/payments/" + id, JsonNode.class).get("status").asText())
                .isEqualTo("COMPLETED");
    }

    @Test
    void rejectedLedgerResultFailsThePaymentWithReason() throws Exception {
        var res = post("key-" + UUID.randomUUID(), client(), body(700));
        UUID id = UUID.fromString(res.getBody().get("id").asText());

        var rejected = new LedgerResultEvent(UUID.randomUUID(), id, "REJECTED", "INSUFFICIENT_FUNDS", null, Instant.now());
        kafka.send("ledger.results", id.toString(), json.writeValueAsString(rejected)).get();

        await().atMost(TIMEOUT).untilAsserted(() -> {
            JsonNode p = http.getForObject("/api/v1/payments/" + id, JsonNode.class);
            assertThat(p.get("status").asText()).isEqualTo("FAILED");
            assertThat(p.get("failureReason").asText()).isEqualTo("INSUFFICIENT_FUNDS");
        });
    }

    @Test
    void invalidRequestsAreRejectedWith400() {
        assertThat(post(null, client(), body(100)).getStatusCode().value()).isEqualTo(400);
        assertThat(post("key-" + UUID.randomUUID(), client(), body(0)).getStatusCode().value()).isEqualTo(400);

        var samePayerAndPayee = Map.<String, Object>of("payerAccountId", payer, "payeeAccountId", payer,
                "amountMinor", 100, "currency", "USD");
        assertThat(post("key-" + UUID.randomUUID(), client(), samePayerAndPayee).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    void burstAboveTheLimitGets429WithRetryAfter() {
        String clientId = client();
        List<ResponseEntity<JsonNode>> responses = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            responses.add(post("key-" + UUID.randomUUID(), clientId, body(100)));
        }
        var limited = responses.stream().filter(r -> r.getStatusCode().value() == 429).toList();

        assertThat(limited).isNotEmpty();
        assertThat(limited.get(0).getHeaders().getFirst("Retry-After")).isNotBlank();
        // A different client has its own bucket and is unaffected.
        assertThat(post("key-" + UUID.randomUUID(), client(), body(100)).getStatusCode().value()).isEqualTo(202);
    }

    @Test
    void unknownPaymentIs404() {
        assertThat(http.getForEntity("/api/v1/payments/" + UUID.randomUUID(), JsonNode.class)
                .getStatusCode().value()).isEqualTo(404);
    }
}
