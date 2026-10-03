package com.apop.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "orchestrator_step")
public class OrchestratorStep implements Persistable<UUID> {

    @Id
    @Column(name = "step_id", nullable = false)
    private UUID stepId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_name", nullable = false, length = 32)
    private StepName stepName;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_status", nullable = false, length = 32)
    private StepStatus stepStatus;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "downstream_ref")
    private UUID downstreamRef;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "finished_at")
    private Instant finishedAt;

    @Transient
    private boolean newlyCreated = true;

    protected OrchestratorStep() {
    }

    public static OrchestratorStep finish(
            UUID paymentId,
            StepName stepName,
            StepStatus stepStatus,
            UUID downstreamRef,
            String reasonCode,
            Instant finishedAt) {
        OrchestratorStep step = new OrchestratorStep();
        step.stepId = UuidV7.generate();
        step.paymentId = paymentId;
        step.stepName = stepName;
        step.stepStatus = stepStatus;
        step.idempotencyKey = stepName.name().toLowerCase() + ":" + paymentId;
        step.downstreamRef = downstreamRef;
        step.reasonCode = reasonCode;
        step.startedAt = finishedAt;
        step.finishedAt = finishedAt;
        return step;
    }

    public StepName getStepName() {
        return stepName;
    }

    public StepStatus getStepStatus() {
        return stepStatus;
    }

    public UUID getDownstreamRef() {
        return downstreamRef;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    @Override
    public UUID getId() {
        return stepId;
    }

    @Override
    public boolean isNew() {
        return newlyCreated;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newlyCreated = false;
    }
}
