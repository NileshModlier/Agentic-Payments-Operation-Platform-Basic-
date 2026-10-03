package com.apop.settlement.api;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.MessageListenerContainer;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestClient;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
class SettlementServiceTest {

    private static final UUID NOSTRO = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", SettlementSandbox::settlementJdbcUrl);
        registry.add("spring.datasource.username", SettlementSandbox::settlementUsername);
        registry.add("spring.datasource.password", SettlementSandbox::settlementPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/settlement");
        registry.add("apop.payment.base-url", SettlementSandbox::paymentBaseUrl);
        registry.add("apop.ledger.base-url", SettlementSandbox::ledgerBaseUrl);
        registry.add("spring.kafka.bootstrap-servers", SettlementSandbox::kafkaBootstrap);
        registry.add("apop.messaging.enabled", () -> "true");
        registry.add("apop.messaging.relay-delay-ms", () -> "200");
        registry.add("apop.messaging.retry-delay-ms", () -> "30000");
    }

    @Autowired
    private org.springframework.test.web.servlet.MockMvc mvc;

    @Autowired
    private KafkaListenerEndpointRegistry listeners;

    @Test
    void authorizedBookPaymentSettlesOnceAndPublishesPaymentSettled() throws Exception {
        resume();
        RestClient ledger = ledger();
        RestClient payment = payment();
        UUID debtor = openCustomer(ledger, "6000001111");
        UUID creditor = openCustomer(ledger, "6000002222");
        fund(ledger, debtor, 200_000L);

        String paymentId = createPayment(payment, debtor, creditor, 125_000L);
        await().atMost(Duration.ofSeconds(40)).until(() -> "SETTLED".equals(paymentStatus(payment, paymentId)));

        String settled = payment.get().uri("/v1/payments/{id}", paymentId).retrieve().body(String.class);
        String journalEntryId = com.jayway.jsonpath.JsonPath.read(settled, "$.journalEntryId");
        assertThat(journalEntryId).isNotBlank();

        mvc.perform(get("/v1/settlements/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemStatus").value("POSTED"))
                .andExpect(jsonPath("$.journalEntryId").value(journalEntryId))
                .andExpect(jsonPath("$.timeline[0].eventType").value("SettlementRequested"))
                .andExpect(jsonPath("$.timeline[1].eventType").value("SettlementPending"))
                .andExpect(jsonPath("$.timeline[2].eventType").value("SettlementItemPosted"));

        String scrape = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(scrape).contains("apop_payment_settled_total");
        assertThat(scrape).contains("apop_settlement_lag_seconds");
        assertThat(scrape).doesNotContain("payment_id=");
        assertThat(scrape).doesNotContain(paymentId);

        mvc.perform(post("/internal/v1/settlement-items/{id}/retries", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemStatus").value("POSTED"))
                .andExpect(jsonPath("$.journalEntryId").value(journalEntryId));

        String debtorAccount = ledger.get().uri("/internal/v1/accounts/{id}", debtor).retrieve().body(String.class);
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(debtorAccount, "$.ledgerBalanceMinor")).isEqualTo(75_000);
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(debtorAccount, "$.heldMinor")).isEqualTo(0);
        String creditorAccount = ledger.get().uri("/internal/v1/accounts/{id}", creditor).retrieve().body(String.class);
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(creditorAccount, "$.ledgerBalanceMinor")).isEqualTo(125_000);

