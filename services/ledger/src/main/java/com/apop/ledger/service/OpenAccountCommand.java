package com.apop.ledger.service;

import com.apop.ledger.domain.AccountRole;
import com.apop.ledger.domain.NormalBalance;

public record OpenAccountCommand(
        String legalEntityId,
        AccountRole accountRole,
        String glCode,
        String accountNumber,
        String currency,
        NormalBalance normalBalance
) {
}
