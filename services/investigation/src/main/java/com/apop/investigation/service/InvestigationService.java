package com.apop.investigation.service;

import com.apop.investigation.agent.AgentControls;
import com.apop.investigation.agent.InvestigationGraph;
import com.apop.investigation.domain.InvestigationException;
import com.apop.investigation.domain.UuidV7;
import com.apop.investigation.persistence.AgentRecommendation;
import com.apop.investigation.persistence.AgentRecommendationRepository;
import com.apop.investigation.persistence.AgentSession;
import com.apop.investigation.persistence.AgentSessionRepository;
import com.apop.investigation.persistence.CaseNote;
import com.apop.investigation.persistence.CaseNoteRepository;
import com.apop.investigation.persistence.CaseReport;
import com.apop.investigation.persistence.CaseReportRepository;
import com.apop.investigation.persistence.HumanDecision;
import com.apop.investigation.persistence.HumanDecisionRepository;
import com.apop.investigation.persistence.InvestigationCase;
import com.apop.investigation.persistence.InvestigationCaseRepository;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
public class InvestigationService {

    private static final Set<String> SOURCES = Set.of("FRAUD_REVIEW", "RECON_BREAK", "ESCALATED_FAILURE");
    private static final String MODEL_ROUTE = "investigation-default";

    private final InvestigationCaseRepository cases;
    private final AgentSessionRepository sessions;
    private final AgentRecommendationRepository recommendations;
    private final HumanDecisionRepository decisions;
    private final CaseNoteRepository notes;
    private final CaseReportRepository reports;
    private final SessionWriter writer;
    private final InvestigationGraph graph;
    private final AgentControls controls;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final long fourEyesMinor;

    public InvestigationService(
            InvestigationCaseRepository cases,
            AgentSessionRepository sessions,
            AgentRecommendationRepository recommendations,
            HumanDecisionRepository decisions,
            CaseNoteRepository notes,
            CaseReportRepository reports,
            SessionWriter writer,
            InvestigationGraph graph,
            AgentControls controls,
            ObjectMapper mapper,
            Clock clock,
            @Value("${investigation.reversal.four-eyes-minor:1000000}") long fourEyesMinor) {
        this.cases = cases;
        this.sessions = sessions;
        this.recommendations = recommendations;
        this.decisions = decisions;
        this.notes = notes;
        this.reports = reports;
        this.writer = writer;
        this.graph = graph;
        this.controls = controls;
        this.mapper = mapper;
        this.clock = clock;
        this.fourEyesMinor = fourEyesMinor;
    }

    @Transactional
    public CaseView open(OpenCommand command) {
        if (!SOURCES.contains(command.source())) {
            throw InvestigationException.badRequest("Unsupported case source");
        }
        return cases.findBySourceAndSourceRef(command.source(), command.sourceRef())
                .map(this::view)
                .orElseGet(() -> {
                    InvestigationCase created = InvestigationCase.open(
                            UuidV7.generate(),
                            command.paymentId(),
                            command.source(),
                            command.sourceRef(),
                            command.submittedBy(),
                            command.severity(),
                            command.amountMinor(),
                            command.currency(),
                            command.evidence().toString(),
                            clock.instant());
                    cases.save(created);
                    writer.publishCase(created, null, null);
                    return view(created);
                });
    }

    @Transactional(readOnly = true)
    public List<CaseView> list() {
        return cases.findAll().stream()
                .sorted(Comparator.comparing(InvestigationCase::getOpenedAt))
                .map(this::view)
                .toList();
    }

    @Transactional(readOnly = true)
    public CaseView get(UUID caseId) {
        return view(load(caseId));
    }

