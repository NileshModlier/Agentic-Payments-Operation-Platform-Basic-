package com.apop.observability;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

public class ApopMeters {

    private final MeterRegistry registry;

    public ApopMeters(MeterRegistry registry) {
        this.registry = registry;
    }

    public void paymentCreated(String rail) {
        // OpenMetrics reserves a trailing "created" suffix, so this series is named intake.
        registry.counter("apop.payment.intake", "rail", label(rail)).increment();
    }

    public void paymentDecision(String rail, String status, String reasonCode) {
        registry.counter(
                "apop.payment.decision",
                "rail", label(rail),
                "status", label(status),
                "reason_code", label(reasonCode)).increment();
    }

    public void paymentSettled(String rail) {
        registry.counter("apop.payment.settled", "rail", label(rail)).increment();
    }

    public void ledgerPost(String entryType, String result) {
        registry.counter(
                "apop.ledger.post",
                "entry_type", label(entryType),
                "result", label(result)).increment();
    }

    public void idempotencyReplay(String result) {
        registry.counter("apop.idempotency.replay", "result", label(result)).increment();
    }

    public void settlementLag(String rail, Duration lag) {
        Timer.builder("apop.settlement.lag")
                .tag("rail", label(rail))
                .serviceLevelObjectives(
                        Duration.ofSeconds(1),
                        Duration.ofSeconds(2),
                        Duration.ofSeconds(5))
                .register(registry)
                .record(lag);
    }

    static String label(String value) {
        if (value == null || value.isBlank()) {
            return "none";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 64 ? trimmed : trimmed.substring(0, 64);
    }
}
