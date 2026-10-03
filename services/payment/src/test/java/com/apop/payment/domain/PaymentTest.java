package com.apop.payment.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PaymentTest {

    @Test
    void authorizedRequiresAHold() {
        Payment payment = received();
        payment.markValidated(LocalDate.of(2026, 10, 2));

        assertThatThrownBy(() -> payment.markAuthorized(null))
                .isInstanceOf(PaymentException.class)
                .hasFieldOrPropertyWithValue("reasonCode", "ILLEGAL_STATE");
    }

    private static Payment received() {
        return Payment.receive(
                UUID.randomUUID(),
                "SIM-BANK",
                "key-1",
                "E2E-1",
                Rail.BOOK,
                125_000L,
                "USD",
                UUID.randomUUID(),
                UUID.randomUUID(),
                LocalDate.of(2026, 10, 2),
                null,
                null,
                "channel",
                UUID.randomUUID(),
                Instant.parse("2026-10-02T12:00:00Z"));
    }
}
