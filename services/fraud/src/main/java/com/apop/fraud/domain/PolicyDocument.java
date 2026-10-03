package com.apop.fraud.domain;

import java.util.List;

public record PolicyDocument(
        int reviewThreshold,
        int declineThreshold,
        int newCreditorWeight,
        int reviewTtlSeconds,
        List<Window> windows,
        Geo geo,
        Device device,
        List<Corridor> allowedCorridors
) {
    public record Window(
            String name,
            int seconds,
            int debtorCount,
            long debtorAmountMinor,
            int pairCount,
            long pairAmountMinor,
            int deviceCount,
            long deviceAmountMinor,
            int debtorWeight,
            int pairWeight,
            int deviceWeight
    ) {
    }

    public record Geo(
            int lookbackSeconds,
            int sessionChangeSeconds,
            int newCountryWeight,
            int mismatchWeight,
            int sessionChangeWeight,
            List<String> missingCountryHardReviewChannels
    ) {
    }

    public record Device(
            int lookbackSeconds,
            int newDeviceWeight,
            int manyDebtors,
            int manyDebtorsWeight,
            List<String> missingDeviceHardReviewChannels
    ) {
    }

    public record Corridor(String rail, String debtorCountry, String creditorCountry) {
    }

    public int longestWindowSeconds() {
        int longest = Math.max(geo.lookbackSeconds(), device.lookbackSeconds());
        for (Window window : windows) {
            longest = Math.max(longest, window.seconds());
        }
        return longest;
    }
}
