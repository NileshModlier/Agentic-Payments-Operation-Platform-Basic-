package com.apop.payment.messaging;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(name = "apop.messaging.enabled", havingValue = "true")
public class OutboxRelay {

    private final OutboxPublisher publisher;

    OutboxRelay(OutboxPublisher publisher) {
        this.publisher = publisher;
    }

    @Scheduled(fixedDelayString = "${apop.messaging.relay-delay-ms}")
    public void relay() {
        int published = 0;
        while (published < 50 && publisher.publishNext()) {
            published++;
        }
    }
}
