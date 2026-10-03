package com.apop.investigation.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface AgentRecommendationRepository extends JpaRepository<AgentRecommendation, UUID> {

    Optional<AgentRecommendation> findFirstByCaseIdOrderByCreatedAtDesc(UUID caseId);
}
