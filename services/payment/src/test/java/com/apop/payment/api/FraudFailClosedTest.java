package com.apop.payment.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class FraudFailClosedTest {

    private static final UUID NOSTRO = UUID.fromString("00000000-0000-7000-8000-000000000002");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaymentSandbox::paymentJdbcUrl);
        registry.add("spring.datasource.username", PaymentSandbox::paymentUsername);
        registry.add("spring.datasource.password", PaymentSandbox::paymentPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/payment");
        registry.add("apop.ledger.base-url", PaymentSandbox::ledgerBaseUrl);
        registry.add("apop.fraud.mode", () -> "fail-closed");
        registry.add("apop.messaging.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void fraudFailureDoesNotPlaceAHold() throws Exception {
        RestClient ledger = RestClient.builder().baseUrl(PaymentSandbox.ledgerBaseUrl()).build();
        UUID debtor = openCustomer(ledger, "2000001111");
        UUID creditor = openCustomer(ledger, "2000002222");
        ledger.post()
                .uri("/internal/v1/postings")
                .contentType(MediaType.APPLICATION_JSON)
                .body("""
                        {
                          "idempotencyKey": "fund-%s",
                          "entryType": "POSTING",
                          "valueDate": "%s",
                          "lines": [
                            {"accountId": "%s", "direction": "DEBIT", "amountMinor": 80000, "currency": "USD"},
                            {"accountId": "%s", "direction": "CREDIT", "amountMinor": 80000, "currency": "USD"}
                          ]
                        }
                        """.formatted(UUID.randomUUID(), LocalDate.now(ZoneId.of("America/New_York")), NOSTRO, debtor))
                .retrieve()
                .toBodilessEntity();

        MvcResult result = mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "fraud-" + UUID.randomUUID())
                        .content("""
                                {
                                  "endToEndId": "%s",
                                  "rail": "BOOK",
                                  "amount": { "minor": 1000, "currency": "USD" },
                                  "debtorAccountId": "%s",
                                  "creditor": { "accountId": "%s" },
                                  "requestedValueDate": "%s"
                                }
                                """.formatted(UUID.randomUUID().toString().substring(0, 12), debtor, creditor, LocalDate.now(ZoneId.of("America/New_York")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("FAILED"))
                .andExpect(jsonPath("$.reasonCode").value("FRAUD_UNAVAILABLE"))
                .andReturn();
        String paymentId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.paymentId");
        String ledgerView = ledger.get().uri("/internal/v1/payments/{id}/ledger", paymentId).retrieve().body(String.class);
        org.hamcrest.MatcherAssert.assertThat(
                com.jayway.jsonpath.JsonPath.<Integer>read(ledgerView, "$.holds.length()"),
                org.hamcrest.Matchers.equalTo(0));
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
}
