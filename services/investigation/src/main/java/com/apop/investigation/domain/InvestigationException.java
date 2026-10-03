package com.apop.investigation.domain;

import org.springframework.http.HttpStatus;

public class InvestigationException extends RuntimeException {

    private final HttpStatus status;
    private final String type;
    private final String reasonCode;

    private InvestigationException(HttpStatus status, String type, String reasonCode, String message) {
        super(message);
        this.status = status;
        this.type = type;
        this.reasonCode = reasonCode;
    }

    public static InvestigationException unavailable(String message) {
        return new InvestigationException(HttpStatus.SERVICE_UNAVAILABLE, "risk-control-unavailable", "TECHNICAL_DEPENDENCY", message);
    }

    public static InvestigationException conflict(String message) {
        return new InvestigationException(HttpStatus.CONFLICT, "idempotency-conflict", "IDEMPOTENCY_CONFLICT", message);
    }

    public static InvestigationException illegal(String message) {
        return new InvestigationException(HttpStatus.CONFLICT, "illegal-state", "ILLEGAL_STATE", message);
    }

    public static InvestigationException forbidden(String message) {
        return new InvestigationException(HttpStatus.FORBIDDEN, "account-forbidden", "INVESTIGATION_ACTOR_FORBIDDEN", message);
    }

    public static InvestigationException paused() {
        return new InvestigationException(HttpStatus.FORBIDDEN, "agent-paused", "AGENT_PAUSED",
                "Assistance is paused. A human can still decide the case.");
    }

    public static InvestigationException badRequest(String message) {
        return new InvestigationException(HttpStatus.BAD_REQUEST, "schema-invalid", "VALIDATION_SCHEMA", message);
    }

    public static InvestigationException notFound(String message) {
        return new InvestigationException(HttpStatus.NOT_FOUND, "not-found", null, message);
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
