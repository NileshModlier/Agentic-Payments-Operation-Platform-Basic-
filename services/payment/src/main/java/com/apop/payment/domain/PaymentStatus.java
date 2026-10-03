package com.apop.payment.domain;

public enum PaymentStatus {
    RECEIVED,
    VALIDATED,
    FRAUD_REVIEW,
    AUTHORIZED,
    FAILED,
    SETTLEMENT_PENDING,
    SETTLED,
    SETTLEMENT_FAILED,
    VALIDATION_FAILED,
    FRAUD_DECLINED,
    AUTHORIZATION_DECLINED;

    public boolean isDecided() {
        return this != RECEIVED && this != VALIDATED;
    }
}
