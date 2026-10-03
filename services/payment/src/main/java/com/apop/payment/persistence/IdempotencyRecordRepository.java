package com.apop.payment.persistence;

import com.apop.payment.domain.IdempotencyId;
import com.apop.payment.domain.IdempotencyRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRecordRepository extends JpaRepository<IdempotencyRecord, IdempotencyId> {
}
