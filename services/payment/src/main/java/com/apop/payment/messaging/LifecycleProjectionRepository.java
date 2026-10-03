package com.apop.payment.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface LifecycleProjectionRepository extends JpaRepository<LifecycleProjection, UUID> {

    List<LifecycleProjection> findByPaymentIdOrderByOccurredAtAscEventIdAsc(UUID paymentId);
}
