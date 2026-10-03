package com.apop.settlement.ledger;

public class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(String detail) {
        super(detail);
    }

    public LedgerUnavailableException(String detail, Throwable cause) {
        super(detail, cause);
    }
}
