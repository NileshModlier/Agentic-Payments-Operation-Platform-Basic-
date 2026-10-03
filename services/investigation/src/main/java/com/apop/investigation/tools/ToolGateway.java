package com.apop.investigation.tools;

import com.apop.investigation.domain.PromptCatalog;
import com.apop.investigation.domain.ToolCatalog;
import com.apop.investigation.domain.UuidV7;
import com.apop.investigation.persistence.AgentToolCall;
import com.apop.investigation.persistence.AgentToolCallRepository;
import com.apop.investigation.persistence.AuditEvent;
import com.apop.investigation.persistence.AuditEventRepository;
import com.apop.investigation.persistence.InvestigationCase;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class ToolGateway {

    private final AgentToolCallRepository toolCalls;
    private final AuditEventRepository audits;
    private final ObjectMapper mapper;
    private final PromptCatalog prompts;
    private final JavaServiceTools httpTools;
    private final Clock clock;
    private final boolean httpMode;

    public ToolGateway(
            AgentToolCallRepository toolCalls,
            AuditEventRepository audits,
            ObjectMapper mapper,
            PromptCatalog prompts,
            JavaServiceTools httpTools,
            Clock clock,
            @Value("${investigation.tools.mode:evidence}") String mode) {
        this.toolCalls = toolCalls;
        this.audits = audits;
        this.mapper = mapper;
        this.prompts = prompts;
        this.httpTools = httpTools;
        this.clock = clock;
        this.httpMode = "http".equals(mode);
    }

    @Transactional
    public ToolResult execute(InvestigationCase caseRow, UUID sessionId, String role, String toolName, Map<String, String> arguments) {
        String requestedPayment = arguments.get("paymentId");
        if (requestedPayment != null && !requestedPayment.equals(caseRow.getPaymentId().toString())) {
            return record(caseRow, sessionId, toolName, arguments, "PAYMENT_SCOPE", List.of(), mapper.createObjectNode());
        }
        if (!ToolCatalog.allowed(role, toolName)) {
            return record(caseRow, sessionId, toolName, arguments, "REFUSED", List.of(), mapper.createObjectNode());
        }
        try {
            JsonNode evidence = mapper.readTree(caseRow.getEvidence());
            Read read = read(caseRow, role, toolName, arguments, evidence);
            return record(caseRow, sessionId, toolName, arguments, read.code(), read.evidenceIds(), read.body());
        } catch (Exception exception) {
            return record(caseRow, sessionId, toolName, arguments, "TECHNICAL_DEPENDENCY", List.of(), mapper.createObjectNode());
        }
    }

    private Read read(InvestigationCase caseRow, String role, String toolName, Map<String, String> arguments, JsonNode evidence) {
        UUID paymentId = caseRow.getPaymentId();
        return switch (toolName) {
            case "get_payment" -> section("payment:" + paymentId, paymentSection(paymentId, evidence));
            case "get_timeline" -> section("timeline:" + paymentId, evidence.path("timeline"));
            case "get_fraud_assessment" -> assessment(evidence);
            case "get_ledger_movements" -> ledger(paymentId, evidence);
            case "get_settlement_item" -> section("settlement:" + paymentId, settlementSection(paymentId, evidence));
            case "get_break" -> named(evidence.path("break"), "breakId", "break");
            case "get_failure_case" -> named(evidence.path("failure"), "failureCaseId", "failure");
            case "summarize_similar" -> section("similar:" + paymentId, evidence.path("similar"));
            case "get_runbook" -> runbook(arguments.get("runbookId"));
            case "get_case_summary" -> summary(caseRow, role);
            default -> new Read("REFUSED", List.of(), mapper.createObjectNode());
        };
    }

    private JsonNode paymentSection(UUID paymentId, JsonNode evidence) {
        if (httpMode) {
            return AccountMask.apply(httpTools.payment(paymentId));
        }
        return evidence.path("payment");
    }

    private JsonNode settlementSection(UUID paymentId, JsonNode evidence) {
        if (httpMode) {
            return AccountMask.apply(httpTools.settlement(paymentId));
        }
        return evidence.path("settlement");
    }

    private Read assessment(JsonNode evidence) {
        JsonNode node = evidence.path("fraudAssessment");
        if (httpMode) {
            node = httpTools.fraudAssessment(UUID.fromString(evidence.path("payment").path("paymentId").asText()));
        }
        if (node.isMissingNode() || node.isNull() || node.isEmpty()) {
            return new Read("NOT_FOUND", List.of(), mapper.createObjectNode());
        }
        String assessmentId = node.path("assessmentId").asText("");
        JsonNode masked = AccountMask.apply(node);
        return new Read("OK", List.of("fraud-assessment:" + assessmentId), masked);
    }

    private Read ledger(UUID paymentId, JsonNode evidence) {
        JsonNode node = httpMode ? httpTools.ledger(paymentId) : evidence.path("ledger");
        if (node.isMissingNode() || node.isNull() || node.isEmpty()) {
            return new Read("NOT_FOUND", List.of(), mapper.createObjectNode());
        }
        List<String> ids = new ArrayList<>();
        ids.add("ledger:" + paymentId);
        String journal = node.path("journalEntryId").asText("");
        if (!journal.isBlank()) {
            ids.add("journal:" + journal);
        }
        return new Read("OK", ids, AccountMask.apply(node));
    }

    private Read named(JsonNode node, String idField, String prefix) {
        if (node.isMissingNode() || node.isNull() || node.isEmpty()) {
            return new Read("NOT_FOUND", List.of(), mapper.createObjectNode());
        }
        List<String> ids = new ArrayList<>();
        String id = node.path(idField).asText("");
        if (!id.isBlank()) {
            ids.add(prefix + ":" + id);
        }
        String journal = node.path("journalEntryId").asText("");
        if (!journal.isBlank()) {
            ids.add("journal:" + journal);
        }
        return new Read("OK", ids, AccountMask.apply(node));
    }

    private Read section(String evidenceId, JsonNode node) {
        if (node.isMissingNode() || node.isNull() || (node.isObject() && node.isEmpty()) || (node.isArray() && node.isEmpty())) {
            return new Read("NOT_FOUND", List.of(), mapper.createObjectNode());
        }
        return new Read("OK", List.of(evidenceId), AccountMask.apply(node));
    }

    private Read runbook(String runbookId) {
        String passage = prompts.runbook(runbookId);
        if (passage == null) {
            return new Read("NOT_FOUND", List.of(), mapper.createObjectNode());
        }
        ObjectNode body = mapper.createObjectNode();
        body.put("runbookId", runbookId);
        body.put("passage", passage);
        return new Read("OK", List.of("runbook:" + runbookId), body);
    }

    private Read summary(InvestigationCase caseRow, String role) {
        ObjectNode body = mapper.createObjectNode();
        body.put("caseId", caseRow.getCaseId().toString());
        body.put("paymentId", caseRow.getPaymentId().toString());
        body.put("source", caseRow.getSource());
        body.put("status", caseRow.getCaseStatus());
        if (!"REPORTING".equals(role)) {
            return new Read("REFUSED", List.of(), mapper.createObjectNode());
        }
        return new Read("OK", List.of("case:" + caseRow.getCaseId()), body);
    }

    private ToolResult record(
            InvestigationCase caseRow,
            UUID sessionId,
            String toolName,
            Map<String, String> arguments,
            String code,
            List<String> evidenceIds,
            JsonNode body) {
        String safeName = toolName == null ? "unknown" : toolName;
        toolCalls.save(AgentToolCall.create(
                UuidV7.generate(),
                sessionId,
                safeName,
                hash(safeName + arguments),
                code,
                clock.instant()));
        audits.save(AuditEvent.create(
                UuidV7.generate(),
                "AGENT",
                "ToolCall",
                safeName,
                caseRow.getCaseId().toString(),
                mapper.createObjectNode().put("responseCode", code).put("sessionId", sessionId.toString()).toString(),
                clock.instant()));
        return new ToolResult(safeName, code, evidenceIds, body.toString(), String.join(",", evidenceIds));
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    public record ToolResult(String toolName, String responseCode, List<String> evidenceIds, String body, String evidenceList) {
        public String logLine() {
            return toolName + "|" + responseCode + "|" + evidenceList + "|" + body;
        }
    }

    private record Read(String code, List<String> evidenceIds, JsonNode body) {
    }
}
