package com.apop.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_assessment")
public class FraudAssessment implements Persistable<UUID> {

    @Id
    @Column(name = "assessment_id")
    private UUID assessmentId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "debtor_account_id", nullable = false)
    private UUID debtorAccountId;

    @Column(name = "policy_version", nullable = false)
    private int policyVersion;

    @Column(name = "decision", nullable = false, length = 16)
    private String decision;

    @Column(name = "score", nullable = false)
    private int score;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "feature_snapshot", nullable = false)
    private String featureSnapshot;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean newlyCreated = true;

    protected FraudAssessment() {
    }

    public static FraudAssessment create(
            UUID assessmentId,
            UUID paymentId,
            String idempotencyKey,
            UUID debtorAccountId,
            int policyVersion,
            String decision,
            int score,
            String featureSnapshot,
            Instant createdAt) {
        FraudAssessment assessment = new FraudAssessment();
        assessment.assessmentId = assessmentId;
        assessment.paymentId = paymentId;
        assessment.idempotencyKey = idempotencyKey;
        assessment.debtorAccountId = debtorAccountId;
        assessment.policyVersion = policyVersion;
        assessment.decision = decision;
        assessment.score = score;
        assessment.featureSnapshot = featureSnapshot;
        assessment.createdAt = createdAt;
        return assessment;
    }

    public UUID getAssessmentId() {
        return assessmentId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public UUID getDebtorAccountId() {
        return debtorAccountId;
    }

    public int getPolicyVersion() {
        return policyVersion;
    }

    public String getDecision() {
        return decision;
    }

    public int getScore() {
        return score;
    }

    public String getFeatureSnapshot() {
        return featureSnapshot;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    @Override
    public UUID getId() {
        return assessmentId;
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
