package com.apop.investigation.persistence;

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
@Table(name = "investigation_case")
public class InvestigationCase implements Persistable<UUID> {

    @Id
    private UUID caseId;
    private UUID paymentId;
    private String source;
    private UUID sourceRef;
    private String caseStatus;
    private Instant openedAt;
    private Instant closedAt;
    private String submittedBy;
    private String severity;
    @Column(name = "queue_name")
    private String queueName;
    private long amountMinor;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String currency;
    @JdbcTypeCode(SqlTypes.JSON)
    private String evidence;
    private String escalatedBy;

    @Transient
    private boolean newlyCreated = true;

    public static InvestigationCase open(
            UUID caseId,
            UUID paymentId,
            String source,
            UUID sourceRef,
            String submittedBy,
            String severity,
            long amountMinor,
            String currency,
            String evidence,
            Instant openedAt) {
        InvestigationCase row = new InvestigationCase();
        row.caseId = caseId;
        row.paymentId = paymentId;
        row.source = source;
        row.sourceRef = sourceRef;
        row.caseStatus = "OPEN";
        row.openedAt = openedAt;
        row.submittedBy = submittedBy;
        row.severity = severity;
        row.queueName = "PRIMARY";
        row.amountMinor = amountMinor;
        row.currency = currency;
        row.evidence = evidence;
        row.newlyCreated = true;
        return row;
    }

    @Override
    public UUID getId() {
        return caseId;
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

    public UUID getCaseId() {
        return caseId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getSource() {
        return source;
    }

    public UUID getSourceRef() {
        return sourceRef;
    }

    public String getCaseStatus() {
        return caseStatus;
    }

    public void setCaseStatus(String caseStatus) {
        this.caseStatus = caseStatus;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getClosedAt() {
        return closedAt;
    }

    public void setClosedAt(Instant closedAt) {
        this.closedAt = closedAt;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public String getSeverity() {
        return severity;
    }

    public String getQueueName() {
        return queueName;
    }

    public void setQueueName(String queueName) {
        this.queueName = queueName;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency == null ? null : currency.trim();
    }

    public String getEvidence() {
        return evidence;
    }

    public String getEscalatedBy() {
        return escalatedBy;
    }

    public void setEscalatedBy(String escalatedBy) {
        this.escalatedBy = escalatedBy;
    }
}
