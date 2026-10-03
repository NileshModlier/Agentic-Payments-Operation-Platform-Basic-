package com.apop.payment.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.LocalTime;

@ConfigurationProperties(prefix = "apop")
public record PaymentProperties(
        String defaultLegalEntity,
        Fraud fraud,
        Ledger ledger,
        Validation validation,
        String remittanceKey
) {
    public record Fraud(String mode) {
    }

    public record Ledger(String baseUrl) {
    }

    public record Validation(long maxAmountMinor, String zone, LocalTime cutoff) {
    }
}
