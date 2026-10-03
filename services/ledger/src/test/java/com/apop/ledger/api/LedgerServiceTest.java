package com.apop.ledger.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@Testcontainers
class LedgerServiceTest {

    private static final UUID NOSTRO = UUID.fromString("00000000-0000-7000-8000-000000000002");
    private static final UUID CLEARING = UUID.fromString("00000000-0000-7000-8000-000000000001");

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private MockMvc mvc;

    @Test
    void holdAndPostAreIdempotentAndTheJournalStaysBalanced() throws Exception {
        UUID accountId = openCustomer("000123456789");
        UUID paymentId = UUID.randomUUID();
        fund(accountId, 200_000L, "fund-" + paymentId);

        mvc.perform(get("/internal/v1/accounts/{id}", accountId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ledgerBalanceMinor").value(200_000))
                .andExpect(jsonPath("$.heldMinor").value(0))
                .andExpect(jsonPath("$.availableMinor").value(200_000));

        String holdKey = "hold-" + paymentId;
        MvcResult placed = mvc.perform(post("/internal/v1/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdJson(holdKey, paymentId, accountId, 125_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.status").value("OPEN"))
                .andExpect(jsonPath("$.ledgerBalanceMinor").value(200_000))
                .andExpect(jsonPath("$.availableMinor").value(75_000))
                .andReturn();
        String holdId = com.jayway.jsonpath.JsonPath.read(placed.getResponse().getContentAsString(), "$.holdId");

        mvc.perform(post("/internal/v1/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdJson(holdKey, paymentId, accountId, 125_000L)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holdId").value(holdId))
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.heldMinor").value(125_000));

        mvc.perform(post("/internal/v1/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdJson(holdKey, paymentId, accountId, 1L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.reasonCode").value("IDEMPOTENCY_CONFLICT"));

        mvc.perform(post("/internal/v1/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdJson("too-big-" + paymentId, UUID.randomUUID(), accountId, 80_000L)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode").value("AUTH_INSUFFICIENT_FUNDS"));

        mvc.perform(get("/internal/v1/accounts/{id}", accountId))
                .andExpect(jsonPath("$.heldMinor").value(125_000))
                .andExpect(jsonPath("$.ledgerBalanceMinor").value(200_000));

        String postKey = "post-" + paymentId;
        String posting = postingJson(postKey, paymentId, holdId, accountId, 125_000L);
        MvcResult posted = mvc.perform(post("/internal/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(posting))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.lines", hasSize(2)))
                .andReturn();
        String entryId = com.jayway.jsonpath.JsonPath.read(posted.getResponse().getContentAsString(), "$.journalEntryId");

        mvc.perform(post("/internal/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(posting))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journalEntryId").value(entryId))
                .andExpect(jsonPath("$.replayed").value(true));

        mvc.perform(get("/internal/v1/accounts/{id}", accountId))
                .andExpect(jsonPath("$.ledgerBalanceMinor").value(75_000))
                .andExpect(jsonPath("$.heldMinor").value(0))
                .andExpect(jsonPath("$.availableMinor").value(75_000));

        mvc.perform(get("/internal/v1/payments/{id}/ledger", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.holds", hasSize(1)))
                .andExpect(jsonPath("$.holds[0].status").value("CONSUMED"))
                .andExpect(jsonPath("$.postings", hasSize(1)))
                .andExpect(jsonPath("$.postings[0].journalEntryId").value(entryId));

        mvc.perform(post("/internal/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "unbalanced-%s",
                                  "paymentId": "%s",
                                  "entryType": "POSTING",
                                  "valueDate": "%s",
                                  "lines": [
                                    {"accountId": "%s", "direction": "DEBIT", "amountMinor": 10, "currency": "USD"},
                                    {"accountId": "%s", "direction": "CREDIT", "amountMinor": 11, "currency": "USD"}
                                  ]
                                }
                                """.formatted(UUID.randomUUID(), UUID.randomUUID(), LocalDate.of(2026, 10, 2), accountId, CLEARING)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.reasonCode", equalTo("LEDGER_UNBALANCED")));

        String scrape = mvc.perform(get("/actuator/prometheus"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        org.hamcrest.MatcherAssert.assertThat(scrape, org.hamcrest.Matchers.containsString("apop_ledger_post_total"));
        org.hamcrest.MatcherAssert.assertThat(scrape, org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("account_id=")));
        org.hamcrest.MatcherAssert.assertThat(scrape, org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("payment_id=")));
        org.hamcrest.MatcherAssert.assertThat(scrape, org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString(paymentId.toString())));
    }

    @Test
    void releasingAHoldRestoresAvailableBalanceWithoutChangingTheLedger() throws Exception {
        UUID accountId = openCustomer("000999888777");
        UUID paymentId = UUID.randomUUID();
        fund(accountId, 50_000L, "fund-release-" + paymentId);
        String holdKey = "hold-release-" + paymentId;

        mvc.perform(post("/internal/v1/holds")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(holdJson(holdKey, paymentId, accountId, 20_000L)))
                .andExpect(status().isOk());

        mvc.perform(post("/internal/v1/hold-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"" + holdKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("RELEASED"))
                .andExpect(jsonPath("$.replayed").value(false))
                .andExpect(jsonPath("$.ledgerBalanceMinor").value(50_000))
                .andExpect(jsonPath("$.availableMinor").value(50_000));

        mvc.perform(post("/internal/v1/hold-releases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idempotencyKey\":\"" + holdKey + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.status").value("RELEASED"));
    }

    private UUID openCustomer(String accountNumber) throws Exception {
        MvcResult result = mvc.perform(post("/internal/v1/accounts")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Correlation-Id", UUID.randomUUID().toString())
                        .content("""
                                {
                                  "legalEntityId": "SIM-BANK",
                                  "accountRole": "CUSTOMER",
                                  "accountNumber": "%s",
                                  "currency": "USD",
                                  "normalBalance": "CREDIT"
                                }
                                """.formatted(accountNumber)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accountId", notNullValue()))
                .andExpect(jsonPath("$.accountNumberLast4").value(accountNumber.substring(accountNumber.length() - 4)))
                .andReturn();
        String id = com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.accountId");
        return UUID.fromString(id);
    }

    private void fund(UUID accountId, long amount, String key) throws Exception {
        mvc.perform(post("/internal/v1/postings")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "idempotencyKey": "%s",
                                  "entryType": "POSTING",
                                  "valueDate": "2026-10-02",
                                  "lines": [
                                    {"accountId": "%s", "direction": "DEBIT", "amountMinor": %d, "currency": "USD"},
                                    {"accountId": "%s", "direction": "CREDIT", "amountMinor": %d, "currency": "USD"}
                                  ]
                                }
                                """.formatted(key, NOSTRO, amount, accountId, amount)))
                .andExpect(status().isOk());
    }

    private static String holdJson(String key, UUID paymentId, UUID accountId, long amount) {
        return """
                {
                  "idempotencyKey": "%s",
                  "paymentId": "%s",
                  "accountId": "%s",
                  "amountMinor": %d,
                  "currency": "USD",
                  "expiresAt": "%s"
                }
                """.formatted(key, paymentId, accountId, amount, Instant.now().plusSeconds(86_400));
    }

    private static String postingJson(String key, UUID paymentId, String holdId, UUID accountId, long amount) {
        return """
                {
                  "idempotencyKey": "%s",
                  "paymentId": "%s",
                  "entryType": "POSTING",
                  "valueDate": "2026-10-02",
                  "holdId": "%s",
                  "lines": [
                    {"accountId": "%s", "direction": "DEBIT", "amountMinor": %d, "currency": "USD"},
                    {"accountId": "%s", "direction": "CREDIT", "amountMinor": %d, "currency": "USD"}
                  ]
                }
                """.formatted(key, paymentId, holdId, accountId, amount, CLEARING, amount);
    }
}
