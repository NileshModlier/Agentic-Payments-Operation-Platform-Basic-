package com.apop.payment.messaging;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OutboxRepository extends JpaRepository<OutboxMessage, UUID> {

    Optional<OutboxMessage> findFirstByAggregateIdOrderByCreatedAtDescOutboxIdDesc(UUID aggregateId);

    List<OutboxMessage> findByRecordKeyOrderByCreatedAtAscOutboxIdAsc(String recordKey);
}
