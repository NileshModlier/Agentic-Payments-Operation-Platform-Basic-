package com.apop.payment.service;

import com.apop.payment.authorization.LedgerAuthorization;
import com.apop.payment.domain.IdempotencyId;
import com.apop.payment.domain.IdempotencyRecord;
import com.apop.payment.domain.OrchestratorStep;
import com.apop.payment.domain.PartyRole;
import com.apop.payment.domain.Payment;
import com.apop.payment.domain.PaymentException;
import com.apop.payment.domain.PaymentParty;
import com.apop.payment.domain.PaymentStatus;
import com.apop.payment.domain.StepName;
import com.apop.payment.domain.StepStatus;
import com.apop.payment.domain.UuidV7;
import com.apop.payment.fraud.FraudDecision;
import com.apop.payment.fraud.FraudOutcome;
import com.apop.payment.fraud.FraudPort;
import com.apop.payment.fraud.FraudRequest;
import com.apop.payment.fraud.FraudUnavailableException;
import com.apop.payment.ledger.LedgerBusinessException;
import com.apop.payment.ledger.LedgerUnavailableException;
import com.apop.payment.messaging.LifecycleEventName;
import com.apop.payment.messaging.LifecycleOutbox;
import com.apop.payment.persistence.IdempotencyRecordRepository;
import com.apop.payment.persistence.OrchestratorStepRepository;
import com.apop.payment.persistence.PaymentPartyRepository;
import com.apop.payment.persistence.PaymentRepository;
import com.apop.payment.validation.ValidationOutcome;
import com.apop.payment.validation.ValidationPort;
import com.apop.observability.ApopMeters;
import com.apop.payment.validation.ValidationRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Service
class PaymentServiceImpl implements PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentServiceImpl.class);

    private final PaymentRepository payments;
    private final PaymentPartyRepository parties;
    private final IdempotencyRecordRepository idempotency;
    private final OrchestratorStepRepository steps;
    private final ValidationPort validation;
    private final FraudPort fraud;
    private final LedgerAuthorization authorization;
    private final RemittanceSealer sealer;
    private final IdempotentTransaction transactions;
    private final LifecycleOutbox lifecycle;
    private final ApopMeters meters;
    private final Clock clock;

    PaymentServiceImpl(
            PaymentRepository payments,
            PaymentPartyRepository parties,
            IdempotencyRecordRepository idempotency,
            OrchestratorStepRepository steps,
            ValidationPort validation,
            FraudPort fraud,
            LedgerAuthorization authorization,
            RemittanceSealer sealer,
            IdempotentTransaction transactions,
            LifecycleOutbox lifecycle,
            ApopMeters meters,
            Clock clock) {
        this.payments = payments;
        this.parties = parties;
        this.idempotency = idempotency;
        this.steps = steps;
        this.validation = validation;
        this.fraud = fraud;
        this.authorization = authorization;
        this.sealer = sealer;
        this.transactions = transactions;
        this.lifecycle = lifecycle;
        this.meters = meters;
        this.clock = clock;
    }

    @Override
    public PaymentSubmission create(CreatePaymentCommand command) {
        if (command.idempotencyKey() == null || command.idempotencyKey().isBlank()) {
            throw PaymentException.idempotencyKeyRequired();
        }
        String hash = RequestHasher.hash(command);
        Inserted inserted = transactions.execute(() -> insertNew(command, hash), () -> recover(command, hash));
        MDC.put("paymentId", inserted.paymentId().toString());
        try {
            PaymentView view = advance(inserted.paymentId());
            if (inserted.created()) {
                meters.paymentCreated(view.rail().name());
                meters.paymentDecision(view.rail().name(), view.status().name(), view.reasonCode());
                if (view.reasonCode() != null) {
                    MDC.put("reasonCode", view.reasonCode());
                }
            } else {
                meters.idempotencyReplay("replay");
            }
            return new PaymentSubmission(view, inserted.created());
        } finally {
            MDC.remove("paymentId");
            MDC.remove("reasonCode");
        }
    }

    @Override
    @Transactional(readOnly = true)
    public PaymentView get(UUID paymentId) {
        return toView(payments.findById(paymentId).orElseThrow(PaymentException::notFound));
    }

    @Override
    public PaymentView applySettlement(UUID paymentId, SettlementUpdate update) {
        if (update == null || update.targetStatus() == null) {
            throw PaymentException.illegal("Settlement target status is required");
        }
        return transactions.execute(() -> switch (update.targetStatus()) {
            case SETTLEMENT_PENDING -> toView(markPending(load(paymentId)));
            case SETTLED -> toView(markSettled(load(paymentId), update.journalEntryId()));
            case SETTLEMENT_FAILED -> toView(markSettlementFailed(load(paymentId), update.reasonCode()));
            default -> throw PaymentException.illegal("Unsupported settlement status");
        });
    }

    private Inserted insertNew(CreatePaymentCommand command, String hash) {
        Optional<IdempotencyRecord> existing = idempotency.findById(new IdempotencyId(command.legalEntityId(), command.idempotencyKey()));
        if (existing.isPresent()) {
            return replay(existing.get(), hash);
        }
        Instant now = clock.instant();
        UUID paymentId = UuidV7.generate();
        RemittanceSealer.Sealed remittance = sealer.seal(command.remittance());
        Payment payment = Payment.receive(
                paymentId,
                command.legalEntityId(),
                command.idempotencyKey(),
                command.endToEndId(),
                command.rail(),
                command.amountMinor(),
                command.currency(),
                command.debtorAccountId(),
                command.creditorAccountId(),
                command.requestedValueDate(),
                remittance.ciphertext(),
                remittance.keyVersion(),
                command.initiatedBy(),
                command.correlationId(),
                now);
        payments.saveAndFlush(payment);
        parties.save(debtor(command, paymentId));
        parties.save(creditor(command, paymentId));
        idempotency.saveAndFlush(IdempotencyRecord.create(
                command.legalEntityId(),
                command.idempotencyKey(),
                hash,
                paymentId,
                now,
                now.plus(Duration.ofHours(24))));
        lifecycle.append(payment, LifecycleEventName.PaymentReceived, null, null);
        log.info("payment.received paymentId={} correlationId={}", paymentId, MDC.get("correlationId"));
        return new Inserted(paymentId, true);
    }

    private Inserted recover(CreatePaymentCommand command, String hash) {
        return idempotency.findById(new IdempotencyId(command.legalEntityId(), command.idempotencyKey()))
                .map(record -> replay(record, hash))
                .orElseGet(() -> {
                    if (payments.findByLegalEntityIdAndDebtorAccountIdAndEndToEndId(
                            command.legalEntityId(), command.debtorAccountId(), command.endToEndId()).isPresent()) {
                        throw PaymentException.duplicateInstruction();
                    }
                    throw PaymentException.conflict("Payment idempotency key already exists");
                });
    }

    private Inserted replay(IdempotencyRecord record, String hash) {
        if (!record.getRequestHash().equals(hash)) {
            meters.idempotencyReplay("conflict");
            throw PaymentException.conflict("Idempotency key was already used for a different payment");
        }
        return new Inserted(record.getPaymentId(), false);
    }

    private PaymentView advance(UUID paymentId) {
        Payment payment = load(paymentId);
        if (payment.getStatus() == PaymentStatus.RECEIVED) {
            payment = validate(payment);
        }
        if (payment.getStatus() == PaymentStatus.VALIDATED) {
            payment = screenAndAuthorize(payment);
        }
        return toView(payment);
    }

    private Payment validate(Payment payment) {
        ValidationOutcome outcome;
        try {
            outcome = validation.validate(new ValidationRequest(
                    payment.getPaymentId(),
                    payment.getRail(),
                    payment.getAmountMinor(),
                    payment.getCurrency(),
                    payment.getDebtorAccountId(),
                    payment.getCreditorAccountId(),
                    creditorName(payment.getPaymentId()),
                    payment.getRequestedValueDate()));
        } catch (LedgerUnavailableException exception) {
            return fail(payment.getPaymentId(), StepName.VALIDATE, "LEDGER_UNAVAILABLE");
        }
        return transactions.execute(() -> {
            Payment current = load(payment.getPaymentId());
            if (current.getStatus() != PaymentStatus.RECEIVED) {
                return current;
            }
            Instant now = clock.instant();
            if (outcome.passed()) {
                String previous = current.getStatus().name();
                current.markValidated(outcome.valueDate());
                OrchestratorStep step = steps.save(OrchestratorStep.finish(
                        current.getPaymentId(), StepName.VALIDATE, StepStatus.SUCCEEDED, outcome.validationId(), null, now));
                lifecycle.append(current, LifecycleEventName.PaymentValidated, previous, step.getId());
            } else {
                current.markValidationFailed(outcome.reasonCode());
                steps.save(OrchestratorStep.finish(
                        current.getPaymentId(),
                        StepName.VALIDATE,
                        StepStatus.BUSINESS_REJECTED,
                        outcome.validationId(),
                        outcome.reasonCode(),
                        now));
            }
            log.info("payment.validated paymentId={} status={} correlationId={}",
                    current.getPaymentId(), current.getStatus(), MDC.get("correlationId"));
            return payments.save(current);
        });
    }

    private Payment screenAndAuthorize(Payment payment) {
        Optional<OrchestratorStep> recorded = steps.findByPaymentIdAndStepName(payment.getPaymentId(), StepName.FRAUD);
        if (recorded.isEmpty() || recorded.get().getStepStatus() == StepStatus.FAILED) {
            FraudDecision decision;
            try {
                decision = fraud.assess(new FraudRequest(
                        payment.getPaymentId(),
                        payment.getAmountMinor(),
                        payment.getCurrency(),
                        payment.getDebtorAccountId()));
            } catch (FraudUnavailableException exception) {
                return fail(payment.getPaymentId(), StepName.FRAUD, "FRAUD_UNAVAILABLE");
            }
            if (decision.outcome() != FraudOutcome.APPROVE) {
                return rejectFraud(payment.getPaymentId(), decision);
            }
            transactions.execute(() -> {
                if (steps.findByPaymentIdAndStepName(payment.getPaymentId(), StepName.FRAUD).isEmpty()) {
                    steps.save(OrchestratorStep.finish(
                            payment.getPaymentId(),
                            StepName.FRAUD,
                            StepStatus.SUCCEEDED,
                            decision.assessmentId(),
                            decision.outcome().name(),
                            clock.instant()));
                }
                return null;
            });
        }
        return authorize(payment.getPaymentId());
    }

    private Payment rejectFraud(UUID paymentId, FraudDecision decision) {
        return transactions.execute(() -> {
            Payment current = load(paymentId);
            if (current.getStatus().isDecided()) {
                return current;
            }
            if (decision.outcome() == FraudOutcome.REVIEW) {
                current.markFraudReview(decision.reasonCode() == null ? "FRAUD_REVIEW" : decision.reasonCode());
            } else {
                current.markFraudDeclined(decision.reasonCode() == null ? "FRAUD_DECLINED" : decision.reasonCode());
            }
            steps.save(OrchestratorStep.finish(
                    paymentId,
                    StepName.FRAUD,
                    StepStatus.BUSINESS_REJECTED,
                    decision.assessmentId(),
                    current.getReasonCode(),
                    clock.instant()));
            return payments.save(current);
        });
    }

    private Payment authorize(UUID paymentId) {
        Optional<OrchestratorStep> recorded = steps.findByPaymentIdAndStepName(paymentId, StepName.AUTHORIZE);
        if (recorded.isPresent()
                && recorded.get().getStepStatus() == StepStatus.SUCCEEDED
                && recorded.get().getDownstreamRef() != null) {
            return rememberHold(paymentId, recorded.get().getDownstreamRef());
        }
        Payment payment = load(paymentId);
        try {
            UUID holdId = authorization.placeHold(payment, clock);
            return rememberHold(paymentId, holdId);
        } catch (LedgerBusinessException exception) {
            return transactions.execute(() -> {
                Payment current = load(paymentId);
                if (current.getStatus().isDecided()) {
                    return current;
                }
                current.markAuthorizationDeclined(exception.reasonCode());
                steps.save(OrchestratorStep.finish(
                        paymentId,
                        StepName.AUTHORIZE,
                        StepStatus.BUSINESS_REJECTED,
                        null,
                        exception.reasonCode(),
                        clock.instant()));
                log.info("payment.authorization-declined paymentId={} reasonCode={} correlationId={}",
                        paymentId, exception.reasonCode(), MDC.get("correlationId"));
                return payments.save(current);
            });
        } catch (LedgerUnavailableException exception) {
            UUID recovered = recoverHold(paymentId);
            if (recovered != null) {
                return rememberHold(paymentId, recovered);
            }
            return fail(paymentId, StepName.AUTHORIZE, "LEDGER_UNAVAILABLE");
        }
    }

    private UUID recoverHold(UUID paymentId) {
        try {
            return authorization.findHold(paymentId);
        } catch (RuntimeException exception) {
            return null;
        }
    }

    private Payment rememberHold(UUID paymentId, UUID holdId) {
        return transactions.execute(() -> {
            Payment current = load(paymentId);
            if (current.getStatus() == PaymentStatus.AUTHORIZED) {
                return current;
            }
            String previous = current.getStatus().name();
            current.markAuthorized(holdId);
            OrchestratorStep step = steps.findByPaymentIdAndStepName(paymentId, StepName.AUTHORIZE)
                    .orElseGet(() -> steps.save(OrchestratorStep.finish(
                            paymentId, StepName.AUTHORIZE, StepStatus.SUCCEEDED, holdId, null, clock.instant())));
            lifecycle.append(current, LifecycleEventName.PaymentAuthorized, previous, step.getId());
            log.info("payment.authorized paymentId={} holdId={} correlationId={}",
                    paymentId, holdId, MDC.get("correlationId"));
            return payments.save(current);
        });
    }

    private Payment fail(UUID paymentId, StepName stepName, String reasonCode) {
        return transactions.execute(() -> {
            Payment current = load(paymentId);
            if (current.getStatus().isDecided()) {
                return current;
            }
            String previous = current.getStatus().name();
            current.markFailed(reasonCode);
            OrchestratorStep step = steps.findByPaymentIdAndStepName(paymentId, stepName)
                    .orElseGet(() -> steps.save(OrchestratorStep.finish(
                            paymentId, stepName, StepStatus.FAILED, null, reasonCode, clock.instant())));
            lifecycle.append(current, LifecycleEventName.PaymentFailed, previous, step.getId());
            log.info("payment.failed paymentId={} reasonCode={} correlationId={}",
                    paymentId, reasonCode, MDC.get("correlationId"));
            return payments.save(current);
        });
    }

    private Payment markPending(Payment current) {
        if (current.getStatus() == PaymentStatus.SETTLEMENT_PENDING
                || current.getStatus() == PaymentStatus.SETTLED
                || current.getStatus() == PaymentStatus.SETTLEMENT_FAILED) {
            return current;
        }
        String previous = current.getStatus().name();
        current.markSettlementPending();
        lifecycle.append(current, LifecycleEventName.PaymentSettlementPending, previous, null);
        log.info("payment.settlement-pending paymentId={} correlationId={}",
                current.getPaymentId(), MDC.get("correlationId"));
        return payments.save(current);
    }

    private Payment markSettled(Payment current, UUID journalEntryId) {
        if (current.getStatus() == PaymentStatus.SETTLED) {
            if (journalEntryId != null && !journalEntryId.equals(current.getJournalEntryId())) {
                throw PaymentException.conflict("Payment is already settled with a different journal entry");
            }
            return current;
        }
        String previous = current.getStatus().name();
        current.markSettled(journalEntryId);
        OrchestratorStep step = steps.findByPaymentIdAndStepName(current.getPaymentId(), StepName.SETTLE)
                .orElseGet(() -> steps.save(OrchestratorStep.finish(
                        current.getPaymentId(),
                        StepName.SETTLE,
                        StepStatus.SUCCEEDED,
                        journalEntryId,
                        null,
                        clock.instant())));
        lifecycle.append(current, LifecycleEventName.PaymentSettled, previous, step.getId());
        log.info("payment.settled paymentId={} journalEntryId={} correlationId={}",
                current.getPaymentId(), journalEntryId, MDC.get("correlationId"));
        return payments.save(current);
    }

    private Payment markSettlementFailed(Payment current, String reasonCode) {
        if (current.getStatus() == PaymentStatus.SETTLEMENT_FAILED) {
            return current;
        }
        String reason = reasonCode == null || reasonCode.isBlank() ? "SETTLEMENT_POST_FAILED" : reasonCode;
        current.markSettlementFailed(reason);
        if (steps.findByPaymentIdAndStepName(current.getPaymentId(), StepName.SETTLE).isEmpty()) {
            steps.save(OrchestratorStep.finish(
                    current.getPaymentId(),
                    StepName.SETTLE,
                    StepStatus.BUSINESS_REJECTED,
                    null,
                    reason,
                    clock.instant()));
        }
        log.info("payment.settlement-failed paymentId={} reasonCode={} correlationId={}",
                current.getPaymentId(), reason, MDC.get("correlationId"));
        return payments.save(current);
    }

    private String creditorName(UUID paymentId) {
        return parties.findByPaymentIdAndRole(paymentId, PartyRole.CREDITOR)
                .map(party -> sealer.open(party.getPartyNameCiphertext()))
                .orElse(null);
    }

    private Payment load(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(PaymentException::notFound);
    }

    private PaymentParty debtor(CreatePaymentCommand command, UUID paymentId) {
        return PaymentParty.create(
                paymentId,
                PartyRole.DEBTOR,
                command.debtorAccountId(),
                null,
                null,
                null,
                null,
                null,
                1,
                null);
    }

    private PaymentParty creditor(CreatePaymentCommand command, UUID paymentId) {
        RemittanceSealer.Sealed name = sealer.seal(command.creditorName());
        RemittanceSealer.Sealed accountNumber = sealer.seal(command.creditorAccountNumber());
        RemittanceSealer.Sealed routing = sealer.seal(command.creditorRoutingNumber());
        int keyVersion = name.keyVersion() == null ? 1 : name.keyVersion();
        return PaymentParty.create(
                paymentId,
                PartyRole.CREDITOR,
                command.creditorAccountId(),
                accountNumber.ciphertext(),
                sealer.last4(command.creditorAccountNumber()),
                command.creditorAccountNumber() == null ? null : RequestHasher.sha256(command.creditorAccountNumber()),
                routing.ciphertext(),
                name.ciphertext(),
                keyVersion,
                command.creditorCountry());
    }

    private static PaymentView toView(Payment payment) {
        return new PaymentView(
                payment.getPaymentId(),
                payment.getEndToEndId(),
                payment.getStatus(),
                payment.getRail(),
                payment.getDebtorAccountId(),
                payment.getCreditorAccountId(),
                new PaymentView.Money(payment.getAmountMinor(), payment.getCurrency()),
                payment.getValueDate(),
                payment.getReasonCode(),
                payment.getCorrelationId(),
                payment.getHoldId(),
                payment.getJournalEntryId(),
                payment.getUpdatedAt());
    }

    private record Inserted(UUID paymentId, boolean created) {
    }
}
