package com.apop.settlement.payment;

public class PaymentUnavailableException extends RuntimeException {

    public PaymentUnavailableException(String detail, Throwable cause) {
        super(detail, cause);
    }

    public PaymentUnavailableException(String detail) {
        super(detail);
    }
}
