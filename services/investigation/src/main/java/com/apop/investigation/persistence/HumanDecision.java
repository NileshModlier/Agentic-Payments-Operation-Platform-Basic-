package com.apop.investigation.persistence;

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
@Table(name = "human_decision")
public class HumanDecision implements Persistable<UUID> {

    @Id
    private UUID decisionId;
    private UUID caseId;
    private UUID recommendationId;
    private String decision;
    private String decisionRole;
    private String decidedBy;
    private Instant decidedAt;
    private String rationale;

    @Transient
    private boolean newlyCreated = true;

    public static HumanDecision create(
            UUID id,
            UUID caseId,
            UUID recommendationId,
            String decision,
            String decisionRole,
            String decidedBy,
            String rationale,
            Instant decidedAt) {
        HumanDecision row = new HumanDecision();
        row.decisionId = id;
        row.caseId = caseId;
        row.recommendationId = recommendationId;
        row.decision = decision;
        row.decisionRole = decisionRole;
        row.decidedBy = decidedBy;
        row.rationale = rationale;
        row.decidedAt = decidedAt;
        row.newlyCreated = true;
        return row;
    }

    @Override
    public UUID getId() {
        return decisionId;
    }

    @Override
    public boolean isNew() {
        return newlyCreated;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        newlyCreated = false;
    }

    public UUID getDecisionId() {
        return decisionId;
    }

    public String getDecision() {
        return decision;
    }

    public String getDecisionRole() {
        return decisionRole;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public String getRationale() {
        return rationale;
    }
}
