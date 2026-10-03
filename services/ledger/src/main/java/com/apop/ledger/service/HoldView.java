package com.apop.ledger.service;

import com.apop.ledger.domain.HoldStatus;

import java.time.Instant;
import java.util.UUID;

public record HoldView(
        UUID holdId,
        UUID accountId,
        UUID paymentId,
        String idempotencyKey,
        long amountMinor,
        String currency,
        HoldStatus status,
        String outcome,
        String reasonCode,
        boolean replayed,
        long ledgerBalanceMinor,
        long heldMinor,
        long availableMinor,
        Instant expiresAt,
        Instant createdAt,
        Instant releasedAt
) {
}
