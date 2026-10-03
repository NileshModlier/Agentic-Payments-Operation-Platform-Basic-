package com.apop.ledger.persistence;

import com.apop.ledger.domain.JournalEntry;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface JournalEntryRepository extends JpaRepository<JournalEntry, UUID> {

    @EntityGraph(attributePaths = {"lines", "lines.account", "reversalOf"})
    Optional<JournalEntry> findByIdempotencyKey(String idempotencyKey);

    @EntityGraph(attributePaths = {"lines", "lines.account", "reversalOf"})
    @Query("select e from JournalEntry e where e.paymentId = :paymentId order by e.postedAt")
    List<JournalEntry> findByPaymentId(@Param("paymentId") UUID paymentId);
}
