package com.apop.ledger.service;

import com.apop.ledger.domain.Direction;
import com.apop.ledger.domain.EntryType;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PostingView(
        UUID journalEntryId,
        UUID paymentId,
        String idempotencyKey,
        EntryType entryType,
        UUID reversalOfEntryId,
        LocalDate valueDate,
        Instant postedAt,
        String outcome,
        boolean replayed,
        List<LineView> lines
) {
    public record LineView(
            UUID journalLineId,
            UUID accountId,
            Direction direction,
            long amountMinor,
            String currency
    ) {
    }
}
