package com.apop.fraud.scoring;

import com.apop.fraud.domain.PolicyDocument;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class FraudScorer {

    private FraudScorer() {
    }

    public static Result score(PolicyDocument policy, Signals signals) {
        List<Hit> hits = new ArrayList<>();
        boolean hardDecline = false;
        if (signals.creditorDenied()) {
            hits.add(Hit.hard("LIST_CREDITOR_DENY"));
            hardDecline = true;
        }
        if (signals.deviceDenied()) {
            hits.add(Hit.hard("LIST_DEVICE_DENY"));
            hardDecline = true;
        }
        if (signals.countryDenied()) {
            hits.add(Hit.hard("LIST_COUNTRY_DENY"));
            hardDecline = true;
        }

        int score = 0;
        if (!signals.creditorAllowed() && !signals.creditorSeen() && policy.newCreditorWeight() > 0) {
            score += policy.newCreditorWeight();
            hits.add(new Hit("NEW_CREDITOR", policy.newCreditorWeight(), false));
        }
        for (PolicyDocument.Window window : policy.windows()) {
            Signals.Window windowSignals = signals.window(window.name());
            if (windowSignals == null) {
                continue;
            }
            score += addVelocity(hits, "VELOCITY_DEBTOR_" + window.name(), window.debtorWeight(),
                    windowSignals.debtorCount() >= window.debtorCount()
                            || windowSignals.debtorAmountMinor() >= window.debtorAmountMinor());
            score += addVelocity(hits, "VELOCITY_PAIR_" + window.name(), window.pairWeight(),
                    windowSignals.pairCount() >= window.pairCount()
                            || windowSignals.pairAmountMinor() >= window.pairAmountMinor());
            if (signals.deviceHashPresent()) {
                score += addVelocity(hits, "VELOCITY_DEVICE_" + window.name(), window.deviceWeight(),
                        windowSignals.deviceCount() >= window.deviceCount()
                                || windowSignals.deviceAmountMinor() >= window.deviceAmountMinor());
            }
        }
        if (signals.deviceHashPresent()) {
            Signals.Window shortest = signals.window(policy.windows().getFirst().name());
            if (shortest != null && shortest.distinctDebtors() >= policy.device().manyDebtors()) {
                score += policy.device().manyDebtorsWeight();
                hits.add(new Hit("DEVICE_MANY_DEBTORS", policy.device().manyDebtorsWeight(), false));
            }
        }
        score += geo(policy, signals, hits);
        score += device(policy, signals, hits);
        boolean hardReview = missingContext(policy, signals, hits);
        score = Math.min(score, 1000);

        Decision band = band(policy, score);
        Decision decision;
        if (hardDecline) {
            decision = Decision.DECLINE;
        } else if (hardReview && band == Decision.APPROVE) {
            decision = Decision.REVIEW;
        } else {
            decision = band;
        }
        return new Result(decision, score, List.copyOf(hits));
    }

    private static int geo(PolicyDocument policy, Signals signals, List<Hit> hits) {
        int score = 0;
        PolicyDocument.Geo geo = policy.geo();
        if (unseenSessionCountry(signals) && geo.newCountryWeight() > 0) {
            score += geo.newCountryWeight();
            hits.add(new Hit("GEO_SESSION_COUNTRY_NEW", geo.newCountryWeight(), false));
        }
        if (signals.debtorCountry() != null && signals.creditorCountry() != null
                && !signals.debtorCountry().equals(signals.creditorCountry())
                && !corridorAllowed(policy, signals)) {
            score += geo.mismatchWeight();
            hits.add(new Hit("GEO_CREDITOR_COUNTRY_MISMATCH", geo.mismatchWeight(), false));
        }
        if (signals.previousSessionCountry() != null
                && signals.sessionCountry() != null
                && !signals.previousSessionCountry().equals(signals.sessionCountry())
                && signals.previousSessionAt() != null
                && Duration.between(signals.previousSessionAt(), signals.now()).getSeconds() <= geo.sessionChangeSeconds()) {
            score += geo.sessionChangeWeight();
            hits.add(new Hit("GEO_SESSION_CHANGE", geo.sessionChangeWeight(), false));
        }
        return score;
    }

    private static boolean unseenSessionCountry(Signals signals) {
        if (signals.sessionCountry() == null || signals.countrySeen()) {
            return false;
        }
        return signals.debtorCountry() == null || !signals.sessionCountry().equals(signals.debtorCountry());
    }

    private static int device(PolicyDocument policy, Signals signals, List<Hit> hits) {
        if (!signals.deviceHashPresent() || signals.deviceSeen() || policy.device().newDeviceWeight() <= 0) {
            return 0;
        }
        hits.add(new Hit("DEVICE_NEW", policy.device().newDeviceWeight(), false));
        return policy.device().newDeviceWeight();
    }

    private static boolean missingContext(PolicyDocument policy, Signals signals, List<Hit> hits) {
        boolean hardReview = false;
        String channel = signals.channel().toUpperCase(Locale.ROOT);
        if (signals.sessionCountry() == null && policy.geo().missingCountryHardReviewChannels().contains(channel)) {
            hits.add(Hit.hardReview("GEO_SESSION_COUNTRY_MISSING"));
            hardReview = true;
        }
        if (!signals.deviceHashPresent() && policy.device().missingDeviceHardReviewChannels().contains(channel)) {
            hits.add(Hit.hardReview("DEVICE_ID_MISSING"));
            hardReview = true;
        }
        return hardReview;
    }

    private static int addVelocity(List<Hit> hits, String ruleId, int weight, boolean fired) {
        if (!fired || weight <= 0) {
            return 0;
        }
        hits.add(new Hit(ruleId, weight, false));
        return weight;
    }

    private static boolean corridorAllowed(PolicyDocument policy, Signals signals) {
        for (PolicyDocument.Corridor corridor : policy.allowedCorridors()) {
            if (corridor.rail().equals(signals.rail())
                    && corridor.debtorCountry().equals(signals.debtorCountry())
                    && corridor.creditorCountry().equals(signals.creditorCountry())) {
                return true;
            }
        }
        return false;
    }

    private static Decision band(PolicyDocument policy, int score) {
        if (score >= policy.declineThreshold()) {
            return Decision.DECLINE;
        }
        if (score >= policy.reviewThreshold()) {
            return Decision.REVIEW;
        }
        return Decision.APPROVE;
    }

    public enum Decision {
        APPROVE, REVIEW, DECLINE
    }

    public record Hit(String ruleId, int points, boolean hard) {
        static Hit hard(String ruleId) {
            return new Hit(ruleId, 0, true);
        }

        static Hit hardReview(String ruleId) {
            return new Hit(ruleId, 0, true);
        }
    }

    public record Result(Decision decision, int score, List<Hit> hits) {
    }

    public record Signals(
            String channel,
            String rail,
            String sessionCountry,
            String debtorCountry,
            String creditorCountry,
            boolean creditorDenied,
            boolean deviceDenied,
            boolean countryDenied,
            boolean creditorAllowed,
            boolean creditorSeen,
            boolean deviceSeen,
            boolean deviceHashPresent,
            boolean countrySeen,
            String previousSessionCountry,
            Instant previousSessionAt,
            Instant now,
            List<Window> windows
    ) {
        public Window window(String name) {
            for (Window window : windows) {
                if (window.name().equals(name)) {
                    return window;
                }
            }
            return null;
        }

        public record Window(
                String name,
                int debtorCount,
                long debtorAmountMinor,
                int pairCount,
                long pairAmountMinor,
                int deviceCount,
                long deviceAmountMinor,
                int distinctDebtors
        ) {
        }
    }
}
