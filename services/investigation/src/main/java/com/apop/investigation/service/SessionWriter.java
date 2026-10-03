package com.apop.investigation.service;

import com.apop.investigation.domain.PolicyGuard;
import com.apop.investigation.domain.UuidV7;
import com.apop.investigation.persistence.AgentRecommendation;
import com.apop.investigation.persistence.AgentRecommendationRepository;
import com.apop.investigation.persistence.AgentSession;
import com.apop.investigation.persistence.AgentSessionRepository;
import com.apop.investigation.persistence.AuditEvent;
import com.apop.investigation.persistence.AuditEventRepository;
import com.apop.investigation.persistence.CaseNote;
import com.apop.investigation.persistence.CaseNoteRepository;
import com.apop.investigation.persistence.CaseReport;
import com.apop.investigation.persistence.CaseReportRepository;
import com.apop.investigation.persistence.InvestigationCase;
import com.apop.investigation.persistence.InvestigationCaseRepository;
import com.apop.investigation.persistence.OutboxMessage;
import com.apop.investigation.persistence.OutboxRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
public class SessionWriter {

    private final InvestigationCaseRepository cases;
    private final AgentSessionRepository sessions;
    private final AgentRecommendationRepository recommendations;
    private final CaseNoteRepository notes;
    private final CaseReportRepository reports;
    private final AuditEventRepository audits;
    private final OutboxRepository outbox;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final String caseTopic;
    private final String auditTopic;

    public SessionWriter(
            InvestigationCaseRepository cases,
            AgentSessionRepository sessions,
            AgentRecommendationRepository recommendations,
            CaseNoteRepository notes,
            CaseReportRepository reports,
            AuditEventRepository audits,
            OutboxRepository outbox,
            ObjectMapper mapper,
            Clock clock,
            @Value("${apop.messaging.case-topic}") String caseTopic,
            @Value("${apop.messaging.audit-topic}") String auditTopic) {
        this.cases = cases;
        this.sessions = sessions;
        this.recommendations = recommendations;
        this.notes = notes;
        this.reports = reports;
        this.audits = audits;
        this.outbox = outbox;
        this.mapper = mapper;
        this.clock = clock;
        this.caseTopic = caseTopic;
        this.auditTopic = auditTopic;
    }

    @Transactional
    public void completeRecommendation(UUID sessionId, UUID caseId, String role, PolicyGuard.Draft draft, String memory) {
        Instant now = clock.instant();
        AgentSession session = sessions.findById(sessionId).orElseThrow();
        InvestigationCase caseRow = cases.findById(caseId).orElseThrow();
        UUID recommendationId = UuidV7.generate();
        recommendations.save(AgentRecommendation.create(
                recommendationId,
                caseId,
                sessionId,
                draft.disposition(),
                draft.confidence().setScale(3, RoundingMode.HALF_UP),
                mapper.valueToTree(draft.evidenceIds()).toString(),
                draft.narrative(),
                now));
        finish(session, role, "COMPLETED", null, memory, now);
        caseRow.setCaseStatus("AWAITING_HUMAN");
        notes.save(CaseNote.create(UuidV7.generate(), caseId, sessionId, "AGENT", "Recommendation recorded", now));
        audit("AGENT", "RecommendationRecorded", "agent_session", sessionId.toString(),
                mapper.createObjectNode().put("disposition", draft.disposition()).put("caseId", caseId.toString()));
        publishCase(caseRow, recommendationId, null);
    }

    @Transactional
    public void completeReport(UUID sessionId, UUID caseId, String draft, String memory, String priorStatus) {
        Instant now = clock.instant();
        AgentSession session = sessions.findById(sessionId).orElseThrow();
        reports.save(CaseReport.create(UuidV7.generate(), caseId, sessionId, draft, now));
        finish(session, "REPORTING", "COMPLETED", null, memory, now);
        restore(caseId, priorStatus);
        audit("AGENT", "ReportDraftCreated", "case_report", caseId.toString(),
                mapper.createObjectNode().put("sessionId", sessionId.toString()).put("modelRoute", session.getModelRoute()));
    }

