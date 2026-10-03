package com.apop.payment.validation;

import com.apop.payment.domain.UuidV7;

import java.time.LocalDate;
import java.util.UUID;

public record ValidationOutcome(boolean passed, String reasonCode, LocalDate valueDate, UUID validationId) {

    public static ValidationOutcome pass(LocalDate valueDate) {
        return new ValidationOutcome(true, null, valueDate, UuidV7.generate());
    }

    public static ValidationOutcome reject(String reasonCode) {
        return new ValidationOutcome(false, reasonCode, null, UuidV7.generate());
    }
}
