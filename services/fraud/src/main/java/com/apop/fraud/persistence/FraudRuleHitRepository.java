package com.apop.fraud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface FraudRuleHitRepository extends JpaRepository<FraudRuleHit, FraudRuleHit.Key> {

    List<FraudRuleHit> findByAssessmentId(UUID assessmentId);
}
