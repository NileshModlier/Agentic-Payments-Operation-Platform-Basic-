package com.apop.investigation.domain;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

class PolicyGuardTest {

    @Test
    void aFraudReleaseMustCiteTheAssessmentAndThePayment() {
        PolicyGuard.Draft draft = new PolicyGuard.Draft(
                "RELEASE_TO_AUTHORIZE",
                new BigDecimal("0.500"),
                List.of("payment:p1"),
                "Release the review.");
        assertThat(PolicyGuard.recommendation("FRAUD", draft, Set.of("payment:p1"), "p1").check())
                .isEqualTo("RELEASE_CITATIONS");
    }

    @Test
    void rootCauseCannotProposeAReversal() {
        PolicyGuard.Draft draft = new PolicyGuard.Draft(
                "PROPOSE_REVERSAL",
                new BigDecimal("0.500"),
                List.of("journal:j1"),
                "Reverse it.");
        assertThat(PolicyGuard.recommendation("ROOT_CAUSE", draft, Set.of("journal:j1"), "p1").check())
                .isEqualTo("DISPOSITION");
    }

    @Test
    void anUnretrievedEvidenceIdIsRefused() {
        PolicyGuard.Draft draft = new PolicyGuard.Draft(
                "CONFIRM_DECLINE",
                new BigDecimal("0.500"),
                List.of("payment:other"),
                "Decline the other payment.");
        assertThat(PolicyGuard.recommendation("FRAUD", draft, Set.of("payment:p1"), "p1").accepted()).isFalse();
    }

    @Test
    void aFullAccountNumberInTheNarrativeIsRefused() {
        PolicyGuard.Draft draft = new PolicyGuard.Draft(
                "NO_ACTION",
                new BigDecimal("0.200"),
                List.of(),
                "Account 123456789012 looks familiar.");
        assertThat(PolicyGuard.recommendation("ROOT_CAUSE", draft, Set.of(), "p1").check()).isEqualTo("NARRATIVE");
    }

    @Test
    void forbiddenToolsAreNotInAnyRole() {
        assertThat(ToolCatalog.allowed("FRAUD", "place_hold")).isFalse();
        assertThat(ToolCatalog.allowed("FRAUD", "release_fraud_review")).isFalse();
        assertThat(ToolCatalog.allowed("SETTLEMENT", "settle_payment")).isFalse();
        assertThat(ToolCatalog.allowed("ROOT_CAUSE", "authorize_payment")).isFalse();
        assertThat(ToolCatalog.allowed("SUPERVISOR", "get_fraud_assessment")).isFalse();
        assertThat(ToolCatalog.allowed("FRAUD", "get_fraud_assessment")).isTrue();
    }
}
