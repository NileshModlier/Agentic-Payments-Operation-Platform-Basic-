package com.apop.fraud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface FraudReviewRepository extends JpaRepository<FraudReview, UUID> {

    Optional<FraudReview> findByPaymentId(UUID paymentId);
}
