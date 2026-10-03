package com.apop.payment.messaging;

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
@Table(name = "outbox")
public class OutboxMessage implements Persistable<UUID> {

    @Id
    @Column(name = "outbox_id", nullable = false)
    private UUID outboxId;

    @Column(name = "aggregate_type", nullable = false, length = 64)
    private String aggregateType;

    @Column(name = "aggregate_id", nullable = false)
    private UUID aggregateId;

    @Column(name = "topic", nullable = false, length = 160)
    private String topic;

    @Column(name = "record_key", nullable = false, length = 80)
    private String recordKey;

    @Column(name = "event_type", nullable = false, length = 160)
    private String eventType;

    @Column(name = "payload", nullable = false)
    private byte[] payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "headers", nullable = false)
    private String headers;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Transient
    private boolean newlyCreated = true;

    protected OutboxMessage() {
    }

    static OutboxMessage create(
            UUID outboxId,
            UUID aggregateId,
            String topic,
            String eventType,
            byte[] payload,
            String headers,
            Instant createdAt) {
        OutboxMessage message = new OutboxMessage();
        message.outboxId = outboxId;
        message.aggregateType = "payment";
        message.aggregateId = aggregateId;
        message.topic = topic;
        message.recordKey = aggregateId.toString();
        message.eventType = eventType;
        message.payload = payload;
        message.headers = headers;
        message.createdAt = createdAt;
        return message;
    }

    void markPublished(Instant publishedAt) {
        this.publishedAt = publishedAt;
    }

    public UUID getOutboxId() {
        return outboxId;
    }

    public UUID getAggregateId() {
        return aggregateId;
    }

    public String getTopic() {
        return topic;
    }

    public String getRecordKey() {
        return recordKey;
    }

    public String getEventType() {
        return eventType;
    }

    public byte[] getPayload() {
        return payload;
    }

    public String getHeaders() {
        return headers;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getPublishedAt() {
        return publishedAt;
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
    void markPersisted() {
        newlyCreated = false;
    }
}
