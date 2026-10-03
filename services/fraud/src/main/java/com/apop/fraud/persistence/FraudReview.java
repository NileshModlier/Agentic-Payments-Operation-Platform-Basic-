package com.apop.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "fraud_review")
public class FraudReview implements Persistable<UUID> {

    @Id
    @Column(name = "review_id")
    private UUID reviewId;

    @Column(name = "assessment_id", nullable = false)
    private UUID assessmentId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "decision", length = 32)
    private String decision;

    @Column(name = "rationale", length = 500)
    private String rationale;

    @Column(name = "submitted_by", nullable = false, length = 64)
    private String submittedBy;

    @Column(name = "decided_by", length = 64)
    private String decidedBy;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Transient
    private boolean newlyCreated = true;

    protected FraudReview() {
    }

    public static FraudReview open(UUID reviewId, UUID assessmentId, UUID paymentId, String submittedBy, Instant openedAt, Instant expiresAt) {
        FraudReview review = new FraudReview();
        review.reviewId = reviewId;
        review.assessmentId = assessmentId;
        review.paymentId = paymentId;
        review.status = "OPEN";
        review.submittedBy = submittedBy;
        review.openedAt = openedAt;
        review.expiresAt = expiresAt;
        return review;
    }

    public void decide(String decision, String rationale, String actor, Instant decidedAt) {
        this.decision = decision;
        this.rationale = rationale;
        this.decidedBy = actor;
        this.decidedAt = decidedAt;
        this.status = "ESCALATE".equals(decision) ? "ESCALATED" : "DECIDED";
        if ("CONFIRM_DECLINE".equals(decision)) {
            this.reasonCode = "FRAUD_DECLINE";
        }
    }

    public void expire(Instant decidedAt) {
        this.status = "EXPIRED";
        this.reasonCode = "FRAUD_REVIEW_EXPIRED";
        this.decidedAt = decidedAt;
    }

    public UUID getReviewId() {
        return reviewId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getStatus() {
        return status;
    }

    public String getDecision() {
        return decision;
    }

    public String getRationale() {
        return rationale;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public boolean isOpenForDecision() {
        return "OPEN".equals(status) || "ESCALATED".equals(status);
    }

    @Override
    public UUID getId() {
        return reviewId;
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
