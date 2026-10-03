package com.apop.payment.messaging;

public enum LifecycleEventName {
    PaymentReceived,
    PaymentValidated,
    PaymentAuthorized,
    PaymentFailed,
    PaymentSettlementPending,
    PaymentSettled
}
