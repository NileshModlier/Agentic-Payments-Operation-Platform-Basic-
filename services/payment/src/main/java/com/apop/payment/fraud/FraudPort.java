package com.apop.payment.fraud;

public interface FraudPort {

    FraudDecision assess(FraudRequest request);
}
