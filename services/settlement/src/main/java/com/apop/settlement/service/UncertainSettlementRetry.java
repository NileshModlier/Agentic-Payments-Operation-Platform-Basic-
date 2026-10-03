package com.apop.settlement.service;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "apop.messaging.enabled", havingValue = "true")
public class UncertainSettlementRetry {

    private final SettlementService settlement;

    public UncertainSettlementRetry(SettlementService settlement) {
        this.settlement = settlement;
    }

    @Scheduled(fixedDelayString = "${apop.messaging.retry-delay-ms}")
    public void retry() {
        settlement.retryUncertain();
    }
}
