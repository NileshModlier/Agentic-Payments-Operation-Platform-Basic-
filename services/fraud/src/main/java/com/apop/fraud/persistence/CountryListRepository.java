package com.apop.fraud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface CountryListRepository extends JpaRepository<CountryEntry, UUID> {

    Optional<CountryEntry> findByCountryHash(String countryHash);
}
