package com.apop.settlement.domain;

import java.util.UUID;

public record JournalLine(UUID accountId, String direction, long amountMinor, String currency) {
}
