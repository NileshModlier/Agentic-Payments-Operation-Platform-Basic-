package com.apop.ledger.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void holdReducesAvailableBalanceAndLeavesLedgerBalanceUnchanged() {
        Account account = customer();
        account.apply(Direction.CREDIT, 200_000L);

        account.placeHold(125_000L);

        assertThat(account.getLedgerBalanceMinor()).isEqualTo(200_000L);
        assertThat(account.getHeldMinor()).isEqualTo(125_000L);
        assertThat(account.availableMinor()).isEqualTo(75_000L);
    }

    @Test
    void holdThatWouldMakeAvailableBalanceNegativeIsRefused() {
        Account account = customer();
        account.apply(Direction.CREDIT, 50_000L);

        assertThatThrownBy(() -> account.placeHold(50_001L))
                .isInstanceOf(LedgerException.class)
                .hasFieldOrPropertyWithValue("reasonCode", "AUTH_INSUFFICIENT_FUNDS");
        assertThat(account.getHeldMinor()).isZero();
        assertThat(account.getLedgerBalanceMinor()).isEqualTo(50_000L);
    }

    @Test
    void postingADebitReducesACreditNormalCustomerBalance() {
        Account account = customer();
        account.apply(Direction.CREDIT, 200_000L);
        account.placeHold(125_000L);

        account.apply(Direction.DEBIT, 125_000L);
        account.releaseHeld(125_000L);

        assertThat(account.getLedgerBalanceMinor()).isEqualTo(75_000L);
        assertThat(account.getHeldMinor()).isZero();
        assertThat(account.availableMinor()).isEqualTo(75_000L);
    }

    private static Account customer() {
        return Account.open(
                UUID.randomUUID(),
                "SIM-BANK",
                AccountRole.CUSTOMER,
                null,
                "6789",
                "a".repeat(64),
                "USD",
                NormalBalance.CREDIT,
                Instant.parse("2026-10-02T00:00:00Z"));
    }
}
