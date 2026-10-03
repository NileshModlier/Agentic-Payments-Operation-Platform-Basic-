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
@Table(name = "agent_tool_call")
public class AgentToolCall implements Persistable<UUID> {

    @Id
    private UUID toolCallId;
    private UUID sessionId;
    private String toolName;
    @JdbcTypeCode(SqlTypes.CHAR)
    private String requestHash;
    private String responseCode;
    private Instant calledAt;

    @Transient
    private boolean newlyCreated = true;

    public static AgentToolCall create(UUID id, UUID sessionId, String toolName, String requestHash, String responseCode, Instant calledAt) {
        AgentToolCall row = new AgentToolCall();
        row.toolCallId = id;
        row.sessionId = sessionId;
        row.toolName = toolName;
        row.requestHash = requestHash;
        row.responseCode = responseCode;
        row.calledAt = calledAt;
        row.newlyCreated = true;
        return row;
    }

    @Override
    public UUID getId() {
        return toolCallId;
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

    public String getToolName() {
        return toolName;
    }

    public String getResponseCode() {
        return responseCode;
    }

    public UUID getSessionId() {
        return sessionId;
    }
}
