package com.apop.ledger.service;

import com.apop.ledger.domain.AccountRole;
import com.apop.ledger.domain.AccountStatus;
import com.apop.ledger.domain.NormalBalance;

import java.time.Instant;
import java.util.UUID;

public record AccountView(
        UUID accountId,
        String legalEntityId,
        AccountRole accountRole,
        String glCode,
        String accountNumberLast4,
        String currency,
        NormalBalance normalBalance,
        AccountStatus status,
        long ledgerBalanceMinor,
        long heldMinor,
        long availableMinor,
        Instant openedAt,
        Instant createdAt,
        Instant updatedAt
) {
}