    public CaseView assist(UUID caseId, String actor, String requestedRole) {
        if (actor == null || actor.isBlank()) {
            throw InvestigationException.badRequest("X-Actor-Id is required");
        }
        InvestigationCase caseRow = load(caseId);
        if (!controls.enabled()) {
            writer.audit(actor, "AssistDenied", "investigation_case", caseId.toString(),
                    mapper.createObjectNode().put("reason", "AGENT_PAUSED"));
            throw InvestigationException.paused();
        }
        if (!controls.modelAvailable()) {
            writer.audit(actor, "AssistDenied", "investigation_case", caseId.toString(),
                    mapper.createObjectNode().put("reason", "MODEL_UNAVAILABLE"));
            throw InvestigationException.unavailable("The investigation model is unavailable. The queue is still open.");
        }
        if (!"REPORTING".equals(requestedRole)
                && ("DECIDED".equals(caseRow.getCaseStatus()) || "CLOSED".equals(caseRow.getCaseStatus()))) {
            throw InvestigationException.illegal("The case is already decided");
        }
        if ("REPORTING".equals(requestedRole) && decisions.findByCaseId(caseId).isEmpty()) {
            throw InvestigationException.illegal("A report draft waits until a human has decided the case");
        }
        String priorStatus = caseRow.getCaseStatus();
        UUID sessionId = UuidV7.generate();
        sessions.save(AgentSession.start(sessionId, caseId, MODEL_ROUTE, "SUPERVISOR", clock.instant()));
        caseRow.setCaseStatus("AGENT_RUNNING");
        cases.save(caseRow);
        writer.audit(actor, "AssistanceRequested", "agent_session", sessionId.toString(),
                mapper.createObjectNode().put("caseId", caseId.toString()).put("requestedRole", requestedRole == null ? "" : requestedRole));
        try {
            graph.run(Map.of(
                    "caseId", caseId.toString(),
                    "sessionId", sessionId.toString(),
                    "paymentId", caseRow.getPaymentId().toString(),
                    "source", caseRow.getSource(),
                    "requestedRole", requestedRole == null ? "" : requestedRole,
                    "startedAt", Long.toString(clock.millis()),
                    "turn", "0",
                    "route", "supervisor",
                    "priorStatus", priorStatus
            ), sessionId);
        } catch (RuntimeException exception) {
            writer.stop(sessionId, caseId, "RUNNER");
            throw InvestigationException.unavailable("The investigation agent stopped before a recommendation.");
        }
        return view(load(caseId));
    }

    @Transactional
    public CaseView decide(UUID caseId, String decision, String rationale, String actor) {
        InvestigationCase caseRow = load(caseId);
        requireActor(caseRow, actor, rationale);
        if ("DECIDED".equals(caseRow.getCaseStatus()) || "CLOSED".equals(caseRow.getCaseStatus())) {
            throw InvestigationException.illegal("The case is already decided");
        }
        if (decisions.findByCaseId(caseId).stream().anyMatch(row -> "PRIMARY".equals(row.getDecisionRole()))) {
            throw InvestigationException.illegal("The case already has a primary decision");
        }
        if (!allowedDecision(caseRow.getSource()).contains(decision)) {
            throw InvestigationException.illegal("That disposition is not available for this case");
        }
        if (actor.equals(caseRow.getEscalatedBy())) {
            throw InvestigationException.forbidden("The person who escalated the case cannot decide it");
        }
        Instant now = clock.instant();
        if ("ESCALATE".equals(decision)) {
            caseRow.setQueueName("SENIOR");
            caseRow.setEscalatedBy(actor);
            caseRow.setCaseStatus("AWAITING_HUMAN");
            notes.save(CaseNote.create(UuidV7.generate(), caseId, null, actor, rationale, now));
            writer.audit(actor, "Escalated", "investigation_case", caseId.toString(),
                    mapper.createObjectNode().put("queue", "SENIOR"));
            writer.publishCase(caseRow, null, "ESCALATE");
            return view(caseRow);
        }
        AgentRecommendation recommendation = recommendations.findFirstByCaseIdOrderByCreatedAtDesc(caseId).orElse(null);
        decisions.save(HumanDecision.create(
                UuidV7.generate(),
                caseId,
                recommendation == null ? null : recommendation.getRecommendationId(),
                decision,
                "PRIMARY",
                actor,
                rationale,
                now));
        boolean waiting = "PROPOSE_REVERSAL".equals(decision) && caseRow.getAmountMinor() >= fourEyesMinor;
        if (waiting) {
            caseRow.setCaseStatus("AWAITING_HUMAN");
            caseRow.setQueueName("SENIOR");
        } else {
            caseRow.setCaseStatus("DECIDED");
            caseRow.setClosedAt(now);
        }
        writer.audit(actor, "HumanDecisionRecorded", "investigation_case", caseId.toString(),
                mapper.createObjectNode().put("disposition", decision).put("decisionRole", "PRIMARY").put("moneyMovement", "NONE"));
        writer.publishCase(caseRow, recommendation == null ? null : recommendation.getRecommendationId(), decision);
        return view(caseRow);
    }

