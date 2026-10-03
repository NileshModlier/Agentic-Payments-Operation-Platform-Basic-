package com.apop.payment.validation;

import com.apop.payment.domain.Rail;

import java.time.LocalDate;
import java.util.UUID;

public record ValidationRequest(
        UUID paymentId,
        Rail rail,
        long amountMinor,
        String currency,
        UUID debtorAccountId,
        UUID creditorAccountId,
        String creditorName,
        LocalDate requestedValueDate
) {
}
