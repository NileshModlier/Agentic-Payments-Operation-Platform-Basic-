package com.apop.ledger.service;

import com.apop.ledger.domain.Direction;
import com.apop.ledger.domain.EntryType;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public record PostJournalCommand(
        String idempotencyKey,
        UUID paymentId,
        EntryType entryType,
        UUID reversalOfEntryId,
        LocalDate valueDate,
        UUID holdId,
        List<Line> lines
) {
    public record Line(UUID accountId, Direction direction, long amountMinor, String currency) {
    }
}
