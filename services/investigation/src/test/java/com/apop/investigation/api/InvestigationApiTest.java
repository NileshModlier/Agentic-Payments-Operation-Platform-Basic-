package com.apop.investigation.api;

import com.apop.investigation.agent.AgentControls;
import com.apop.investigation.agent.DeterministicRecommendationModel;
import com.apop.investigation.agent.ModelScript;
import com.apop.investigation.agent.RecommendationModel;
import com.apop.investigation.persistence.AgentToolCallRepository;
import com.apop.investigation.persistence.AuditEventRepository;
import com.apop.investigation.persistence.OutboxRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Testcontainers
class InvestigationApiTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("apop.messaging.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude", () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        registry.add("spring.flyway.locations", () -> "classpath:db/investigation");
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ModelScript model;
    @Autowired
    private DeterministicRecommendationModel deterministic;
    @Autowired
    private AgentControls controls;
    @Autowired
    private AgentToolCallRepository toolCalls;
    @Autowired
    private AuditEventRepository audits;
    @Autowired
    private OutboxRepository outbox;

    @AfterEach
    void reset() {
        model.reset();
        controls.setEnabled(true);
        controls.setModelAvailable(true);
    }

    @Test
    void fraudAssistRecommendsAndDoesNotMoveMoney() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID assessmentId = UUID.randomUUID();
        String caseId = open("FRAUD_REVIEW", paymentId, "teller", 125_000, fraudEvidence(paymentId, assessmentId, 800, "LIST_CREDITOR_DENY"));
        mvc.perform(post("/v1/cases/{id}/assist", caseId).header("X-Actor-Id", "analyst-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_HUMAN"))
                .andExpect(jsonPath("$.agentRole").value("FRAUD"))
                .andExpect(jsonPath("$.moneyMovement").value("NONE"))
                .andExpect(jsonPath("$.recommendation.disposition").value("CONFIRM_DECLINE"));

        mvc.perform(post("/v1/cases/{id}/decisions", caseId)
                        .header("X-Actor-Id", "teller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM_DECLINE\",\"rationale\":\"the beneficiary is on the deny list\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/cases/{id}/decisions", caseId)
                        .header("X-Actor-Id", "analyst-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM_DECLINE\",\"rationale\":\"the beneficiary is on the deny list\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.moneyMovement").value("NONE"))
                .andExpect(jsonPath("$.decision.decidedBy").value("analyst-2"));

        String events = outbox.findByAggregateIdOrderByCreatedAtAsc(UUID.fromString(caseId)).stream()
                .map(row -> new String(row.getPayload()))
                .reduce("", String::concat);
        org.hamcrest.MatcherAssert.assertThat(events, containsString("NONE"));
        org.hamcrest.MatcherAssert.assertThat(events, not(containsString("place_hold")));
        org.hamcrest.MatcherAssert.assertThat(toolNames(caseId), not(containsString("place_hold")));
        org.hamcrest.MatcherAssert.assertThat(toolNames(caseId), not(containsString("release_fraud_review")));
    }

    @Test
    void aQuietReviewCanBeRecommendedForReleaseWithoutReleasingIt() throws Exception {
        UUID paymentId = UUID.randomUUID();
        String caseId = open("FRAUD_REVIEW", paymentId, "teller", 10_000, fraudEvidence(paymentId, UUID.randomUUID(), 210, "NEW_CREDITOR"));
        mvc.perform(post("/v1/cases/{id}/assist", caseId).header("X-Actor-Id", "analyst-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.disposition").value("RELEASE_TO_AUTHORIZE"))
                .andExpect(jsonPath("$.moneyMovement").value("NONE"))
                .andExpect(jsonPath("$.status").value("AWAITING_HUMAN"));
    }

    @Test
    void aBreakProposalNeedsASecondPersonAndDoesNotPost() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID journalId = UUID.randomUUID();
        String caseId = open("RECON_BREAK", paymentId, "teller", 2_000_000, breakEvidence(paymentId, journalId));
        mvc.perform(post("/v1/cases/{id}/assist", caseId).header("X-Actor-Id", "ops-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentRole").value("SETTLEMENT"))
                .andExpect(jsonPath("$.recommendation.disposition").value("PROPOSE_REVERSAL"))
                .andExpect(jsonPath("$.moneyMovement").value("NONE"));
        mvc.perform(post("/v1/cases/{id}/decisions", caseId)
                        .header("X-Actor-Id", "ops-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"PROPOSE_REVERSAL\",\"rationale\":\"the statement amount does not match the journal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_HUMAN"));
        mvc.perform(post("/v1/cases/{id}/approvals", caseId)
                        .header("X-Actor-Id", "ops-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"PROPOSE_REVERSAL\",\"rationale\":\"I cannot approve my own reversal\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/v1/cases/{id}/approvals", caseId)
                        .header("X-Actor-Id", "ops-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"PROPOSE_REVERSAL\",\"rationale\":\"the journal id matches the break\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.moneyMovement").value("NONE"))
                .andExpect(jsonPath("$.counterApproval.decidedBy").value("ops-2"));
    }

    @Test
    void rootCauseRetriesAndCannotProposeAReversal() throws Exception {
        UUID paymentId = UUID.randomUUID();
        String caseId = open("ESCALATED_FAILURE", paymentId, "teller", 10_000, failureEvidence(paymentId));
        mvc.perform(post("/v1/cases/{id}/assist", caseId).header("X-Actor-Id", "ops-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agentRole").value("ROOT_CAUSE"))
                .andExpect(jsonPath("$.recommendation.disposition").value("RETRY_TECHNICAL"));

        String blocked = open("ESCALATED_FAILURE", UUID.randomUUID(), "teller", 10_000, failureEvidence(UUID.randomUUID()));
        model.use(request -> RecommendationModel.ModelTurn.draft(new com.apop.investigation.domain.PolicyGuard.Draft(
                "PROPOSE_REVERSAL", new BigDecimal("0.900"), List.of(), "Reverse the payment from the failure agent.")));
        mvc.perform(post("/v1/cases/{id}/assist", blocked).header("X-Actor-Id", "ops-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("FAILED"))
                .andExpect(jsonPath("$.guardCheck").value("DISPOSITION"))
                .andExpect(jsonPath("$.recommendation").value(nullValue()));
    }

    @Test
    void aForeignEvidenceIdIsNotShownAsAdvice() throws Exception {
        String caseId = open("FRAUD_REVIEW", UUID.randomUUID(), "teller", 10_000, fraudEvidence(UUID.randomUUID(), UUID.randomUUID(), 800, "LIST_CREDITOR_DENY"));
        model.use(request -> RecommendationModel.ModelTurn.draft(new com.apop.investigation.domain.PolicyGuard.Draft(
                "CONFIRM_DECLINE",
                new BigDecimal("0.990"),
                List.of("payment:" + UUID.randomUUID()),
                "FOREIGN-PAYMENT-ADVICE")));
        MvcResult result = mvc.perform(post("/v1/cases/{id}/assist", caseId).header("X-Actor-Id", "analyst-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionStatus").value("FAILED"))
                .andExpect(jsonPath("$.guardCheck").value("EVIDENCE"))
                .andExpect(jsonPath("$.recommendation").value(nullValue()))
                .andReturn();
        org.hamcrest.MatcherAssert.assertThat(result.getResponse().getContentAsString(), not(containsString("FOREIGN-PAYMENT-ADVICE")));
        org.hamcrest.MatcherAssert.assertThat(
                audits.findByResourceIdOrderByCreatedAtAsc(caseId).toString(),
                not(containsString("FOREIGN-PAYMENT-ADVICE")));
    }

    @Test
    void aToolCallForAnotherPaymentIsRefused() throws Exception {
        UUID paymentId = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        String caseId = open("FRAUD_REVIEW", paymentId, "teller", 10_000, fraudEvidence(paymentId, UUID.randomUUID(), 800, "LIST_CREDITOR_DENY"));
        model.use(request -> {
            if (request.toolLog().stream().noneMatch(line -> line.startsWith("get_payment|"))) {
                return RecommendationModel.ModelTurn.tool("get_payment", Map.of("paymentId", other.toString()));
            }
            return deterministic.next(request);
        });
        mvc.perform(post("/v1/cases/{id}/assist", caseId).header("X-Actor-Id", "analyst-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.recommendation.disposition").value("CONFIRM_DECLINE"));
        org.hamcrest.MatcherAssert.assertThat(toolNames(caseId), containsString("PAYMENT_SCOPE"));
        org.hamcrest.MatcherAssert.assertThat(toolNames(caseId), not(containsString(other.toString())));
    }

    @Test
    void theKillSwitchRefusesAssistAndStillAllowsADecision() throws Exception {
        String caseId = open("FRAUD_REVIEW", UUID.randomUUID(), "teller", 10_000, fraudEvidence(UUID.randomUUID(), UUID.randomUUID(), 800, "LIST_CREDITOR_DENY"));
        controls.setEnabled(false);
        mvc.perform(post("/v1/cases/{id}/assist", caseId).header("X-Actor-Id", "analyst-2"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.reasonCode").value("AGENT_PAUSED"));
        mvc.perform(get("/v1/cases/{id}", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
        mvc.perform(post("/v1/cases/{id}/decisions", caseId)
                        .header("X-Actor-Id", "analyst-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM_DECLINE\",\"rationale\":\"assistance is paused so I decline it\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.moneyMovement").value("NONE"));
    }

    @Test
    void aDownModelLeavesTheQueueUsable() throws Exception {
        String caseId = open("FRAUD_REVIEW", UUID.randomUUID(), "teller", 10_000, fraudEvidence(UUID.randomUUID(), UUID.randomUUID(), 210, "NEW_CREDITOR"));
        controls.setModelAvailable(false);
        mvc.perform(post("/v1/cases/{id}/assist", caseId).header("X-Actor-Id", "analyst-2"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.reasonCode").value("TECHNICAL_DEPENDENCY"));
        mvc.perform(get("/v1/cases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[*].caseId", hasItem(caseId)));
        mvc.perform(get("/v1/cases/{id}", caseId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("OPEN"));
    }

    @Test
    void reportingDraftsAfterAHumanDecisionAndPublishesNothingFinancial() throws Exception {
        UUID paymentId = UUID.randomUUID();
        String caseId = open("FRAUD_REVIEW", paymentId, "teller", 10_000, fraudEvidence(paymentId, UUID.randomUUID(), 800, "LIST_CREDITOR_DENY"));
        mvc.perform(post("/v1/cases/{id}/assist", caseId).header("X-Actor-Id", "analyst-2"))
                .andExpect(status().isOk());
        mvc.perform(post("/v1/cases/{id}/decisions", caseId)
                        .header("X-Actor-Id", "analyst-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM_DECLINE\",\"rationale\":\"the deny list agrees with the score\"}"))
                .andExpect(status().isOk());
        MvcResult drafted = mvc.perform(post("/v1/cases/{id}/reports", caseId).header("X-Actor-Id", "analyst-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.agentRole").value("REPORTING"))
                .andExpect(jsonPath("$.report.draft", containsString("Scope")))
                .andExpect(jsonPath("$.report.draft", containsString("Money movement")))
                .andExpect(jsonPath("$.moneyMovement").value("NONE"))
                .andReturn();
        String reportId = com.jayway.jsonpath.JsonPath.read(drafted.getResponse().getContentAsString(), "$.report.reportId");
        org.hamcrest.MatcherAssert.assertThat(drafted.getResponse().getContentAsString(), not(containsString("featureSnapshot")));
        mvc.perform(post("/v1/cases/{id}/reports/{reportId}/publications", caseId, reportId).header("X-Actor-Id", "analyst-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.report.publishedBy").value("analyst-2"))
                .andExpect(jsonPath("$.moneyMovement").value("NONE"));
    }

    private String toolNames(String caseId) {
        return toolCalls.findAll().stream()
                .filter(call -> audits.findByResourceIdOrderByCreatedAtAsc(caseId).stream().anyMatch(audit -> audit.getDetail().contains(call.getSessionId().toString())))
                .map(call -> call.getToolName() + ":" + call.getResponseCode())
                .reduce("", (left, right) -> left + " " + right);
    }

    private String open(String source, UUID paymentId, String submittedBy, long amount, String evidence) throws Exception {
        MvcResult result = mvc.perform(post("/internal/v1/investigation-cases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"paymentId":"%s","source":"%s","sourceRef":"%s","submittedBy":"%s","severity":"HIGH","amountMinor":%d,"currency":"USD","evidence":%s}
                                """.formatted(paymentId, source, UUID.randomUUID(), submittedBy, amount, evidence)))
                .andExpect(status().isOk())
                .andReturn();
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.caseId");
    }

    private static String fraudEvidence(UUID paymentId, UUID assessmentId, int score, String ruleId) {
        return """
                {"payment":{"paymentId":"%s","status":"FRAUD_REVIEW","rail":"BOOK","amountMinor":125000,"currency":"USD","debtorAccountNumber":"123456789012","holdId":null},"timeline":[{"summaryCode":"FRAUD_REVIEW"}],"fraudAssessment":{"assessmentId":"%s","decision":"REVIEW","score":%d,"ruleIds":["%s"]},"similar":{"counts":{"FRAUD_REVIEW":1}}}
                """.formatted(paymentId, assessmentId, score, ruleId);
    }

    private static String breakEvidence(UUID paymentId, UUID journalId) {
        return """
                {"payment":{"paymentId":"%s","status":"SETTLED","debtorAccountNumber":"123456789012"},"break":{"breakId":"%s","breakClass":"AMOUNT_MISMATCH","journalEntryId":"%s"},"ledger":{"journalEntryId":"%s","holds":[]},"settlement":{"status":"POSTED"}}
                """.formatted(paymentId, UUID.randomUUID(), journalId, journalId);
    }

    private static String failureEvidence(UUID paymentId) {
        return """
                {"payment":{"paymentId":"%s","status":"FAILED","debtorAccountNumber":"123456789012"},"failure":{"failureCaseId":"%s","detailCode":"POST_UNCERTAIN","attemptCount":2},"timeline":[{"summaryCode":"FAILED"}]}
                """.formatted(paymentId, UUID.randomUUID());
    }
}
