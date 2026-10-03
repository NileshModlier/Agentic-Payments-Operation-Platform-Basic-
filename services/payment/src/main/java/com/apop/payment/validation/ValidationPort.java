package com.apop.payment.validation;

public interface ValidationPort {

    ValidationOutcome validate(ValidationRequest request);
}
