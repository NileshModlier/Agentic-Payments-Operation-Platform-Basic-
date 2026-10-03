package com.apop.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "balance_hold")
public class BalanceHold implements Persistable<UUID> {

    @Id
    @Column(name = "hold_id", nullable = false)
    private UUID holdId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private HoldStatus status;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    protected BalanceHold() {
    }

    public static BalanceHold open(
            UUID holdId,
            Account account,
            UUID paymentId,
            String idempotencyKey,
            long amountMinor,
            String currency,
            Instant expiresAt,
            Instant createdAt) {
        BalanceHold hold = new BalanceHold();
        hold.holdId = holdId;
        hold.account = account;
        hold.paymentId = paymentId;
        hold.idempotencyKey = idempotencyKey;
        hold.amountMinor = amountMinor;
        hold.currency = currency;
        hold.status = HoldStatus.OPEN;
        hold.expiresAt = expiresAt;
        hold.createdAt = createdAt;
        return hold;
    }

    public void release(Instant when) {
        if (status != HoldStatus.OPEN) {
            throw LedgerException.illegalState("Only an open hold can be released");
        }
        status = HoldStatus.RELEASED;
        releasedAt = when;
    }

    public void consume(Instant when) {
        if (status != HoldStatus.OPEN) {
            throw LedgerException.illegalState("Only an open hold can be consumed");
        }
        status = HoldStatus.CONSUMED;
        releasedAt = when;
    }

    public boolean matchesPlacement(UUID accountId, UUID paymentId, long amountMinor, String currency) {
        return this.account.getAccountId().equals(accountId)
                && this.paymentId.equals(paymentId)
                && this.amountMinor == amountMinor
                && this.currency.trim().equals(currency);
    }

    public UUID getHoldId() {
        return holdId;
    }

    public Account getAccount() {
        return account;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public HoldStatus getStatus() {
        return status;
    }

    public Instant getExpiresAt() {
        return expiresAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getReleasedAt() {
        return releasedAt;
    }

    @Transient
    private boolean newlyOpened = true;

    @Override
    public UUID getId() {
        return holdId;
    }

    @Override
    public boolean isNew() {
        return newlyOpened;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newlyOpened = false;
    }
}
