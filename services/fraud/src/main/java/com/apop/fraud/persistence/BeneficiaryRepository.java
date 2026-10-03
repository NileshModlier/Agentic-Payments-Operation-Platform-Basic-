package com.apop.fraud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface BeneficiaryRepository extends JpaRepository<BeneficiaryEntry, UUID> {

    Optional<BeneficiaryEntry> findByListTypeAndCreditorHash(String listType, String creditorHash);
}