    @Transactional
    public CaseView approve(UUID caseId, String decision, String rationale, String actor) {
        InvestigationCase caseRow = load(caseId);
        requireActor(caseRow, actor, rationale);
        HumanDecision primary = decisions.findByCaseId(caseId).stream()
                .filter(row -> "PRIMARY".equals(row.getDecisionRole()))
                .findFirst()
                .orElseThrow(() -> InvestigationException.illegal("A primary reversal decision is required"));
        if (!"PROPOSE_REVERSAL".equals(primary.getDecision()) || caseRow.getAmountMinor() < fourEyesMinor) {
            throw InvestigationException.illegal("This case does not require a second approver");
        }
        if (actor.equals(primary.getDecidedBy())) {
            throw InvestigationException.forbidden("The second approver must be a different person");
        }
        if (decisions.findByCaseId(caseId).stream().anyMatch(row -> "COUNTER_APPROVAL".equals(row.getDecisionRole()))) {
            throw InvestigationException.illegal("The reversal is already counter-approved");
        }
        if (!"PROPOSE_REVERSAL".equals(decision)) {
            throw InvestigationException.illegal("The counter-approval must confirm the reversal proposal");
        }
        Instant now = clock.instant();
        decisions.save(HumanDecision.create(
                UuidV7.generate(),
                caseId,
                null,
                decision,
                "COUNTER_APPROVAL",
                actor,
                rationale,
                now));
        caseRow.setCaseStatus("DECIDED");
        caseRow.setClosedAt(now);
        writer.audit(actor, "CounterApprovalRecorded", "investigation_case", caseId.toString(),
                mapper.createObjectNode().put("disposition", decision).put("moneyMovement", "NONE"));
        writer.publishCase(caseRow, null, decision);
        return view(caseRow);
    }

    @Transactional
    public CaseView publish(UUID caseId, UUID reportId, String actor) {
        if (actor == null || actor.isBlank()) {
            throw InvestigationException.badRequest("X-Actor-Id is required");
        }
        CaseReport report = reports.findById(reportId).orElseThrow(() -> InvestigationException.notFound("Report was not found"));
        if (!report.getCaseId().equals(caseId)) {
            throw InvestigationException.notFound("Report was not found");
        }
        report.publish(actor, clock.instant());
        writer.audit(actor, "ReportPublished", "case_report", reportId.toString(),
                mapper.createObjectNode().put("caseId", caseId.toString()).put("moneyMovement", "NONE"));
        return view(load(caseId));
    }

    private void requireActor(InvestigationCase caseRow, String actor, String rationale) {
        if (actor == null || actor.isBlank()) {
            throw InvestigationException.badRequest("X-Actor-Id is required");
        }
        if (rationale == null || rationale.isBlank() || rationale.length() > 512) {
            throw InvestigationException.badRequest("A rationale is required");
        }
        if (actor.equals(caseRow.getSubmittedBy()) || "AGENT".equals(actor)) {
            throw InvestigationException.forbidden("The person who submitted the payment cannot decide it");
        }
    }

