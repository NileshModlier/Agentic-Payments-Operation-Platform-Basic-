package com.apop.settlement.ledger;

public class LedgerBusinessException extends RuntimeException {

    private final String reasonCode;

    public LedgerBusinessException(String reasonCode, String detail) {
        super(detail);
        this.reasonCode = reasonCode == null ? "SETTLEMENT_POST_FAILED" : reasonCode;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
