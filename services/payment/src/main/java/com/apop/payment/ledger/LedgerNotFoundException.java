package com.apop.payment.ledger;

public class LedgerNotFoundException extends RuntimeException {

    public LedgerNotFoundException() {
        super("Account was not found");
    }
}
