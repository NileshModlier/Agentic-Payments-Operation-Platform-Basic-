package com.apop.payment.api;

import com.apop.payment.domain.PaymentException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class PaymentExceptionHandler {

    @ExceptionHandler(PaymentException.class)
    public ResponseEntity<ProblemBody> handle(PaymentException exception) {
        return problem(exception.status(), exception.problemSlug(), exception.getMessage(), exception.reasonCode());
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemBody> missingHeader(MissingRequestHeaderException exception) {
        if ("Idempotency-Key".equals(exception.getHeaderName())) {
            return problem(HttpStatus.BAD_REQUEST, "idempotency-key-required", "Idempotency-Key is required", "IDEMPOTENCY_KEY_REQUIRED");
        }
        return problem(HttpStatus.BAD_REQUEST, "schema-invalid", "Request is not valid", "VALIDATION_SCHEMA");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemBody> invalid(MethodArgumentNotValidException exception) {
        return problem(HttpStatus.BAD_REQUEST, "schema-invalid", "Request is not valid", "VALIDATION_SCHEMA");
    }

    private static ResponseEntity<ProblemBody> problem(HttpStatus status, String slug, String detail, String reasonCode) {
        return ResponseEntity.status(status).body(new ProblemBody(
                "https://apop.internal/problems/" + slug,
                status.getReasonPhrase(),
                status.value(),
                detail,
                reasonCode,
                MDC.get("correlationId")));
    }

    public record ProblemBody(
            String type,
            String title,
            int status,
            String detail,
            String reasonCode,
            String correlationId
    ) {
    }
}
