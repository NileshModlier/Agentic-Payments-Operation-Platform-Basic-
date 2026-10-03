package com.apop.payment.domain;

import org.springframework.http.HttpStatus;

public class PaymentException extends RuntimeException {

    private final HttpStatus status;
    private final String problemSlug;
    private final String reasonCode;

    public PaymentException(HttpStatus status, String problemSlug, String reasonCode, String detail) {
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

    public static PaymentException notFound() {
        return new PaymentException(HttpStatus.NOT_FOUND, "not-found", null, "Payment was not found");
    }

    public static PaymentException idempotencyKeyRequired() {
        return new PaymentException(
                HttpStatus.BAD_REQUEST,
                "idempotency-key-required",
                "IDEMPOTENCY_KEY_REQUIRED",
                "Idempotency-Key is required");
    }

    public static PaymentException conflict(String detail) {
        return new PaymentException(HttpStatus.CONFLICT, "idempotency-conflict", "IDEMPOTENCY_CONFLICT", detail);
    }

    public static PaymentException duplicateInstruction() {
        return new PaymentException(
                HttpStatus.CONFLICT,
                "duplicate-instruction",
                "DUPLICATE_END_TO_END_ID",
                "This end-to-end id was already used for the debtor account");
    }

    public static PaymentException illegal(String detail) {
        return new PaymentException(HttpStatus.CONFLICT, "illegal-state", "ILLEGAL_STATE", detail);
    }
}
