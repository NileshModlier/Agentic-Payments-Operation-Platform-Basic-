package com.apop.payment.domain;

public enum StepName {
    VALIDATE,
    FRAUD,
    AUTHORIZE,
    SETTLE,
    CANCEL,
    REVERSE
}
