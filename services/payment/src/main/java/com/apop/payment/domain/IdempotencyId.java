package com.apop.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.Objects;

@Embeddable
public class IdempotencyId implements Serializable {

    @Column(name = "legal_entity_id", nullable = false, length = 32)
    private String legalEntityId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    protected IdempotencyId() {
    }

    public IdempotencyId(String legalEntityId, String idempotencyKey) {
        this.legalEntityId = legalEntityId;
        this.idempotencyKey = idempotencyKey;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof IdempotencyId that)) {
            return false;
        }
        return Objects.equals(legalEntityId, that.legalEntityId)
                && Objects.equals(idempotencyKey, that.idempotencyKey);
    }

    @Override
    public int hashCode() {
        return Objects.hash(legalEntityId, idempotencyKey);
    }
}