    private static Set<String> allowedDecision(String source) {
        return switch (source) {
            case "FRAUD_REVIEW" -> Set.of("CONFIRM_DECLINE", "RELEASE_TO_AUTHORIZE", "ESCALATE", "NO_ACTION");
            case "ESCALATED_FAILURE" -> Set.of("RETRY_TECHNICAL", "NO_ACTION", "ESCALATE");
            case "RECON_BREAK" -> Set.of("RETRY_TECHNICAL", "PROPOSE_REVERSAL", "NO_ACTION", "ESCALATE");
            default -> Set.of();
        };
    }

    private InvestigationCase load(UUID caseId) {
        return cases.findById(caseId).orElseThrow(() -> InvestigationException.notFound("Case was not found"));
    }

    private CaseView view(InvestigationCase caseRow) {
        AgentSession latest = sessions.findByCaseIdOrderByStartedAtDesc(caseRow.getCaseId()).stream().findFirst().orElse(null);
        AgentRecommendation recommendation = recommendations.findFirstByCaseIdOrderByCreatedAtDesc(caseRow.getCaseId()).orElse(null);
        boolean showRecommendation = recommendation != null && latest != null && "COMPLETED".equals(latest.getStatus())
                && recommendation.getSessionId().equals(latest.getSessionId());
        List<HumanDecision> human = decisions.findByCaseId(caseRow.getCaseId());
        HumanDecision primary = human.stream().filter(row -> "PRIMARY".equals(row.getDecisionRole())).findFirst().orElse(null);
        HumanDecision counter = human.stream().filter(row -> "COUNTER_APPROVAL".equals(row.getDecisionRole())).findFirst().orElse(null);
        CaseReport report = reports.findFirstByCaseIdOrderByCreatedAtDesc(caseRow.getCaseId()).orElse(null);
        return new CaseView(
                caseRow.getCaseId(),
                caseRow.getPaymentId(),
                caseRow.getSource(),
                caseRow.getCaseStatus(),
                caseRow.getQueueName(),
                "NONE",
                latest == null ? null : latest.getAgentRole(),
                latest == null ? null : latest.getStatus(),
                latest == null ? null : latest.getGuardCheck(),
                showRecommendation ? new RecommendationView(
                        recommendation.getRecommendationId(),
                        recommendation.getDisposition(),
                        recommendation.getConfidence(),
                        recommendation.getEvidenceIds(),
                        recommendation.getNarrative()) : null,
                primary == null ? null : new DecisionView(primary.getDecision(), primary.getDecisionRole(), primary.getDecidedBy()),
                counter == null ? null : new DecisionView(counter.getDecision(), counter.getDecisionRole(), counter.getDecidedBy()),
                report == null ? null : new ReportView(report.getReportId(), report.getDraft(), report.getPublishedBy()),
                caseRow.getEscalatedBy());
    }

    public record OpenCommand(
            UUID paymentId,
            String source,
            UUID sourceRef,
            String submittedBy,
            String severity,
            long amountMinor,
            String currency,
            JsonNode evidence) {
    }

    public record CaseView(
            UUID caseId,
            UUID paymentId,
            String source,
            String status,
            String queue,
            String moneyMovement,
            String agentRole,
            String sessionStatus,
            String guardCheck,
            RecommendationView recommendation,
            DecisionView decision,
            DecisionView counterApproval,
            ReportView report,
            String escalatedBy) {
    }

    public record RecommendationView(UUID recommendationId, String disposition, BigDecimal confidence, String evidenceIds, String narrative) {
    }

    public record DecisionView(String decision, String decisionRole, String decidedBy) {
    }

    public record ReportView(UUID reportId, String draft, String publishedBy) {
    }
}
