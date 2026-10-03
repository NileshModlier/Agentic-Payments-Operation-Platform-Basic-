package com.apop.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apop.messaging")
public record MessagingProperties(
        boolean enabled,
        String topic,
        int partitions,
        short replicas,
        long relayDelayMs
) {
    public String cloudEventType() {
        return "com.apop.payments.payment.lifecycle.v1";
    }

    public String deadLetterTopic() {
        return topic + ".transaction-history-projector.dlq";
    }
}
