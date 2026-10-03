package com.apop.investigation.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InvestigationCaseRepository extends JpaRepository<InvestigationCase, UUID> {

    Optional<InvestigationCase> findBySourceAndSourceRef(String source, UUID sourceRef);
}
