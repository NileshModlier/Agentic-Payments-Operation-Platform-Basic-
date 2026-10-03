package com.apop.payment.fraud;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "apop.fraud.mode", havingValue = "fail-closed", matchIfMissing = true)
public class FailClosedFraudPort implements FraudPort {

    @Override
    public FraudDecision assess(FraudRequest request) {
        throw new FraudUnavailableException();
    }
}
