package com.apop.payment.persistence;

import com.apop.payment.domain.PartyRole;
import com.apop.payment.domain.PaymentParty;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface PaymentPartyRepository extends JpaRepository<PaymentParty, UUID> {

    Optional<PaymentParty> findByPaymentIdAndRole(UUID paymentId, PartyRole role);
}
