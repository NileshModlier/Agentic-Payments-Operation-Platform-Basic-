package com.apop.settlement.persistence;

import com.apop.settlement.domain.ItemStatus;
import com.apop.settlement.domain.SettlementItem;
import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SettlementItemRepository extends JpaRepository<SettlementItem, UUID> {

    Optional<SettlementItem> findByPaymentId(UUID paymentId);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select item from SettlementItem item where item.settlementItemId = :id")
    Optional<SettlementItem> findByIdForUpdate(UUID id);

    List<SettlementItem> findByItemStatusInAndUpdatedAtBefore(List<ItemStatus> statuses, Instant updatedBefore);
}
