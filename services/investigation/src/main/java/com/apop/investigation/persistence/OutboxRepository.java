package com.apop.investigation.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    List<OutboxMessage> findByAggregateIdOrderByCreatedAtAsc(UUID aggregateId);
}
