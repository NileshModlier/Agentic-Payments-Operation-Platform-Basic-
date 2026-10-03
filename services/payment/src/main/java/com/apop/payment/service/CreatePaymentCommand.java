package com.apop.payment.service;

import com.apop.payment.domain.Rail;

import java.time.LocalDate;
import java.util.UUID;

public record CreatePaymentCommand(
        String legalEntityId,
        String idempotencyKey,
        String endToEndId,
        Rail rail,
        long amountMinor,
        String currency,
        UUID debtorAccountId,
        UUID creditorAccountId,
        String creditorName,
        String creditorAccountNumber,
        String creditorRoutingNumber,
        String creditorCountry,
        String remittance,
        LocalDate requestedValueDate,
        String initiatedBy,
        UUID correlationId
) {
}
