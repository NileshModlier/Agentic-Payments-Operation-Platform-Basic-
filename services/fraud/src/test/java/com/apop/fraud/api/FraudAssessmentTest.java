package com.apop.fraud.api;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@AutoConfigureObservability
@Testcontainers
class FraudAssessmentTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Container
    static GenericContainer<?> redis = new GenericContainer<>(DockerImageName.parse("redis:7-alpine")).withExposedPorts(6379);

    @DynamicPropertySource
    static void redis(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("apop.messaging.enabled", () -> "false");
        registry.add("spring.autoconfigure.exclude", () -> "org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration");
        registry.add("spring.flyway.locations", () -> "classpath:db/fraud");
    }

    @Autowired
    private MockMvc mvc;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private com.apop.fraud.messaging.OutboxRepository outbox;

    @Test
    void aBookPaymentApprovesAndTheSameCreditorThenScoresZero() throws Exception {
        UUID debtor = UUID.randomUUID();
        UUID creditor = UUID.randomUUID();
        MvcResult first = assess(debtor, creditor, 125_000, "API", "US", null, "pay-1-" + debtor);
        String paymentId = id(first, "$.paymentId");
        mvc.perform(post("/internal/v1/fraud-assessments").contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.fromString(paymentId), debtor, creditor, 125_000, "API", "US", null, "pay-1-" + debtor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.replayed").value(true))
                .andExpect(jsonPath("$.assessmentId").value(id(first, "$.assessmentId")));

        MvcResult second = assess(debtor, creditor, 1_000, "API", "US", null, "pay-2-" + debtor);
        mvc.perform(get("/internal/v1/fraud-assessments/{id}", paymentId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.decision").value("APPROVE"))
                .andExpect(jsonPath("$.score").value(150))
                .andExpect(jsonPath("$.ruleIds[0].ruleId").value("NEW_CREDITOR"));
        org.hamcrest.MatcherAssert.assertThat(second.getResponse().getContentAsString(), containsString("\"score\":0"));
        org.hamcrest.MatcherAssert.assertThat(second.getResponse().getContentAsString(), containsString("APPROVE"));
    }

    @Test
    void denyListDeclinesOnlyAfterASecondPersonApprovesIt() throws Exception {
        UUID debtor = UUID.randomUUID();
        UUID creditor = UUID.randomUUID();
        MvcResult proposed = mvc.perform(post("/internal/v1/restricted-lists")
                        .header("X-Actor-Id", "risk-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectType":"CREDITOR","listType":"DENY","value":"%s"}
                                """.formatted(creditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn();
        String entryId = id(proposed, "$.listEntryId");
        assess(debtor, creditor, 10_000, "API", "US", null, "before-deny-" + debtor);

        mvc.perform(post("/internal/v1/restricted-lists/{id}/approvals", entryId).header("X-Actor-Id", "risk-1"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/v1/restricted-lists/{id}/approvals", entryId).header("X-Actor-Id", "risk-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));

        MvcResult declined = assess(debtor, creditor, 10_000, "API", "US", null, "after-deny-" + debtor);
        org.hamcrest.MatcherAssert.assertThat(declined.getResponse().getContentAsString(), containsString("DECLINE"));
        org.hamcrest.MatcherAssert.assertThat(declined.getResponse().getContentAsString(), containsString("LIST_CREDITOR_DENY"));
    }

    @Test
    void anAllowListSuppressesTheNewCreditorRule() throws Exception {
        UUID creditor = UUID.randomUUID();
        mvc.perform(post("/internal/v1/restricted-lists")
                        .header("X-Actor-Id", "risk-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"subjectType":"CREDITOR","listType":"ALLOW","value":"%s"}
                                """.formatted(creditor)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE"));
        MvcResult allowed = assess(UUID.randomUUID(), creditor, 10_000, "API", "US", null, "allow-" + creditor);
        org.hamcrest.MatcherAssert.assertThat(allowed.getResponse().getContentAsString(), containsString("\"score\":0"));
        org.hamcrest.MatcherAssert.assertThat(allowed.getResponse().getContentAsString(), not(containsString("NEW_CREDITOR")));
    }

    @Test
    void reviewCanBeReleasedBySomeoneElseAndNotByTheSubmitter() throws Exception {
        UUID debtor = UUID.randomUUID();
        MvcResult created = assess(debtor, UUID.randomUUID(), 10_000, "MOBILE", null, "device-1", "review-" + debtor);
        String paymentId = id(created, "$.paymentId");
        org.hamcrest.MatcherAssert.assertThat(created.getResponse().getContentAsString(), containsString("REVIEW"));
        org.hamcrest.MatcherAssert.assertThat(created.getResponse().getContentAsString(), not(containsString("device-1")));

        mvc.perform(post("/internal/v1/fraud-assessments/{id}/decisions", paymentId)
                        .header("X-Actor-Id", "teller")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"RELEASE_TO_AUTHORIZE\",\"rationale\":\"looks familiar\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/v1/fraud-assessments/{id}/decisions", paymentId)
                        .header("X-Actor-Id", "analyst-2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"RELEASE_TO_AUTHORIZE\",\"rationale\":\"known customer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DECIDED"))
                .andExpect(jsonPath("$.decision").value("RELEASE_TO_AUTHORIZE"));

        byte[] payload = outbox.findByAggregateIdOrderByCreatedAtAsc(UUID.fromString(paymentId)).getFirst().getPayload();
        String event = new String(payload);
        org.hamcrest.MatcherAssert.assertThat(event, containsString("FraudAssessmentCompleted"));
        org.hamcrest.MatcherAssert.assertThat(event, containsString("REVIEW"));
        org.hamcrest.MatcherAssert.assertThat(event, not(containsString("device-1")));
        org.hamcrest.MatcherAssert.assertThat(event, not(containsString("featureSnapshot")));
    }

    @Test
    void confirmDeclineAndEscalateAreRecorded() throws Exception {
        String declined = id(assess(UUID.randomUUID(), UUID.randomUUID(), 10_000, "WEB", null, null, "decline-" + UUID.randomUUID()), "$.paymentId");
        mvc.perform(post("/internal/v1/fraud-assessments/{id}/decisions", declined)
                        .header("X-Actor-Id", "analyst-9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"CONFIRM_DECLINE\",\"rationale\":\"not the customer\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reasonCode").value("FRAUD_DECLINE"));

        String escalated = id(assess(UUID.randomUUID(), UUID.randomUUID(), 10_000, "WEB", null, null, "escalate-" + UUID.randomUUID()), "$.paymentId");
        mvc.perform(post("/internal/v1/fraud-assessments/{id}/decisions", escalated)
                        .header("X-Actor-Id", "analyst-9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"decision\":\"ESCALATE\",\"rationale\":\"needs a senior\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ESCALATED"));
    }

    @Test
    void aFlushedWindowIsRebuiltFromCommittedAssessments() throws Exception {
        UUID debtor = UUID.randomUUID();
        UUID creditor = UUID.randomUUID();
        assess(debtor, creditor, 125_000, "API", "US", null, "flush-a-" + debtor);
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();
        MvcResult second = assess(debtor, creditor, 125_000, "API", "US", null, "flush-b-" + debtor);
        org.hamcrest.MatcherAssert.assertThat(second.getResponse().getContentAsString(), containsString("VELOCITY_DEBTOR_15m"));
        org.hamcrest.MatcherAssert.assertThat(second.getResponse().getContentAsString(), containsString("REVIEW"));
    }

    @Test
    void aCountryMismatchAndASharedDeviceAreVisible() throws Exception {
        MvcResult mismatch = assess(UUID.randomUUID(), UUID.randomUUID(), 10_000, "API", "US", null, "geo-" + UUID.randomUUID(), "US", "GB");
        org.hamcrest.MatcherAssert.assertThat(mismatch.getResponse().getContentAsString(), containsString("GEO_CREDITOR_COUNTRY_MISMATCH"));

        String device = "shared-" + UUID.randomUUID();
        assess(UUID.randomUUID(), UUID.randomUUID(), 10_000, "MOBILE", "US", device, "dev-a-" + device);
        MvcResult shared = assess(UUID.randomUUID(), UUID.randomUUID(), 10_000, "MOBILE", "US", device, "dev-b-" + device);
        org.hamcrest.MatcherAssert.assertThat(shared.getResponse().getContentAsString(), containsString("DEVICE_MANY_DEBTORS"));
    }

    private MvcResult assess(UUID debtor, UUID creditor, long amount, String channel, String session, String device, String key) throws Exception {
        return assess(debtor, creditor, amount, channel, session, device, key, "US", "US");
    }

    private MvcResult assess(
            UUID debtor,
            UUID creditor,
            long amount,
            String channel,
            String session,
            String device,
            String key,
            String debtorCountry,
            String creditorCountry) throws Exception {
        return mvc.perform(post("/internal/v1/fraud-assessments")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(UUID.randomUUID(), debtor, creditor, amount, channel, session, device, key, debtorCountry, creditorCountry)))
                .andExpect(status().isOk())
                .andReturn();
    }

    private static String body(UUID paymentId, UUID debtor, UUID creditor, long amount, String channel, String session, String device, String key) {
        return body(paymentId, debtor, creditor, amount, channel, session, device, key, "US", "US");
    }

    private static String body(
            UUID paymentId,
            UUID debtor,
            UUID creditor,
            long amount,
            String channel,
            String session,
            String device,
            String key,
            String debtorCountry,
            String creditorCountry) {
        String sessionJson = session == null ? "" : ",\n  \"sessionCountry\": \"" + session + "\"";
        String deviceJson = device == null ? "" : ",\n  \"deviceId\": \"" + device + "\"";
        return """
                {
                  "paymentId": "%s",
                  "idempotencyKey": "%s",
                  "amountMinor": %d,
                  "currency": "USD",
                  "debtorAccountId": "%s",
                  "creditorAccountId": "%s",
                  "debtorCountry": "%s",
                  "creditorCountry": "%s",
                  "rail": "BOOK",
                  "channel": "%s",
                  "submittedBy": "teller"%s%s
                }
                """.formatted(paymentId, key, amount, debtor, creditor, debtorCountry, creditorCountry, channel, sessionJson, deviceJson);
    }

    private static String id(MvcResult result, String path) throws Exception {
        return com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), path);
    }
}
