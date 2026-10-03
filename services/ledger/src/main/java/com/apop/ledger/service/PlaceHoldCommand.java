package com.apop.ledger.service;

import java.time.Instant;
import java.util.UUID;

public record PlaceHoldCommand(
        String idempotencyKey,
        UUID paymentId,
        UUID accountId,
        long amountMinor,
        String currency,
        Instant expiresAt
) {
}
