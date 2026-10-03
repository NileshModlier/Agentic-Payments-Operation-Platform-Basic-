package com.apop.payment.persistence;

import com.apop.payment.domain.Payment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByLegalEntityIdAndDebtorAccountIdAndEndToEndId(
            String legalEntityId,
            UUID debtorAccountId,
            String endToEndId);
}
