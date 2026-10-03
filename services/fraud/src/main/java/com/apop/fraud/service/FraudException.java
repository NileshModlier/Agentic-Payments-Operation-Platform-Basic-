package com.apop.fraud.service;

import org.springframework.http.HttpStatus;

public class FraudException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String reasonCode;

    private FraudException(HttpStatus status, String type, String reasonCode, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.reasonCode = reasonCode;
    }

    public static FraudException unavailable() {
        return new FraudException(HttpStatus.SERVICE_UNAVAILABLE, "risk-control-unavailable", "TECHNICAL_DEPENDENCY",
                "Fraud scoring could not be computed.");
    }

    public static FraudException conflict(String message) {
        return new FraudException(HttpStatus.CONFLICT, "idempotency-conflict", "IDEMPOTENCY_CONFLICT", message);
    }

    public static FraudException illegal(String message) {
        return new FraudException(HttpStatus.CONFLICT, "illegal-state", "ILLEGAL_STATE", message);
    }

    public static FraudException forbidden(String message) {
        return new FraudException(HttpStatus.FORBIDDEN, "account-forbidden", "FRAUD_ACTOR_FORBIDDEN", message);
    }

    public static FraudException badRequest(String message) {
        return new FraudException(HttpStatus.BAD_REQUEST, "schema-invalid", "VALIDATION_SCHEMA", message);
    }

    public static FraudException notFound(String message) {
        return new FraudException(HttpStatus.NOT_FOUND, "not-found", null, message);
    }

    public HttpStatus status() {
        return status;
    }

    public String type() {
        return type;
    }

    public String reasonCode() {
        return reasonCode;
    }
}
