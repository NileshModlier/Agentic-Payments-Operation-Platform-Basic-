package com.apop.settlement.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.UUID;

@ConfigurationProperties(prefix = "apop")
public record SettlementProperties(
        Payment payment,
        Ledger ledger,
        Messaging messaging
) {
    public record Payment(String baseUrl) {
    }

    public record Ledger(String baseUrl, UUID clearingAccountId) {
    }

    public record Messaging(
            boolean enabled,
            String lifecycleTopic,
            String itemTopic,
            int partitions,
            short replicas,
            long relayDelayMs,
            long retryDelayMs
    ) {
        public String cloudEventType() {
            return "com.apop.payments.settlement.item.v1";
        }

        public String deadLetterTopic() {
            return lifecycleTopic + ".payment-settlement-executor.dlq";
        }
    }
}
