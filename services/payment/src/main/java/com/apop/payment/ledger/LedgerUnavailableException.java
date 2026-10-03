package com.apop.payment.ledger;

public class LedgerUnavailableException extends RuntimeException {

    public LedgerUnavailableException(String detail, Throwable cause) {
        super(detail, cause);
    }

    public LedgerUnavailableException(String detail) {
        super(detail);
    }
}
