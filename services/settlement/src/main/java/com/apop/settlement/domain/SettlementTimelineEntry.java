package com.apop.settlement.domain;

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
@Table(name = "settlement_timeline")
public class SettlementTimelineEntry implements Persistable<UUID> {

    @Id
    @Column(name = "timeline_id", nullable = false)
    private UUID timelineId;

    @Column(name = "settlement_item_id", nullable = false)
    private UUID settlementItemId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "event_type", nullable = false, length = 64)
    private String eventType;

    @Column(name = "item_status", length = 24)
    private String itemStatus;

    @Column(name = "summary_code", nullable = false, length = 64)
    private String summaryCode;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Transient
    private boolean newlyCreated = true;

    protected SettlementTimelineEntry() {
    }

    public static SettlementTimelineEntry record(
            UUID settlementItemId,
            UUID paymentId,
            String eventType,
            String itemStatus,
            String summaryCode,
            Instant occurredAt) {
        SettlementTimelineEntry entry = new SettlementTimelineEntry();
        entry.timelineId = UuidV7.generate();
        entry.settlementItemId = settlementItemId;
        entry.paymentId = paymentId;
        entry.eventType = eventType;
        entry.itemStatus = itemStatus;
        entry.summaryCode = summaryCode;
        entry.occurredAt = occurredAt;
        return entry;
    }

    public String getEventType() {
        return eventType;
    }

    public String getItemStatus() {
        return itemStatus;
    }

    public String getSummaryCode() {
        return summaryCode;
    }

    public Instant getOccurredAt() {
        return occurredAt;
    }

    @Override
    public UUID getId() {
        return timelineId;
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
