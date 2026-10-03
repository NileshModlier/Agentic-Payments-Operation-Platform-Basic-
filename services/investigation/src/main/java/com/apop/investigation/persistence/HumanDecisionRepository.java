package com.apop.investigation.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HumanDecisionRepository extends JpaRepository<HumanDecision, UUID> {

    List<HumanDecision> findByCaseId(UUID caseId);
}
