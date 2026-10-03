package com.apop.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

@Entity
@Table(name = "fraud_policy")
public class FraudPolicy {

    @Id
    @Column(name = "policy_version")
    private Integer policyVersion;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "document", nullable = false)
    private String document;

    @Column(name = "active", nullable = false)
    private boolean active;

    @Column(name = "activated_at", nullable = false)
    private Instant activatedAt;

    protected FraudPolicy() {
    }

    public Integer getPolicyVersion() {
        return policyVersion;
    }

    public String getDocument() {
        return document;
    }

    public boolean isActive() {
        return active;
    }
}
