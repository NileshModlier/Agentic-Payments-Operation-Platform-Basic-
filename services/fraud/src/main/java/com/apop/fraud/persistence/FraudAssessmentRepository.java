package com.apop.fraud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FraudAssessmentRepository extends JpaRepository<FraudAssessment, UUID> {

    Optional<FraudAssessment> findByPaymentId(UUID paymentId);

    Optional<FraudAssessment> findByIdempotencyKey(String idempotencyKey);

    List<FraudAssessment> findByDebtorAccountIdAndCreatedAtAfter(UUID debtorAccountId, Instant createdAt);
}
