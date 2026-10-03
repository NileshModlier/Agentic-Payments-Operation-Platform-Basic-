package com.apop.payment.api;

import com.apop.payment.messaging.LifecycleProjection;
import com.apop.payment.messaging.LifecycleProjectionRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DirtiesContext
class PaymentFailedLifecycleTest {

    private static final UUID NOSTRO = UUID.fromString("00000000-0000-7000-8000-000000000002");

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
        registry.add("apop.fraud.mode", () -> "fail-closed");
        registry.add("apop.messaging.enabled", () -> "true");
        registry.add("apop.messaging.relay-delay-ms", () -> "200");
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.consumer.group-id", () -> "transaction-history-projector-failed");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private LifecycleProjectionRepository projections;

    @Test
    void technicalFailurePublishesPaymentFailedAfterValidation() throws Exception {
        RestClient ledger = RestClient.builder().baseUrl(PaymentSandbox.ledgerBaseUrl()).build();
        UUID debtor = openCustomer(ledger, "5000001111");
        UUID creditor = openCustomer(ledger, "5000002222");
        fund(ledger, debtor, 200_000L);

        MvcResult created = mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "fail-" + UUID.randomUUID())
                        .header("X-Correlation-Id", UUID.randomUUID().toString())
                        .content(payment(debtor, creditor, 80_000L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reasonCode").value("FRAUD_UNAVAILABLE"))
                .andReturn();
        String paymentId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.paymentId");

        await().atMost(Duration.ofSeconds(30)).until(() ->
                projections.findByPaymentIdOrderByOccurredAtAscEventIdAsc(UUID.fromString(paymentId)).size() == 3);

        assertThat(projections.findByPaymentIdOrderByOccurredAtAscEventIdAsc(UUID.fromString(paymentId)))
                .extracting(LifecycleProjection::getEventName)
                .containsExactly("PaymentReceived", "PaymentValidated", "PaymentFailed");
        assertThat(projections.findByPaymentIdOrderByOccurredAtAscEventIdAsc(UUID.fromString(paymentId))
                .get(2).getStatus()).isEqualTo("FAILED");
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
