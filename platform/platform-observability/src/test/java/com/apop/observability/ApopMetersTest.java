package com.apop.observability;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.Tags;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class ApopMetersTest {

    @Test
    void countersUseBoundedLabels() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ApopMeters meters = new ApopMeters(registry);

        meters.paymentCreated("BOOK");
        meters.paymentDecision("BOOK", "AUTHORIZED", " ");
        meters.paymentSettled("BOOK");
        meters.ledgerPost("PAYMENT", "posted");
        meters.idempotencyReplay("replay");
        meters.settlementLag("BOOK", Duration.ofMillis(40));

        assertThat(registry.get("apop.payment.decision").counter().count()).isEqualTo(1);
        assertThat(registry.get("apop.payment.decision").counter().getId().getTag("reason_code")).isEqualTo("none");
        assertThat(registry.get("apop.settlement.lag").timer().count()).isEqualTo(1);
    }

    @Test
    void highCardinalityIdentifiersAreDenied() {
        Meter.Id allowed = new Meter.Id("apop.payment.decision", Tags.of("rail", "BOOK"), null, null, Meter.Type.COUNTER);
        Meter.Id denied = new Meter.Id("http.server.requests", Tags.of("payment_id", "abc"), null, null, Meter.Type.TIMER);
        assertThat(ApopObservabilityAutoConfiguration.highCardinality(allowed)).isFalse();
        assertThat(ApopObservabilityAutoConfiguration.highCardinality(denied)).isTrue();
    }
}
