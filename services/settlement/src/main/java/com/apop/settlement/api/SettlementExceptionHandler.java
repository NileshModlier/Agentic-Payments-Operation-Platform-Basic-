package com.apop.settlement.api;

import com.apop.settlement.service.SettlementNotFoundException;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class SettlementExceptionHandler {

    @ExceptionHandler(SettlementNotFoundException.class)
    public ResponseEntity<ProblemBody> missing(SettlementNotFoundException exception) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(new ProblemBody(
                "https://apop.internal/problems/not-found",
                HttpStatus.NOT_FOUND.getReasonPhrase(),
                404,
                exception.getMessage(),
                null,
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
