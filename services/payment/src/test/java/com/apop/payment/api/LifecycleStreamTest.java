package com.apop.payment.api;

import com.apop.payment.messaging.LifecycleProjection;
import com.apop.payment.messaging.LifecycleProjectionRepository;
import com.apop.payment.messaging.LifecycleInbox;
import com.apop.payment.messaging.OutboxMessage;
import com.apop.payment.messaging.OutboxRepository;
import com.apop.payment.messaging.ProcessedEvent;
import com.apop.payment.messaging.ProcessedEventRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class LifecycleStreamTest {

    private static final UUID NOSTRO = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final String TOPIC = "payments.payment.lifecycle.v1";
    private static final String DEAD_LETTER = TOPIC + ".transaction-history-projector.dlq";

    private static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka-native:3.8.1"));

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        if (!KAFKA.isRunning()) {
            KAFKA.start();
        }
        registry.add("spring.datasource.url", PaymentSandbox::paymentJdbcUrl);
        registry.add("spring.datasource.username", PaymentSandbox::paymentUsername);
        registry.add("spring.datasource.password", PaymentSandbox::paymentPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/payment");
        registry.add("apop.ledger.base-url", PaymentSandbox::ledgerBaseUrl);
        registry.add("apop.fraud.mode", () -> "local");
        registry.add("apop.messaging.enabled", () -> "true");
        registry.add("apop.messaging.relay-delay-ms", () -> "200");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private LifecycleProjectionRepository projections;

    @Autowired
    private ProcessedEventRepository processedEvents;

    @Autowired
    private KafkaTemplate<String, byte[]> kafkaTemplate;

    @Autowired
    private LifecycleInbox inbox;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void authorizedPaymentPublishesOrderedLifecycleAndIgnoresADuplicateDelivery() throws Exception {
        RestClient ledger = RestClient.builder().baseUrl(PaymentSandbox.ledgerBaseUrl()).build();
        UUID debtor = openCustomer(ledger, "4000001111");
        UUID creditor = openCustomer(ledger, "4000002222");
        fund(ledger, debtor, 200_000L);
        UUID correlationId = UUID.randomUUID();

        MvcResult created = mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "life-" + UUID.randomUUID())
                        .header("X-Correlation-Id", correlationId.toString())
                        .content(payment(debtor, creditor, 125_000L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andReturn();
        String paymentId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.paymentId");

        await().atMost(Duration.ofSeconds(30)).until(() ->
                projections.findByPaymentIdOrderByOccurredAtAscEventIdAsc(UUID.fromString(paymentId)).size() == 3
                        && outbox.findByRecordKeyOrderByCreatedAtAscOutboxIdAsc(paymentId).stream()
                        .allMatch(row -> row.getPublishedAt() != null));

        List<LifecycleProjection> projected =
                projections.findByPaymentIdOrderByOccurredAtAscEventIdAsc(UUID.fromString(paymentId));
        assertThat(projected).extracting(LifecycleProjection::getEventName)
                .containsExactly("PaymentReceived", "PaymentValidated", "PaymentAuthorized");
        assertThat(projected).extracting(LifecycleProjection::getStatus)
                .containsExactly("RECEIVED", "VALIDATED", "AUTHORIZED");
        assertThat(projected.get(0).getPreviousStatus()).isNull();
        assertThat(projected.get(1).getPreviousStatus()).isEqualTo("RECEIVED");
        assertThat(projected.get(2).getPreviousStatus()).isEqualTo("VALIDATED");

        List<OutboxMessage> rows = outbox.findByRecordKeyOrderByCreatedAtAscOutboxIdAsc(paymentId);
        assertThat(rows).hasSize(3);
        assertThat(rows).allMatch(row -> row.getPublishedAt() != null);
        assertThat(rows).extracting(OutboxMessage::getTopic).containsOnly(TOPIC);

        List<ConsumerRecord<String, byte[]>> records = read(TOPIC, paymentId, 3);
        assertThat(records).extracting(ConsumerRecord::key).containsOnly(paymentId);
        List<String> names = new ArrayList<>();
        UUID previousEventId = null;
        for (ConsumerRecord<String, byte[]> record : records) {
            JsonNode envelope = objectMapper.readTree(record.value());
            names.add(envelope.path("data").path("eventName").asText());
            assertThat(envelope.path("specversion").asText()).isEqualTo("1.0");
            assertThat(envelope.path("source").asText()).isEqualTo("urn:apop:payment-service");
            assertThat(envelope.path("type").asText()).isEqualTo("com.apop.payments.payment.lifecycle.v1");
            assertThat(envelope.path("subject").asText()).isEqualTo(paymentId);
            assertThat(envelope.path("datacontenttype").asText()).isEqualTo("application/json");
            assertThat(envelope.toString()).doesNotContain("remittance");
            assertThat(header(record, "ce_id")).isEqualTo(envelope.path("id").asText());
            assertThat(header(record, "ce_type")).isEqualTo("com.apop.payments.payment.lifecycle.v1");
            assertThat(header(record, "correlation-id")).isEqualTo(correlationId.toString());
            assertThat(header(record, "legal-entity-id")).isEqualTo("SIM-BANK");
            assertThat(header(record, "content-type")).isEqualTo("application/cloudevents+json");
            assertThat(header(record, "traceparent")).matches("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]");
            if (previousEventId == null) {
                assertThat(header(record, "causation-id")).isEqualTo(correlationId.toString());
            } else {
                assertThat(header(record, "causation-id")).isEqualTo(previousEventId.toString());
            }
            previousEventId = UUID.fromString(envelope.path("id").asText());
            ProcessedEvent processed = processedEvents.findById(previousEventId).orElseThrow();
            assertThat(processed.getOutcome()).isEqualTo("APPLIED");
        }
        assertThat(names).containsExactly("PaymentReceived", "PaymentValidated", "PaymentAuthorized");

        kafkaTemplate.send(TOPIC, paymentId, records.getFirst().value()).get();
        inbox.project(records.getFirst().value());
        await().atMost(Duration.ofSeconds(20)).until(this::projectorCaughtUp);
        assertThat(projections.findByPaymentIdOrderByOccurredAtAscEventIdAsc(UUID.fromString(paymentId))).hasSize(3);

        UUID poisonId = UUID.randomUUID();
        ProducerRecord<String, byte[]> poison = new ProducerRecord<>(
                TOPIC, UUID.randomUUID().toString(), "not-json".getBytes(StandardCharsets.UTF_8));
        poison.headers().add("ce_id", poisonId.toString().getBytes(StandardCharsets.UTF_8));
        poison.headers().add("ce_type", "com.apop.payments.payment.lifecycle.v1".getBytes(StandardCharsets.UTF_8));
        kafkaTemplate.send(poison).get();

        await().atMost(Duration.ofSeconds(30)).until(() ->
                processedEvents.findById(poisonId)
                        .map(event -> "DEAD_LETTERED".equals(event.getOutcome()))
                        .orElse(false));
        assertThat(read(DEAD_LETTER, poison.key(), 1)).hasSize(1);
    }

    private List<ConsumerRecord<String, byte[]>> read(String topic, String key, int needed) {
        Properties properties = new Properties();
        properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
        properties.put(ConsumerConfig.GROUP_ID_CONFIG, "lifecycle-observer-" + UUID.randomUUID());
        properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
        properties.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        List<ConsumerRecord<String, byte[]>> matched = new ArrayList<>();
        try (KafkaConsumer<String, byte[]> consumer = new KafkaConsumer<>(properties)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
            while (System.nanoTime() < deadline && matched.size() < needed) {
                for (ConsumerRecord<String, byte[]> record : consumer.poll(Duration.ofMillis(400))) {
                    if (key.equals(record.key())) {
                        matched.add(record);
                    }
                }
            }
        }
        return matched;
    }

    private boolean projectorCaughtUp() {
        try (AdminClient admin = AdminClient.create(Map.of(
                AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers()))) {
            Map<TopicPartition, OffsetAndMetadata> committed = admin
                    .listConsumerGroupOffsets("transaction-history-projector")
                    .partitionsToOffsetAndMetadata()
                    .get(5, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = new HashMap<>();
            committed.forEach((partition, metadata) -> {
                if (TOPIC.equals(partition.topic()) && metadata != null) {
                    latest.put(partition, OffsetSpec.latest());
                }
            });
            if (latest.isEmpty()) {
                return false;
            }
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    admin.listOffsets(latest).all().get(5, TimeUnit.SECONDS);
            return ends.entrySet().stream().allMatch(entry ->
                    committed.get(entry.getKey()).offset() >= entry.getValue().offset());
        } catch (Exception exception) {
            return false;
        }
    }

    private static String header(ConsumerRecord<String, byte[]> record, String name) {
        Header header = record.headers().lastHeader(name);
        assertThat(header).isNotNull();
        return new String(header.value(), StandardCharsets.UTF_8);
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

    private static String payment(UUID debtor, UUID creditor, long amount) {
        String endToEndId = "E2E" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return """
                {
                  "endToEndId": "%s",
                  "rail": "BOOK",
                  "amount": { "minor": %d, "currency": "USD" },
                  "debtorAccountId": "%s",
                  "creditor": { "accountId": "%s" },
                  "requestedValueDate": "%s"
                }
                """.formatted(endToEndId, amount, debtor, creditor, LocalDate.now(ZoneId.of("America/New_York")));
    }
}
