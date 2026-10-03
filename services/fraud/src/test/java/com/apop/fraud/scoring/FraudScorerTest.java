package com.apop.fraud.scoring;

import com.apop.fraud.domain.PolicyDocument;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class FraudScorerTest {

    private final PolicyDocument policy = new PolicyDocument(
            200,
            500,
            150,
            3600,
            List.of(new PolicyDocument.Window("15m", 900, 3, 200_000, 100, 100_000_000, 100, 100_000_000, 400, 100, 100)),
            new PolicyDocument.Geo(3600, 3600, 120, 250, 220, List.of("MOBILE", "WEB")),
            new PolicyDocument.Device(3600, 100, 2, 220, List.of("MOBILE", "WEB")),
            List.of(new PolicyDocument.Corridor("BOOK", "US", "US")));

    @Test
    void aQuietBookPaymentApprovesAtZero() {
        FraudScorer.Result result = FraudScorer.score(policy, signals(false, true, "API", "US", "US", quiet()));
        assertThat(result.decision()).isEqualTo(FraudScorer.Decision.APPROVE);
        assertThat(result.score()).isZero();
        assertThat(result.hits()).isEmpty();
    }

    @Test
    void aHardDeclineBeatsALowScore() {
        FraudScorer.Result result = FraudScorer.score(policy, signals(true, true, "API", "US", "US", quiet()));
        assertThat(result.decision()).isEqualTo(FraudScorer.Decision.DECLINE);
        assertThat(result.hits()).extracting(FraudScorer.Hit::ruleId).contains("LIST_CREDITOR_DENY");
    }

    @Test
    void aMissingMobileCountryForcesReview() {
        FraudScorer.Signals base = signals(false, false, "MOBILE", null, "US", quiet());
        FraudScorer.Result result = FraudScorer.score(policy, base);
        assertThat(result.decision()).isEqualTo(FraudScorer.Decision.REVIEW);
        assertThat(result.hits()).extracting(FraudScorer.Hit::ruleId).contains("GEO_SESSION_COUNTRY_MISSING", "NEW_CREDITOR");
    }

    @Test
    void anAllowListSuppressesOnlyTheNewCreditorRule() {
        FraudScorer.Signals allowed = new FraudScorer.Signals(
                "API", "BOOK", "US", "US", "GB", false, false, false, true, false, true, false, true,
                null, null, Instant.parse("2026-10-02T12:00:00Z"), List.of(quiet()));
        FraudScorer.Result result = FraudScorer.score(policy, allowed);
        assertThat(result.hits()).extracting(FraudScorer.Hit::ruleId).contains("GEO_CREDITOR_COUNTRY_MISMATCH");
        assertThat(result.hits()).extracting(FraudScorer.Hit::ruleId).doesNotContain("NEW_CREDITOR");
        assertThat(result.decision()).isEqualTo(FraudScorer.Decision.REVIEW);
    }

    @Test
    void aSessionInTheDebtorCountryIsNotANewCountry() {
        FraudScorer.Result result = FraudScorer.score(policy, signals(false, false, "API", "US", "US", quiet(), false));
        assertThat(result.hits()).extracting(FraudScorer.Hit::ruleId).contains("NEW_CREDITOR");
        assertThat(result.hits()).extracting(FraudScorer.Hit::ruleId).doesNotContain("GEO_SESSION_COUNTRY_NEW");
        assertThat(result.score()).isEqualTo(150);
        assertThat(result.decision()).isEqualTo(FraudScorer.Decision.APPROVE);
    }

    @Test
    void anUnseenForeignSessionCountryAddsGeoPoints() {
        FraudScorer.Result result = FraudScorer.score(policy, signals(false, true, "API", "GB", "US", quiet(), false));
        assertThat(result.hits()).extracting(FraudScorer.Hit::ruleId).contains("GEO_SESSION_COUNTRY_NEW");
        assertThat(result.score()).isEqualTo(120);
        assertThat(result.decision()).isEqualTo(FraudScorer.Decision.APPROVE);
    }

    @Test
    void velocityAndNewCreditorCanReachTheDeclineBand() {
        FraudScorer.Signals.Window hot = new FraudScorer.Signals.Window("15m", 4, 250_000, 1, 250_000, 0, 0, 1);
        FraudScorer.Result result = FraudScorer.score(policy, signals(false, false, "API", "US", "US", hot));
        assertThat(result.score()).isEqualTo(550);
        assertThat(result.decision()).isEqualTo(FraudScorer.Decision.DECLINE);
    }

    private FraudScorer.Signals signals(
            boolean denied,
            boolean seen,
            String channel,
            String session,
            String creditorCountry,
            FraudScorer.Signals.Window window) {
        return signals(denied, seen, channel, session, creditorCountry, window, true);
    }

    private FraudScorer.Signals signals(
            boolean denied,
            boolean seen,
            String channel,
            String session,
            String creditorCountry,
            FraudScorer.Signals.Window window,
            boolean countrySeen) {
        return new FraudScorer.Signals(
                channel, "BOOK", session, "US", creditorCountry, denied, false, false, false, seen, true, false, countrySeen,
                null, null, Instant.parse("2026-10-02T12:00:00Z"), List.of(window));
    }

    private static FraudScorer.Signals.Window quiet() {
        return new FraudScorer.Signals.Window("15m", 1, 10_000, 1, 10_000, 0, 0, 1);
    }
}
