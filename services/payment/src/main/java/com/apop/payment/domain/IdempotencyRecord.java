package com.apop.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "idempotency_record")
public class IdempotencyRecord implements Persistable<IdempotencyId> {

    @EmbeddedId
    private IdempotencyId id;

    @Column(name = "request_hash", nullable = false, length = 64)
    private String requestHash;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Transient
    private boolean newlyCreated = true;

    protected IdempotencyRecord() {
    }

    public static IdempotencyRecord create(
            String legalEntityId,
            String idempotencyKey,
            String requestHash,
            UUID paymentId,
            Instant createdAt,
            Instant expiresAt) {
        IdempotencyRecord record = new IdempotencyRecord();
        record.id = new IdempotencyId(legalEntityId, idempotencyKey);
        record.requestHash = requestHash;
        record.paymentId = paymentId;
        record.createdAt = createdAt;
        record.expiresAt = expiresAt;
        return record;
    }

    public String getRequestHash() {
        return requestHash;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    @Override
    public IdempotencyId getId() {
        return id;
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
