package com.apop.fraud.messaging;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "apop.messaging")
public record FraudMessagingProperties(
        boolean enabled,
        String assessmentTopic,
        String auditTopic,
        int partitions,
        int replicas,
        long relayDelayMs
) {
}
