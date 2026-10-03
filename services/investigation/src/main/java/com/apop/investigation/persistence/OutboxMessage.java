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
@Table(name = "outbox")
public class OutboxMessage implements Persistable<UUID> {

    @Id
    private UUID outboxId;
    private String aggregateType;
    private UUID aggregateId;
    private String topic;
    private String recordKey;
    private String eventType;
    private byte[] payload;
    @JdbcTypeCode(SqlTypes.JSON)
    private String headers;
    private Instant createdAt;
    private Instant publishedAt;

    @Transient
    private boolean newlyCreated = true;

    public static OutboxMessage create(
            UUID id,
            UUID aggregateId,
            String topic,
            String eventType,
            byte[] payload,
            String headers,
            Instant createdAt) {
        OutboxMessage row = new OutboxMessage();
        row.outboxId = id;
        row.aggregateType = "investigation-case";
        row.aggregateId = aggregateId;
        row.topic = topic;
        row.recordKey = aggregateId.toString();
        row.eventType = eventType;
        row.payload = payload;
        row.headers = headers;
        row.createdAt = createdAt;
        row.newlyCreated = true;
        return row;
    }

    @Override
    public UUID getId() {
        return outboxId;
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

    public byte[] getPayload() {
        return payload;
    }

    public String getEventType() {
        return eventType;
    }

    public String getTopic() {
        return topic;
    }
}
