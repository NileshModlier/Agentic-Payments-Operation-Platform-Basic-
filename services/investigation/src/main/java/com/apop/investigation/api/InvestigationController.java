package com.apop.investigation.api;

import com.apop.investigation.domain.InvestigationException;
import com.apop.investigation.service.InvestigationService;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;
import java.util.UUID;

@RestController
public class InvestigationController {

    private final InvestigationService investigations;

    public InvestigationController(InvestigationService investigations) {
        this.investigations = investigations;
    }

    @PostMapping("/internal/v1/investigation-cases")
    public InvestigationService.CaseView open(@Valid @RequestBody OpenRequest request) {
        MDC.put("paymentId", request.paymentId().toString());
        return investigations.open(new InvestigationService.OpenCommand(
                request.paymentId(),
                request.source(),
                request.sourceRef(),
                request.submittedBy(),
                request.severity(),
                request.amountMinor(),
                request.currency(),
                request.evidence()));
    }

    @GetMapping("/v1/cases")
    public List<InvestigationService.CaseView> list() {
        return investigations.list();
    }

    @GetMapping("/v1/cases/{caseId}")
    public InvestigationService.CaseView get(@PathVariable UUID caseId) {
        return investigations.get(caseId);
    }

    @PostMapping("/v1/cases/{caseId}/assist")
    public InvestigationService.CaseView assist(
            @PathVariable UUID caseId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @RequestBody(required = false) AssistRequest request) {
        return investigations.assist(caseId, actor, request == null ? null : request.requestedRole());
    }

    @PostMapping("/v1/cases/{caseId}/decisions")
    public InvestigationService.CaseView decide(
            @PathVariable UUID caseId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @Valid @RequestBody DecisionRequest request) {
        return investigations.decide(caseId, request.decision(), request.rationale(), actor);
    }

    @PostMapping("/v1/cases/{caseId}/approvals")
    public InvestigationService.CaseView approve(
            @PathVariable UUID caseId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @Valid @RequestBody DecisionRequest request) {
        return investigations.approve(caseId, request.decision(), request.rationale(), actor);
    }

    @PostMapping("/v1/cases/{caseId}/reports")
    public InvestigationService.CaseView draft(
            @PathVariable UUID caseId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor) {
        return investigations.assist(caseId, actor, "REPORTING");
    }

    @PostMapping("/v1/cases/{caseId}/reports/{reportId}/publications")
    public InvestigationService.CaseView publish(
            @PathVariable UUID caseId,
            @PathVariable UUID reportId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor) {
        return investigations.publish(caseId, reportId, actor);
    }

    public record OpenRequest(
            @NotNull UUID paymentId,
            @NotBlank String source,
            @NotNull UUID sourceRef,
            @NotBlank String submittedBy,
            @NotBlank String severity,
            @Positive long amountMinor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency,
            @NotNull JsonNode evidence) {
    }

    public record AssistRequest(String requestedRole) {
    }

    public record DecisionRequest(@NotBlank String decision, @NotBlank String rationale) {
    }
}

@RestControllerAdvice
class InvestigationExceptionHandler {

    @ExceptionHandler(InvestigationException.class)
    public ResponseEntity<ProblemBody> handle(InvestigationException exception) {
        return ResponseEntity.status(exception.status()).body(new ProblemBody(
                "https://apop.internal/problems/" + exception.type(),
                exception.status().getReasonPhrase(),
                exception.status().value(),
                exception.getMessage(),
                exception.reasonCode(),
                MDC.get("correlationId")));
    }

    public record ProblemBody(String type, String title, int status, String detail, String reasonCode, String correlationId) {
    }
}
