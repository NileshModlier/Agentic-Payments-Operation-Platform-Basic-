package com.apop.payment.messaging;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@JsonIgnoreProperties(ignoreUnknown = true)
public record LifecycleEnvelope(
        String specversion,
        UUID id,
        String source,
        String type,
        String subject,
        Instant time,
        String datacontenttype,
        LifecycleData data
) {
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record LifecycleData(
            LifecycleEventName eventName,
            UUID paymentId,
            String status,
            String previousStatus,
            String rail,
            long amountMinor,
            String currency,
            UUID debtorAccountId,
            UUID creditorAccountId,
            String creditorFingerprint,
            LocalDate valueDate,
            String reasonCode,
            UUID stepId
    ) {
    }
}
