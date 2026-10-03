package com.apop.payment.messaging;

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
@Table(name = "processed_event")
public class ProcessedEvent implements Persistable<UUID> {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 160)
    private String eventType;

    @Column(name = "outcome", nullable = false, length = 32)
    private String outcome;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;

    @Transient
    private boolean newlyCreated = true;

    protected ProcessedEvent() {
    }

    static ProcessedEvent applied(UUID eventId, String eventType, Instant processedAt) {
        return create(eventId, eventType, "APPLIED", processedAt);
    }

    static ProcessedEvent deadLettered(UUID eventId, String eventType, Instant processedAt) {
        return create(eventId, eventType, "DEAD_LETTERED", processedAt);
    }

    private static ProcessedEvent create(UUID eventId, String eventType, String outcome, Instant processedAt) {
        ProcessedEvent event = new ProcessedEvent();
        event.eventId = eventId;
        event.eventType = eventType;
        event.outcome = outcome;
        event.processedAt = processedAt;
        return event;
    }

    public UUID getEventId() {
        return eventId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getOutcome() {
        return outcome;
    }

    @Override
    public UUID getId() {
        return eventId;
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