        String ledgerView = ledger.get().uri("/internal/v1/payments/{id}/ledger", paymentId).retrieve().body(String.class);
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(ledgerView, "$.postings.length()")).isEqualTo(1);
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(ledgerView, "$.holds[0].status")).isEqualTo("CONSUMED");

        assertThat(eventNames("payments.payment.lifecycle.v1", paymentId))
                .contains("PaymentAuthorized", "PaymentSettlementPending", "PaymentSettled");
        assertThat(payloads("payments.settlement.item.v1", paymentId).stream()
                .filter(body -> body.contains("\"POSTED\""))
                .findFirst()).isPresent();
    }

    @Test
    void releasedHoldIsRefusedWithoutAJournal() throws Exception {
        resume();
        pause();
        RestClient ledger = ledger();
        RestClient payment = payment();
        UUID debtor = openCustomer(ledger, "6000003333");
        UUID creditor = openCustomer(ledger, "6000004444");
        fund(ledger, debtor, 200_000L);
        String paymentId = createPayment(payment, debtor, creditor, 40_000L);
        assertThat(paymentStatus(payment, paymentId)).isEqualTo("AUTHORIZED");

        ledger.post()
                .uri("/internal/v1/hold-releases")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"idempotencyKey\":\"hold:%s\"}".formatted(paymentId))
                .retrieve()
                .toBodilessEntity();
        resume();

        await().atMost(Duration.ofSeconds(40)).until(() -> "SETTLEMENT_FAILED".equals(paymentStatus(payment, paymentId)));
        String ledgerView = ledger.get().uri("/internal/v1/payments/{id}/ledger", paymentId).retrieve().body(String.class);
        assertThat(com.jayway.jsonpath.JsonPath.<Integer>read(ledgerView, "$.postings.length()")).isEqualTo(0);
        mvc.perform(get("/v1/settlements/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.itemStatus").value("POST_REFUSED"))
                .andExpect(jsonPath("$.timeline[?(@.eventType == 'SettlementRefused')]").exists());
    }

    private void resume() {
        await().atMost(Duration.ofSeconds(20)).until(() -> !listeners.getListenerContainers().isEmpty());
        listeners.getListenerContainers().forEach(MessageListenerContainer::resume);
    }

    private void pause() {
        listeners.getListenerContainers().forEach(MessageListenerContainer::pause);
    }

    private List<String> eventNames(String topic, String paymentId) throws Exception {
        return payloads(topic, paymentId).stream()
                .map(body -> com.jayway.jsonpath.JsonPath.<String>read(body, "$.data.eventName"))
                .toList();
    }

    private List<String> payloads(String topic, String key) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, SettlementSandbox.kafkaBootstrap());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "settlement-observer-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<String> matched = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(400))) {
                    if (key.equals(record.key())) {
                        matched.add(new String(record.value(), java.nio.charset.StandardCharsets.UTF_8));
                    }
                }
                if (!matched.isEmpty() && topic.contains("settlement")) {
                    break;
                }
                if (matched.stream().anyMatch(body -> body.contains("PaymentSettled"))) {
                    break;
                }
            }
        }
        return matched;
    }

    private static String createPayment(RestClient payment, UUID debtor, UUID creditor, long amount) {
        String endToEndId = "E2E" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = payment.post()
                .uri("/v1/payments")
                .contentType(MediaType.APPLICATION_JSON)
                .header("Idempotency-Key", "settle-" + UUID.randomUUID())
                .header("X-Correlation-Id", UUID.randomUUID().toString())
                .body("""
                        {
                          "endToEndId": "%s",
                          "rail": "BOOK",
                          "amount": { "minor": %d, "currency": "USD" },
                          "debtorAccountId": "%s",
                          "creditor": { "accountId": "%s" },
                          "requestedValueDate": "%s"
                        }
                        """.formatted(endToEndId, amount, debtor, creditor, LocalDate.now(ZoneId.of("America/New_York"))))
                .retrieve()
                .body(String.class);
        return com.jayway.jsonpath.JsonPath.read(body, "$.paymentId");
    }

    private static String paymentStatus(RestClient payment, String paymentId) {
        String body = payment.get().uri("/v1/payments/{id}", paymentId).retrieve().body(String.class);
        return com.jayway.jsonpath.JsonPath.read(body, "$.status");
    }

    private static RestClient ledger() {
        return RestClient.builder().baseUrl(SettlementSandbox.ledgerBaseUrl()).build();
    }

    private static RestClient payment() {
        return RestClient.builder().baseUrl(SettlementSandbox.paymentBaseUrl()).build();
    }

    private static UUID openCustomer(RestClient ledger, String accountNumber) {
        String body = ledger.post()
                .uri("/internal/v1/accounts")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "legalEntityId": "SIM-BANK",
                          "accountRole": "CUSTOMER",
                          "accountNumber": "%s",
                          "currency": "USD",
                          "normalBalance": "CREDIT"
                        }
                        """.formatted(accountNumber))
                .retrieve()
                .body(String.class);
        return UUID.fromString(com.jayway.jsonpath.JsonPath.read(body, "$.accountId"));
    }

    private static void fund(RestClient ledger, UUID accountId, long amount) {
        ledger.post()
                .uri("/internal/v1/postings")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "idempotencyKey": "fund-%s",
                          "entryType": "POSTING",
                          "valueDate": "%s",
                          "lines": [
                            {"accountId": "%s", "direction": "DEBIT", "amountMinor": %d, "currency": "USD"},
                            {"accountId": "%s", "direction": "CREDIT", "amountMinor": %d, "currency": "USD"}
                          ]
                        }
                        """.formatted(UUID.randomUUID(), LocalDate.now(), NOSTRO, amount, accountId, amount))
                .retrieve()
                .toBodilessEntity();
    }
}