    @Transactional
    public void fail(UUID sessionId, UUID caseId, String role, String check, String memory, String priorStatus) {
        Instant now = clock.instant();
        AgentSession session = sessions.findById(sessionId).orElseThrow();
        finish(session, role, "FAILED", check, memory, now);
        if ("DECIDED".equals(priorStatus) || "CLOSED".equals(priorStatus)) {
            restore(caseId, priorStatus);
        } else {
            cases.findById(caseId).ifPresent(caseRow -> caseRow.setCaseStatus("AWAITING_HUMAN"));
        }
        ObjectNode detail = mapper.createObjectNode().put("check", check);
        audit("AGENT", "PolicyGuardRejected", "agent_session", sessionId.toString(), detail);
    }

    @Transactional
    public void stop(UUID sessionId, UUID caseId, String check) {
        Instant now = clock.instant();
        sessions.findById(sessionId).ifPresent(session -> finish(session, session.getAgentRole(), "STOPPED", check, "[]", now));
        cases.findById(caseId).ifPresent(caseRow -> {
            if ("AGENT_RUNNING".equals(caseRow.getCaseStatus())) {
                caseRow.setCaseStatus("AWAITING_HUMAN");
            }
        });
    }

    private void restore(UUID caseId, String priorStatus) {
        if (priorStatus == null || priorStatus.isBlank()) {
            return;
        }
        cases.findById(caseId).ifPresent(caseRow -> caseRow.setCaseStatus(priorStatus));
    }

    private void finish(AgentSession session, String role, String status, String check, String memory, Instant now) {
        session.setAgentRole(role);
        session.setStatus(status);
        session.setGuardCheck(check);
        session.setMemory(memory == null ? "[]" : memory);
        session.setFinishedAt(now);
    }

    public void audit(String actor, String action, String resourceType, String resourceId, ObjectNode detail) {
        audits.save(AuditEvent.create(UuidV7.generate(), actor, action, resourceType, resourceId, detail.toString(), clock.instant()));
        ObjectNode event = detail.deepCopy();
        event.put("eventName", action);
        event.put("actor", actor);
        event.put("moneyMovement", "NONE");
        outbox.save(OutboxMessage.create(
                UuidV7.generate(),
                uuidOrRandom(resourceId),
                auditTopic,
                action,
                event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                headers(),
                clock.instant()));
    }

    public void publishCase(InvestigationCase caseRow, UUID recommendationId, String humanDecision) {
        ObjectNode event = mapper.createObjectNode();
        event.put("eventName", "InvestigationCaseUpdated");
        event.put("caseId", caseRow.getCaseId().toString());
        event.put("paymentId", caseRow.getPaymentId().toString());
        event.put("source", caseRow.getSource());
        event.put("caseStatus", caseRow.getCaseStatus());
        event.put("moneyMovement", "NONE");
        if (recommendationId != null) {
            event.put("recommendationId", recommendationId.toString());
        }
        if (humanDecision != null) {
            event.put("humanDecision", humanDecision);
        }
        outbox.save(OutboxMessage.create(
                UuidV7.generate(),
                caseRow.getCaseId(),
                caseTopic,
                "com.apop.payments.investigation.case.v1",
                event.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                headers(),
                clock.instant()));
    }

    private String headers() {
        ObjectNode headers = mapper.createObjectNode();
        headers.put("content-type", "application/cloudevents+json");
        String correlation = MDC.get("correlationId");
        if (correlation != null) {
            headers.put("correlation-id", correlation);
        }
        return headers.toString();
    }

    private static UUID uuidOrRandom(String resourceId) {
        try {
            return UUID.fromString(resourceId);
        } catch (IllegalArgumentException exception) {
            return UuidV7.generate();
        }
    }
}
