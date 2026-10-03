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
@Table(name = "agent_session")
public class AgentSession implements Persistable<UUID> {

    @Id
    private UUID sessionId;
    private UUID caseId;
    private String modelRoute;
    private String status;
    private Instant startedAt;
    private Instant finishedAt;
    private Integer tokenInput;
    private Integer tokenOutput;
    private String agentRole;
    private String guardCheck;
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "memory")
    private String memory;

    @Transient
    private boolean newlyCreated = true;

    public static AgentSession start(UUID sessionId, UUID caseId, String modelRoute, String role, Instant startedAt) {
        AgentSession row = new AgentSession();
        row.sessionId = sessionId;
        row.caseId = caseId;
        row.modelRoute = modelRoute;
        row.status = "RUNNING";
        row.startedAt = startedAt;
        row.agentRole = role;
        row.memory = "[]";
        row.newlyCreated = true;
        return row;
    }

    @Override
    public UUID getId() {
        return sessionId;
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

    public UUID getSessionId() {
        return sessionId;
    }

    public UUID getCaseId() {
        return caseId;
    }

    public String getModelRoute() {
        return modelRoute;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getAgentRole() {
        return agentRole;
    }

    public void setAgentRole(String agentRole) {
        this.agentRole = agentRole;
    }

    public String getGuardCheck() {
        return guardCheck;
    }

    public void setGuardCheck(String guardCheck) {
        this.guardCheck = guardCheck;
    }

    public String getMemory() {
        return memory;
    }

    public void setMemory(String memory) {
        this.memory = memory;
    }
}
