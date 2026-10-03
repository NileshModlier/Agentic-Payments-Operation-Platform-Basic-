package com.apop.investigation.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "agent_recommendation")
public class AgentRecommendation implements Persistable<UUID> {

    @Id
    private UUID recommendationId;
    private UUID caseId;
    private UUID sessionId;
    private String disposition;
    private BigDecimal confidence;
    @JdbcTypeCode(SqlTypes.JSON)
    private String evidenceIds;
    private String narrative;
    private String policyVersion;
    private Instant createdAt;

    @Transient
    private boolean newlyCreated = true;

    public static AgentRecommendation create(
            UUID id,
            UUID caseId,
            UUID sessionId,
            String disposition,
            BigDecimal confidence,
            String evidenceIds,
            String narrative,
            Instant createdAt) {
        AgentRecommendation row = new AgentRecommendation();
        row.recommendationId = id;
        row.caseId = caseId;
        row.sessionId = sessionId;
        row.disposition = disposition;
        row.confidence = confidence;
        row.evidenceIds = evidenceIds;
        row.narrative = narrative;
        row.policyVersion = com.apop.investigation.domain.PolicyGuard.POLICY_VERSION;
        row.createdAt = createdAt;
        row.newlyCreated = true;
        return row;
    }

    @Override
    public UUID getId() {
        return recommendationId;
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

    public UUID getRecommendationId() {
        return recommendationId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getSessionId() {
        return sessionId;
    }

    public String getDisposition() {
        return disposition;
    }

    public BigDecimal getConfidence() {
        return confidence;
    }

    public String getEvidenceIds() {
        return evidenceIds;
    }

    public String getNarrative() {
        return narrative;
    }

    public String getPolicyVersion() {
        return policyVersion;
    }
}
