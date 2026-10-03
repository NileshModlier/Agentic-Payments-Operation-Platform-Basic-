package com.apop.payment.fraud;

public class FraudUnavailableException extends RuntimeException {

    public FraudUnavailableException() {
        super("Fraud assessment is unavailable");
    }
}
