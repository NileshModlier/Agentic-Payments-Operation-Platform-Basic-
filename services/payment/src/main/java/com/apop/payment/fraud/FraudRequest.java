package com.apop.payment.fraud;

import java.util.UUID;

public record FraudRequest(UUID paymentId, long amountMinor, String currency, UUID debtorAccountId) {
}
