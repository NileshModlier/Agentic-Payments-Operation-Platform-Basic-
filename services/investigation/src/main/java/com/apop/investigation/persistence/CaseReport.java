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
@Table(name = "case_report")
public class CaseReport implements Persistable<UUID> {

    @Id
    private UUID reportId;
    private UUID caseId;
    private UUID sessionId;
    private String draft;
    private String publishedBy;
    private Instant publishedAt;
    private Instant createdAt;

    @Transient
    private boolean newlyCreated = true;

    public static CaseReport create(UUID id, UUID caseId, UUID sessionId, String draft, Instant createdAt) {
        CaseReport row = new CaseReport();
        row.reportId = id;
        row.caseId = caseId;
        row.sessionId = sessionId;
        row.draft = draft;
        row.createdAt = createdAt;
        row.newlyCreated = true;
        return row;
    }

    @Override
    public UUID getId() {
        return reportId;
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

    public UUID getReportId() {
        return reportId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getDraft() {
        return draft;
    }

    public String getPublishedBy() {
        return publishedBy;
    }

    public void publish(String actor, Instant when) {
        this.publishedBy = actor;
        this.publishedAt = when;
    }
}
