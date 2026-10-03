package com.apop.payment.api;

import com.apop.payment.config.PaymentProperties;
import com.apop.payment.domain.PaymentException;
import com.apop.payment.domain.PaymentStatus;
import com.apop.payment.domain.Rail;
import com.apop.payment.service.CreatePaymentCommand;
import com.apop.payment.service.PaymentService;
import com.apop.payment.service.PaymentSubmission;
import com.apop.payment.service.PaymentView;
import com.apop.payment.service.SettlementUpdate;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.MDC;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

@RestController
public class PaymentController {

    private final PaymentService payments;
    private final PaymentProperties properties;

    public PaymentController(PaymentService payments, PaymentProperties properties) {
        this.payments = payments;
        this.properties = properties;
    }

    @PostMapping("/v1/payments")
    public ResponseEntity<PaymentView> create(
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestHeader(value = "X-Legal-Entity", required = false) String legalEntity,
            @RequestHeader(value = "X-Initiated-By", required = false) String initiatedBy,
            @Valid @RequestBody CreatePaymentRequest request) {
        if (idempotencyKey.isBlank()) {
            throw PaymentException.idempotencyKeyRequired();
        }
        String entity = legalEntity == null || legalEntity.isBlank()
                ? properties.defaultLegalEntity()
                : legalEntity;
        String actor = initiatedBy == null || initiatedBy.isBlank() ? "channel" : initiatedBy;
        String correlation = MDC.get("correlationId");
        UUID correlationId = correlationId(correlation);
        PaymentSubmission submission = payments.create(request.toCommand(
                entity,
                idempotencyKey,
                actor,
                correlationId));
        URI location = URI.create("/v1/payments/" + submission.payment().paymentId());
        if (submission.created()) {
            return ResponseEntity.created(location).body(submission.payment());
        }
        return ResponseEntity.ok(submission.payment());
    }

    @GetMapping("/v1/payments/{paymentId}")
    public PaymentView get(@PathVariable UUID paymentId) {
        return payments.get(paymentId);
    }

    @PostMapping("/internal/v1/payments/{paymentId}/settlement")
    public PaymentView settle(
            @PathVariable UUID paymentId,
            @Valid @RequestBody SettlementProgress request) {
        return payments.applySettlement(paymentId, request.toUpdate());
    }

    private static UUID correlationId(String correlation) {
        if (correlation == null || correlation.isBlank()) {
            return UUID.randomUUID();
        }
        try {
            return UUID.fromString(correlation);
        } catch (IllegalArgumentException exception) {
            return UUID.nameUUIDFromBytes(correlation.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        }
    }

    public record CreatePaymentRequest(
            @NotBlank @Size(max = 35) String endToEndId,
            @NotNull Rail rail,
            @NotNull @Valid MoneyRequest amount,
            @NotNull UUID debtorAccountId,
            @Valid CreditorRequest creditor,
            @Size(max = 140) String remittance,
            @NotNull LocalDate requestedValueDate
    ) {
        CreatePaymentCommand toCommand(String legalEntityId, String idempotencyKey, String initiatedBy, UUID correlationId) {
            CreditorRequest party = creditor == null ? new CreditorRequest(null, null, null, null, null) : creditor;
            return new CreatePaymentCommand(
                    legalEntityId,
                    idempotencyKey,
                    endToEndId,
                    rail,
                    amount.minor(),
                    amount.currency(),
                    debtorAccountId,
                    party.accountId(),
                    party.name(),
                    party.accountNumber(),
                    party.routingNumber(),
                    party.country(),
                    remittance,
                    requestedValueDate,
                    initiatedBy,
                    correlationId);
        }
    }

    public record MoneyRequest(
            @Positive long minor,
            @NotBlank @Pattern(regexp = "[A-Z]{3}") String currency
    ) {
    }

    public record CreditorRequest(
            UUID accountId,
            @Size(max = 140) String name,
            @Size(max = 34) String accountNumber,
            @Size(max = 34) String routingNumber,
            @Pattern(regexp = "[A-Z]{2}") String country
    ) {
    }

    public record SettlementProgress(
            @NotNull PaymentStatus targetStatus,
            UUID journalEntryId,
            @Size(max = 64) String reasonCode
    ) {
        SettlementUpdate toUpdate() {
            return new SettlementUpdate(targetStatus, journalEntryId, reasonCode);
        }
    }
}
