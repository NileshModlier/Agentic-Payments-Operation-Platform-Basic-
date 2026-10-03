package com.apop.ledger.persistence;

import com.apop.ledger.domain.BalanceHold;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BalanceHoldRepository extends JpaRepository<BalanceHold, UUID> {

    Optional<BalanceHold> findByIdempotencyKey(String idempotencyKey);

    Optional<BalanceHold> findByPaymentId(UUID paymentId);

    @EntityGraph(attributePaths = "account")
    List<BalanceHold> findByPaymentIdOrderByCreatedAtAsc(UUID paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from BalanceHold h where h.holdId = :id")
    Optional<BalanceHold> findByIdForUpdate(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select h from BalanceHold h where h.idempotencyKey = :key")
    Optional<BalanceHold> findByIdempotencyKeyForUpdate(@Param("key") String key);
}
