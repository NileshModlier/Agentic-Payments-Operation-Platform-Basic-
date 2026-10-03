package com.apop.payment.service;

import com.apop.payment.domain.PaymentStatus;
import com.apop.payment.domain.Rail;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record PaymentView(
        UUID paymentId,
        String endToEndId,
        PaymentStatus status,
        Rail rail,
        UUID debtorAccountId,
        UUID creditorAccountId,
        Money amount,
        LocalDate valueDate,
        String reasonCode,
        UUID correlationId,
        UUID holdId,
        UUID journalEntryId,
        Instant updatedAt
) {
    public record Money(long minor, String currency) {
    }
}
