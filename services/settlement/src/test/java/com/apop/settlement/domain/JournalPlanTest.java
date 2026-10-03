package com.apop.settlement.domain;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class JournalPlanTest {

    @Test
    void bookDebitsTheDebtorAndCreditsTheCreditor() {
        UUID debtor = UUID.randomUUID();
        UUID creditor = UUID.randomUUID();

        assertThat(JournalPlan.lines("BOOK", debtor, creditor, UUID.randomUUID(), 125_000L, "USD"))
                .containsExactly(
                        new JournalLine(debtor, "DEBIT", 125_000L, "USD"),
                        new JournalLine(creditor, "CREDIT", 125_000L, "USD"));
    }

    @Test
    void externalRailCreditsClearing() {
        UUID debtor = UUID.randomUUID();
        UUID clearing = UUID.randomUUID();

        assertThat(JournalPlan.lines("SIMULATED_ACH", debtor, null, clearing, 80_000L, "USD"))
                .containsExactly(
                        new JournalLine(debtor, "DEBIT", 80_000L, "USD"),
                        new JournalLine(clearing, "CREDIT", 80_000L, "USD"));
    }

    @Test
    void bookWithoutACreditorIsRejected() {
        assertThatThrownBy(() -> JournalPlan.lines("BOOK", UUID.randomUUID(), null, UUID.randomUUID(), 1L, "USD"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
