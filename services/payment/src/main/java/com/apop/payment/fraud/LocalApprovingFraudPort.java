package com.apop.payment.fraud;

import com.apop.payment.domain.UuidV7;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "apop.fraud.mode", havingValue = "local")
public class LocalApprovingFraudPort implements FraudPort {

    @Override
    public FraudDecision assess(FraudRequest request) {
        return new FraudDecision(FraudOutcome.APPROVE, 0, null, UuidV7.generate());
    }
}
