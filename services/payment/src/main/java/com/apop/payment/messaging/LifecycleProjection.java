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
@Table(name = "lifecycle_projection")
public class LifecycleProjection implements Persistable<UUID> {

    @Id
    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "event_name", nullable = false, length = 64)
    private String eventName;

    @Column(name = "status", nullable = false, length = 32)
    private String status;

    @Column(name = "previous_status", length = 32)
    private String previousStatus;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Transient
    private boolean newlyCreated = true;

    protected LifecycleProjection() {
    }

    static LifecycleProjection from(LifecycleEnvelope envelope) {
        LifecycleProjection projection = new LifecycleProjection();
        LifecycleEnvelope.LifecycleData data = envelope.data();
        projection.eventId = envelope.id();
        projection.paymentId = data.paymentId();
        projection.eventName = data.eventName().name();
        projection.status = data.status();
        projection.previousStatus = data.previousStatus();
        projection.occurredAt = envelope.time();
        return projection;
    }

    public UUID getEventId() {
        return eventId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getEventName() {
        return eventName;
    }

    public String getStatus() {
        return status;
    }

    public String getPreviousStatus() {
        return previousStatus;
    }

    public Instant getOccurredAt() {
        return occurredAt;
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
