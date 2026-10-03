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

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "audit_event")
public class AuditEvent implements Persistable<UUID> {

    @Id
    private UUID auditId;
    private String actor;
    private String action;
    private String resourceType;
    private String resourceId;
    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;
    private Instant createdAt;

    @Transient
    private boolean newlyCreated = true;

    public static AuditEvent create(UUID id, String actor, String action, String resourceType, String resourceId, String detail, Instant createdAt) {
        AuditEvent row = new AuditEvent();
        row.auditId = id;
        row.actor = actor;
        row.action = action;
        row.resourceType = resourceType;
        row.resourceId = resourceId;
        row.detail = detail;
        row.createdAt = createdAt;
        row.newlyCreated = true;
        return row;
    }

    @Override
    public UUID getId() {
        return auditId;
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

    public String getAction() {
        return action;
    }

    public String getDetail() {
        return detail;
    }

    public String getActor() {
        return actor;
    }
}
