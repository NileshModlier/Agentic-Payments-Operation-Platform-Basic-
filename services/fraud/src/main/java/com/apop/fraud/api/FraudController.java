package com.apop.fraud.api;

import com.apop.fraud.service.FraudAssessmentService;
import com.apop.fraud.service.FraudException;
import com.apop.fraud.service.RestrictedListService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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

import java.util.UUID;

@RestController
public class FraudController {

    private final FraudAssessmentService assessments;
    private final RestrictedListService lists;

    public FraudController(FraudAssessmentService assessments, RestrictedListService lists) {
        this.assessments = assessments;
        this.lists = lists;
    }

    @PostMapping("/internal/v1/fraud-assessments")
    public FraudAssessmentService.AssessmentView assess(@Valid @RequestBody AssessRequest request) {
        MDC.put("paymentId", request.paymentId().toString());
        try {
            return assessments.assess(new FraudAssessmentService.AssessCommand(
                    request.paymentId(),
                    request.idempotencyKey(),
                    request.amountMinor(),
                    request.currency(),
                    request.debtorAccountId(),
                    request.creditorAccountId(),
                    request.creditorIdentity(),
                    request.debtorCountry(),
                    request.creditorCountry(),
                    request.rail(),
                    request.channel(),
                    request.sessionCountry(),
                    request.deviceId(),
                    request.submittedBy()));
        } finally {
            MDC.remove("paymentId");
        }
    }

    @GetMapping("/internal/v1/fraud-assessments/{paymentId}")
    public FraudAssessmentService.AssessmentView get(@PathVariable UUID paymentId) {
        return assessments.get(paymentId);
    }

    @PostMapping("/internal/v1/fraud-assessments/{paymentId}/decisions")
    public FraudAssessmentService.ReviewView decide(
            @PathVariable UUID paymentId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @Valid @RequestBody DecisionRequest request) {
        return assessments.decide(paymentId, request.decision(), request.rationale(), actor);
    }

    @PostMapping("/internal/v1/restricted-lists")
    public RestrictedListService.ListView propose(
            @RequestHeader(value = "X-Actor-Id", required = false) String actor,
            @Valid @RequestBody ListRequest request) {
        return lists.propose(request.subjectType(), request.listType(), request.value(), actor);
    }

    @PostMapping("/internal/v1/restricted-lists/{listEntryId}/approvals")
    public RestrictedListService.ListView approve(
            @PathVariable UUID listEntryId,
            @RequestHeader(value = "X-Actor-Id", required = false) String actor) {
        return lists.approve(listEntryId, actor);
    }

    public record AssessRequest(
            @NotNull UUID paymentId,
            @NotBlank String idempotencyKey,
            @Positive long amountMinor,
            @NotBlank String currency,
            @NotNull UUID debtorAccountId,
            UUID creditorAccountId,
            String creditorIdentity,
            @NotBlank String debtorCountry,
            @NotBlank String creditorCountry,
            @NotBlank String rail,
            @NotBlank String channel,
            String sessionCountry,
            String deviceId,
            @NotBlank String submittedBy
    ) {
    }

    public record DecisionRequest(@NotBlank String decision, @NotBlank String rationale) {
    }

    public record ListRequest(@NotBlank String subjectType, @NotBlank String listType, @NotBlank String value) {
    }
}

@RestControllerAdvice
class FraudExceptionHandler {

    @ExceptionHandler(FraudException.class)
    public ResponseEntity<ProblemBody> handle(FraudException exception) {
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
