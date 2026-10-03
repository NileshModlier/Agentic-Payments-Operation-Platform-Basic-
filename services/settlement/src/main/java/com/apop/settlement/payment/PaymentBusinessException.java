package com.apop.settlement.payment;

public class PaymentBusinessException extends RuntimeException {

    private final String reasonCode;

    public PaymentBusinessException(String reasonCode, String detail) {
        super(detail);
        this.reasonCode = reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
