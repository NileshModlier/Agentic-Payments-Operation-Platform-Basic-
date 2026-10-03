package com.apop.payment.api;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
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

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@ExtendWith(OutputCaptureExtension.class)
class PaymentServiceTest {

    private static final UUID NOSTRO = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID DENIED = UUID.fromString("00000000-0000-7000-8000-000000000099");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", PaymentSandbox::paymentJdbcUrl);
        registry.add("spring.datasource.username", PaymentSandbox::paymentUsername);
        registry.add("spring.datasource.password", PaymentSandbox::paymentPassword);
        registry.add("spring.flyway.locations", () -> "classpath:db/payment");
        registry.add("apop.ledger.base-url", PaymentSandbox::ledgerBaseUrl);
        registry.add("apop.fraud.mode", () -> "local");
        registry.add("apop.messaging.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude", () ->
                "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
    }

    @Autowired
    private MockMvc mvc;

    @Test
    void bookPaymentReachesAuthorizedAndReplayDoesNotPlaceASecondHold() throws Exception {
        RestClient ledger = ledger();
        UUID debtor = openCustomer(ledger, "1000001111");
        UUID creditor = openCustomer(ledger, "1000002222");
        fund(ledger, debtor, 200_000L);
        String key = "pay-" + UUID.randomUUID();
        String body = payment(key, debtor, creditor, 125_000L, "USD", "Invoice 4401", null);

        MvcResult created = mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .header("X-Correlation-Id", UUID.randomUUID().toString())
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))
                .andExpect(jsonPath("$.holdId", notNullValue()))
                .andExpect(jsonPath("$.amount.minor").value(125_000))
                .andReturn();
        String paymentId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.paymentId");
        String holdId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.holdId");

        mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.paymentId").value(paymentId))
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"));

        mvc.perform(get("/v1/payments/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.journalEntryId").value(org.hamcrest.Matchers.nullValue()));

        String account = ledger.get().uri("/internal/v1/accounts/{id}", debtor).retrieve().body(String.class);
        org.hamcrest.MatcherAssert.assertThat(
                com.jayway.jsonpath.JsonPath.<Integer>read(account, "$.ledgerBalanceMinor"),
                org.hamcrest.Matchers.equalTo(200_000));
        org.hamcrest.MatcherAssert.assertThat(
                com.jayway.jsonpath.JsonPath.<Integer>read(account, "$.heldMinor"),
                org.hamcrest.Matchers.equalTo(125_000));
        org.hamcrest.MatcherAssert.assertThat(
                com.jayway.jsonpath.JsonPath.<Integer>read(account, "$.availableMinor"),
                org.hamcrest.Matchers.equalTo(75_000));
    }

    @Test
    void prometheusScrapeOmitsPaymentIdentifiers(CapturedOutput output) throws Exception {
        RestClient ledger = ledger();
        UUID debtor = openCustomer(ledger, "1000007711");
        UUID creditor = openCustomer(ledger, "1000007722");
        fund(ledger, debtor, 200_000L);
        String correlationId = UUID.randomUUID().toString();
        String key = "obs-" + UUID.randomUUID();
        String body = payment(key, debtor, creditor, 125_000L, "USD", null, null);

        MvcResult created = mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .header("X-Correlation-Id", correlationId)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().string("X-Correlation-Id", correlationId))
                .andExpect(header().string("traceparent", org.hamcrest.Matchers.matchesPattern("00-[0-9a-f]{32}-[0-9a-f]{16}-0[01]")))
                .andReturn();
        String paymentId = com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.paymentId");

        mvc.perform(get("/live")).andExpect(status().isOk());
        mvc.perform(get("/ready")).andExpect(status().isOk());

        String scrape = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.hamcrest.MatcherAssert.assertThat(scrape, org.hamcrest.Matchers.containsString("apop_payment_intake_total"));
        org.hamcrest.MatcherAssert.assertThat(scrape, org.hamcrest.Matchers.containsString("apop_payment_decision_total"));
        org.hamcrest.MatcherAssert.assertThat(scrape, org.hamcrest.Matchers.containsString("apop_outbox_unpublished_age_seconds"));
        org.hamcrest.MatcherAssert.assertThat(scrape, org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("payment_id=")));
        org.hamcrest.MatcherAssert.assertThat(scrape, org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("paymentId=")));
        org.hamcrest.MatcherAssert.assertThat(scrape, org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(paymentId)));
        org.hamcrest.MatcherAssert.assertThat(output.getOut(), org.hamcrest.Matchers.containsString("\"correlationId\":\"" + correlationId + "\""));
        org.hamcrest.MatcherAssert.assertThat(output.getOut(), org.hamcrest.Matchers.containsString("\"paymentId\":\"" + paymentId + "\""));
        org.hamcrest.MatcherAssert.assertThat(output.getOut(), org.hamcrest.Matchers.containsString("\"traceId\""));
    }

    @Test
    void sameKeyWithADifferentBodyConflicts() throws Exception {
        RestClient ledger = ledger();
        UUID debtor = openCustomer(ledger, "1000003333");
        UUID creditor = openCustomer(ledger, "1000004444");
        fund(ledger, debtor, 200_000L);
        String key = "conflict-" + UUID.randomUUID();
        mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(payment(key, debtor, creditor, 10_000L, "USD", null, null)))
                .andExpect(status().isCreated());

        mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", key)
                        .content(payment(key, debtor, creditor, 10_001L, "USD", null, null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("IDEMPOTENCY_CONFLICT"));
    }

    @Test
    void missingIdempotencyKeyIsRejected() throws Exception {
        mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payment("ignored", UUID.randomUUID(), UUID.randomUUID(), 1L, "USD", null, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.reasonCode").value("IDEMPOTENCY_KEY_REQUIRED"));
    }

    @Test
    void insufficientFundsDeclinesWithoutAHold() throws Exception {
        RestClient ledger = ledger();
        UUID debtor = openCustomer(ledger, "1000005555");
        UUID creditor = openCustomer(ledger, "1000006666");
        fund(ledger, debtor, 1_000L);
        MvcResult result = mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "nsf-" + UUID.randomUUID())
                        .content(payment("nsf", debtor, creditor, 50_000L, "USD", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("AUTHORIZATION_DECLINED"))
                .andExpect(jsonPath("$.reasonCode").value("AUTH_INSUFFICIENT_FUNDS"))
                .andReturn();
        String paymentId = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.paymentId");
        String ledgerView = ledger.get().uri("/internal/v1/payments/{id}/ledger", paymentId).retrieve().body(String.class);
        org.hamcrest.MatcherAssert.assertThat(
                com.jayway.jsonpath.JsonPath.<Integer>read(ledgerView, "$.holds.length()"),
                org.hamcrest.Matchers.equalTo(0));
    }

    @Test
    void screeningHitAndLimitBreachStopBeforeTheLedger() throws Exception {
        RestClient ledger = ledger();
        UUID debtor = openCustomer(ledger, "1000007777");
        UUID creditor = openCustomer(ledger, "1000008888");
        fund(ledger, debtor, 50_000L);

        MvcResult screened = mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "screen-" + UUID.randomUUID())
                        .content(payment("screen", debtor, DENIED, 1_000L, "USD", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.reasonCode").value("VALIDATION_SANCTIONS_REJECT"))
                .andReturn();
        assertNoHold(ledger, com.jayway.jsonpath.JsonPath.read(screened.getResponse().getContentAsString(), "$.paymentId"));

        MvcResult limited = mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "limit-" + UUID.randomUUID())
                        .content(payment("limit", debtor, creditor, 1_000_000_001L, "USD", null, null)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.reasonCode").value("VALIDATION_LIMIT_EXCEEDED"))
                .andReturn();
        assertNoHold(ledger, com.jayway.jsonpath.JsonPath.read(limited.getResponse().getContentAsString(), "$.paymentId"));

        mvc.perform(post("/v1/payments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("Idempotency-Key", "name-" + UUID.randomUUID())
                        .content("""
                                {
                                  "endToEndId": "%s",
                                  "rail": "SIMULATED_ACH",
                                  "amount": { "minor": 1000, "currency": "USD" },
                                  "debtorAccountId": "%s",
                                  "creditor": { "name": "Blocked Beneficiary", "accountNumber": "9999", "routingNumber": "021000021", "country": "US" },
                                  "requestedValueDate": "%s"
                                }
                                """.formatted(UUID.randomUUID().toString().substring(0, 20), debtor, LocalDate.now(ZoneId.of("America/New_York")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.reasonCode").value("VALIDATION_SANCTIONS_REJECT"));

        String account = ledger.get().uri("/internal/v1/accounts/{id}", debtor).retrieve().body(String.class);
        org.hamcrest.MatcherAssert.assertThat(
                com.jayway.jsonpath.JsonPath.<Integer>read(account, "$.heldMinor"),
                org.hamcrest.Matchers.equalTo(0));
    }

    private static void assertNoHold(RestClient ledger, String paymentId) {
        String ledgerView = ledger.get().uri("/internal/v1/payments/{id}/ledger", paymentId).retrieve().body(String.class);
        org.hamcrest.MatcherAssert.assertThat(
                com.jayway.jsonpath.JsonPath.<Integer>read(ledgerView, "$.holds.length()"),
                org.hamcrest.Matchers.equalTo(0));
    }

    private static RestClient ledger() {
        return RestClient.builder().baseUrl(PaymentSandbox.ledgerBaseUrl()).build();
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

    private static String payment(
            String endToEndSuffix,
            UUID debtor,
            UUID creditor,
            long amount,
            String currency,
            String remittance,
            String ignored) {
        String remittanceJson = remittance == null ? "" : ",\n  \"remittance\": \"" + remittance + "\"";
        String endToEndId = "E2E" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        return """
                {
                  "endToEndId": "%s",
                  "rail": "BOOK",
                  "amount": { "minor": %d, "currency": "%s" },
                  "debtorAccountId": "%s",
                  "creditor": { "accountId": "%s" },
                  "requestedValueDate": "%s"%s
                }
                """.formatted(
                endToEndId,
                amount,
                currency,
                debtor,
                creditor,
                LocalDate.now(ZoneId.of("America/New_York")),
                remittanceJson);
    }
}
