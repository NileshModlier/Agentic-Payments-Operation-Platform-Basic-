package com.apop.settlement.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record SettlementView(
        UUID paymentId,
        UUID settlementItemId,
        UUID batchId,
        String itemStatus,
        UUID journalEntryId,
        long amountMinor,
        String currency,
        String reasonCode,
        List<TimelineEntry> timeline
) {
    public record TimelineEntry(String eventType, String itemStatus, String summaryCode, Instant occurredAt) {
    }
}
