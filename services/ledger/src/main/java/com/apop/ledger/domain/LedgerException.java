package com.apop.ledger.domain;

import org.springframework.http.HttpStatus;

public class LedgerException extends RuntimeException {

    private final HttpStatus status;
    private final String problemSlug;
    private final String reasonCode;

    public LedgerException(HttpStatus status, String problemSlug, String reasonCode, String detail) {
        super(detail);
        this.status = status;
        this.problemSlug = problemSlug;
        this.reasonCode = reasonCode;
    }

    public HttpStatus status() {
        return status;
    }

    public String problemSlug() {
        return problemSlug;
    }

    public String reasonCode() {
        return reasonCode;
    }

    public static LedgerException notFound(String detail) {
        return new LedgerException(HttpStatus.NOT_FOUND, "not-found", null, detail);
    }

    public static LedgerException conflict(String detail) {
        return new LedgerException(HttpStatus.CONFLICT, "idempotency-conflict", "IDEMPOTENCY_CONFLICT", detail);
    }

    public static LedgerException illegalState(String detail) {
        return new LedgerException(HttpStatus.CONFLICT, "illegal-state", "ILLEGAL_STATE", detail);
    }

    public static LedgerException insufficientFunds() {
        return new LedgerException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "insufficient-funds",
                "AUTH_INSUFFICIENT_FUNDS",
                "Available balance is not sufficient for this hold or posting.");
    }

    public static LedgerException unbalanced() {
        return new LedgerException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "ledger-unbalanced",
                "LEDGER_UNBALANCED",
                "Journal debits must equal credits in a single currency.");
    }

    public static LedgerException closedAccount() {
        return new LedgerException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "account-closed",
                "VALIDATION_ACCOUNT_CLOSED",
                "Account is not open.");
    }

    public static LedgerException currencyMismatch() {
        return new LedgerException(
                HttpStatus.UNPROCESSABLE_ENTITY,
                "currency-mismatch",
                "VALIDATION_CURRENCY_MISMATCH",
                "Currency does not match the account.");
    }
}
