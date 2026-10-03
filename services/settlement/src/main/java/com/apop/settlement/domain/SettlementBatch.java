package com.apop.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "settlement_batch")
public class SettlementBatch implements Persistable<UUID> {

    @Id
    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "rail", nullable = false, length = 24)
    private String rail;

    @Column(name = "window_date", nullable = false)
    private LocalDate windowDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private BatchStatus status;

    @Column(name = "item_count", nullable = false)
    private int itemCount;

    @Column(name = "total_minor", nullable = false)
    private long totalMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "locked_at")
    private Instant lockedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Transient
    private boolean newlyCreated = true;

    protected SettlementBatch() {
    }

    public static SettlementBatch lock(String rail, LocalDate windowDate, long totalMinor, String currency, Instant lockedAt) {
        SettlementBatch batch = new SettlementBatch();
        batch.batchId = UuidV7.generate();
        batch.rail = rail;
        batch.windowDate = windowDate;
        batch.status = BatchStatus.LOCKED;
        batch.itemCount = 1;
        batch.totalMinor = totalMinor;
        batch.currency = currency;
        batch.lockedAt = lockedAt;
        batch.createdAt = lockedAt;
        return batch;
    }

    public void markPosted() {
        this.status = BatchStatus.POSTED;
    }

    public void markFailed() {
        this.status = BatchStatus.FAILED;
    }

    public UUID getBatchId() {
        return batchId;
    }

    @Override
    public UUID getId() {
        return batchId;
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
