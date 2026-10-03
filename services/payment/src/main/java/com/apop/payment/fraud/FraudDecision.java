package com.apop.payment.fraud;

import java.util.UUID;

public record FraudDecision(FraudOutcome outcome, int score, String reasonCode, UUID assessmentId) {
}
