package com.apop.payment.ledger;

public class LedgerBusinessException extends RuntimeException {

    private final String reasonCode;

    public LedgerBusinessException(String reasonCode, String detail) {
        super(detail);
        this.reasonCode = reasonCode == null ? "AUTHORIZATION_DECLINED" : reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
