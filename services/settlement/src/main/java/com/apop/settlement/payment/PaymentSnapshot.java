package com.apop.settlement.payment;

import java.time.LocalDate;
import java.util.UUID;

public record PaymentSnapshot(
        UUID paymentId,
        String status,
        String rail,
        UUID debtorAccountId,
        UUID creditorAccountId,
        long amountMinor,
        String currency,
        LocalDate valueDate,
        UUID holdId,
        UUID journalEntryId
) {
}
