package com.ledgerline.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ledgerline.ledger.events.PaymentRequestedEvent;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, ResultCollector.class})
class LedgerIntegrationTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(45);
    private static final String TOPIC = "payments.requested";

    @Autowired TestRestTemplate http;
    @Autowired KafkaTemplate<String, String> kafka;
    @Autowired ObjectMapper json;
    @Autowired ResultCollector collector;

    // ---- helpers ------------------------------------------------------------------------

    private UUID openAccount(String owner, long openingBalance) {
        JsonNode res = http.postForObject("/api/v1/accounts",
                Map.of("ownerName", owner, "currency", "USD", "openingBalanceMinor", openingBalance), JsonNode.class);
        return UUID.fromString(res.get("id").asText());
    }

    private long balance(UUID account) {
        return http.getForObject("/api/v1/accounts/" + account, JsonNode.class).get("balanceMinor").asLong();
    }

    private PaymentRequestedEvent payment(UUID from, UUID to, long amount) {
        return new PaymentRequestedEvent(UUID.randomUUID(), UUID.randomUUID(), from, to, amount, "USD", Instant.now());
    }

    private CompletableFuture<?> publish(PaymentRequestedEvent e) throws Exception {
        return kafka.send(TOPIC, e.payerAccountId().toString(), json.writeValueAsString(e));
    }

    private JsonNode verify() {
        return http.getForObject("/api/v1/ledger/verify", JsonNode.class);
    }

    // ---- tests --------------------------------------------------------------------------

    @Test
    void openingBalanceIsFundedFromTreasuryAndBooksBalance() {
        UUID a = openAccount("alice", 10_000);

        assertThat(balance(a)).isEqualTo(10_000);
        assertThat(http.getForObject("/api/v1/accounts/" + a + "/postings", JsonNode.class).size()).isEqualTo(1);
        assertThat(verify().get("consistent").asBoolean()).isTrue();
    }

    @Test
    void transferIsPostedAsBalancedDoubleEntry() throws Exception {
        UUID a = openAccount("alice", 10_000);
        UUID b = openAccount("bob", 0);
        var e = payment(a, b, 2_500);
        publish(e).get();

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(collector.resultsFor(e.paymentId())).hasSize(1));
        assertThat(collector.resultsFor(e.paymentId()).get(0).status()).isEqualTo("POSTED");
        assertThat(balance(a)).isEqualTo(7_500);
        assertThat(balance(b)).isEqualTo(2_500);
        assertThat(verify().get("consistent").asBoolean()).isTrue();
    }

    @Test
    void redeliveredEventIsAppliedExactlyOnce() throws Exception {
        UUID a = openAccount("carol", 10_000);
        UUID b = openAccount("dave", 0);
        var e = payment(a, b, 1_000);
        publish(e).get();
        publish(e).get(); // Kafka redelivery / outbox double-send
        publish(e).get();

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(collector.resultsFor(e.paymentId())).isNotEmpty());
        Thread.sleep(2_000); // give the duplicates time to be (not) processed

        assertThat(collector.resultsFor(e.paymentId())).hasSize(1);
        assertThat(balance(a)).isEqualTo(9_000);
        assertThat(balance(b)).isEqualTo(1_000);
    }

    @Test
    void insufficientFundsIsRejectedAndNothingMoves() throws Exception {
        UUID a = openAccount("erin", 100);
        UUID b = openAccount("frank", 0);
        var e = payment(a, b, 500);
        publish(e).get();

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(collector.resultsFor(e.paymentId())).hasSize(1));
        var result = collector.resultsFor(e.paymentId()).get(0);
        assertThat(result.status()).isEqualTo("REJECTED");
        assertThat(result.reason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(balance(a)).isEqualTo(100);
        assertThat(balance(b)).isZero();
    }

    @Test
    void unknownAccountIsRejected() throws Exception {
        UUID a = openAccount("gina", 1_000);
        var e = payment(a, UUID.randomUUID(), 100);
        publish(e).get();

        await().atMost(TIMEOUT).untilAsserted(() -> assertThat(collector.resultsFor(e.paymentId())).hasSize(1));
        assertThat(collector.resultsFor(e.paymentId()).get(0).reason()).isEqualTo("ACCOUNT_NOT_FOUND");
        assertThat(balance(a)).isEqualTo(1_000);
    }

    @Test
    void concurrentTransfersInBothDirectionsConserveMoney() throws Exception {
        List<UUID> accounts = List.of(openAccount("w", 100_000), openAccount("x", 100_000),
                openAccount("y", 100_000), openAccount("z", 100_000));
        Random random = new Random(42);
        List<PaymentRequestedEvent> events = new ArrayList<>();
        for (int i = 0; i < 300; i++) {
            UUID from = accounts.get(random.nextInt(4));
            UUID to = accounts.get(random.nextInt(4));
            if (from.equals(to)) {
                continue;
            }
            events.add(payment(from, to, 1 + random.nextInt(5_000)));
        }
        List<CompletableFuture<?>> sends = new ArrayList<>();
        for (var e : events) {
            sends.add(publish(e));
        }
        CompletableFuture.allOf(sends.toArray(CompletableFuture[]::new)).get();

        await().atMost(Duration.ofSeconds(90)).untilAsserted(() ->
                assertThat(events).allSatisfy(e -> assertThat(collector.resultsFor(e.paymentId())).hasSize(1)));

        long total = accounts.stream().mapToLong(this::balance).sum();
        assertThat(total).isEqualTo(400_000); // money moved between them, never created or lost
        accounts.forEach(acc -> assertThat(balance(acc)).isNotNegative());
        assertThat(verify().get("consistent").asBoolean()).isTrue();
    }

    @Test
    void malformedMessageGoesToDeadLetterTopicWithoutBlockingOthers() throws Exception {
        kafka.send(TOPIC, "poison", "{this is not json").get();

        UUID a = openAccount("hank", 1_000);
        UUID b = openAccount("ivy", 0);
        var e = payment(a, b, 10);
        publish(e).get();

        await().atMost(TIMEOUT).untilAsserted(() -> {
            assertThat(collector.deadLetters()).contains("{this is not json");
            assertThat(collector.resultsFor(e.paymentId())).hasSize(1);
        });
    }
}
