package com.apop.settlement.persistence;

import com.apop.settlement.domain.SettlementTimelineEntry;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface SettlementTimelineRepository extends JpaRepository<SettlementTimelineEntry, UUID> {

    List<SettlementTimelineEntry> findByPaymentIdOrderByOccurredAtAscTimelineIdAsc(UUID paymentId);
}
