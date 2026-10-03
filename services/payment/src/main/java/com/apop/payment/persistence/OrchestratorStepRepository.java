package com.apop.payment.persistence;

import com.apop.payment.domain.OrchestratorStep;
import com.apop.payment.domain.StepName;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface OrchestratorStepRepository extends JpaRepository<OrchestratorStep, UUID> {

    Optional<OrchestratorStep> findByPaymentIdAndStepName(UUID paymentId, StepName stepName);
}
