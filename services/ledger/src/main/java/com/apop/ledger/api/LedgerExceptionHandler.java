package com.apop.ledger.api;

import com.apop.ledger.domain.LedgerException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class LedgerExceptionHandler {

    @ExceptionHandler(LedgerException.class)
    public ResponseEntity<ProblemBody> handle(LedgerException exception) {
        return problem(exception.status(), exception.problemSlug(), exception.getMessage(), exception.reasonCode());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemBody> handleInvalid(MethodArgumentNotValidException exception) {
        return problem(HttpStatus.BAD_REQUEST, "schema-invalid", "Request is not valid", "VALIDATION_SCHEMA");
    }

    private static ResponseEntity<ProblemBody> problem(HttpStatus status, String slug, String detail, String reasonCode) {
        ProblemBody body = new ProblemBody(
                "https://apop.internal/problems/" + slug,
                status.getReasonPhrase(),
                status.value(),
                detail,
                reasonCode,
                MDC.get("correlationId"));
        return ResponseEntity.status(status).body(body);
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
