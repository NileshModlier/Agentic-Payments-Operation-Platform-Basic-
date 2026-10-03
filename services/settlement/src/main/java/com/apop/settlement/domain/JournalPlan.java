package com.apop.settlement.domain;

import java.util.List;
import java.util.UUID;

public final class JournalPlan {

    private JournalPlan() {
    }

    public static List<JournalLine> lines(
            String rail,
            UUID debtorAccountId,
            UUID creditorAccountId,
            UUID clearingAccountId,
            long amountMinor,
            String currency) {
        if (debtorAccountId == null || amountMinor <= 0 || currency == null || currency.isBlank()) {
            throw new IllegalArgumentException("Settlement journal is incomplete");
        }
        UUID creditAccount = "BOOK".equals(rail) ? creditorAccountId : clearingAccountId;
        if (creditAccount == null) {
            throw new IllegalArgumentException("Settlement has no credit account");
        }
        return List.of(
                new JournalLine(debtorAccountId, "DEBIT", amountMinor, currency),
                new JournalLine(creditAccount, "CREDIT", amountMinor, currency));
    }
}
