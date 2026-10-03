package com.apop.payment.ledger;

import java.util.UUID;

public interface LedgerClient {

    AccountSnapshot getAccount(UUID accountId);

    HoldSnapshot placeHold(HoldRequest request);

    HoldSnapshot findHold(UUID paymentId);

    record AccountSnapshot(UUID accountId, String currency, String status) {
    }

    record HoldRequest(
            String idempotencyKey,
            UUID paymentId,
            UUID accountId,
            long amountMinor,
            String currency,
            java.time.Instant expiresAt
    ) {
    }

    record HoldSnapshot(UUID holdId, String status) {
    }
}
