package com.apop.payment.service;

import com.apop.payment.domain.PaymentStatus;

import java.util.UUID;

public record SettlementUpdate(PaymentStatus targetStatus, UUID journalEntryId, String reasonCode) {
}
