package com.apop.fraud.persistence;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FraudPolicyRepository extends JpaRepository<FraudPolicy, Integer> {

    FraudPolicy findFirstByActiveTrue();
}
