package com.apop.ledger.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "account")
public class Account implements Persistable<UUID> {

    @Id
    @Column(name = "account_id", nullable = false)
    private UUID accountId;

    @Column(name = "legal_entity_id", nullable = false, length = 32)
    private String legalEntityId;

    @Enumerated(EnumType.STRING)
    @Column(name = "account_role", nullable = false, length = 16)
    private AccountRole accountRole;

    @Column(name = "gl_code", length = 32)
    private String glCode;

    @Column(name = "account_number_ciphertext")
    private byte[] accountNumberCiphertext;

    @Column(name = "account_number_last4", length = 4)
    private String accountNumberLast4;

    @Column(name = "account_number_hash", length = 64)
    private String accountNumberHash;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Enumerated(EnumType.STRING)
    @Column(name = "normal_balance", nullable = false, length = 6)
    private NormalBalance normalBalance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 16)
    private AccountStatus status;

    @Column(name = "ledger_balance_minor", nullable = false)
    private long ledgerBalanceMinor;

    @Column(name = "held_minor", nullable = false)
    private long heldMinor;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Account() {
    }

    public static Account open(
            UUID accountId,
            String legalEntityId,
            AccountRole accountRole,
            String glCode,
            String accountNumberLast4,
            String accountNumberHash,
            String currency,
            NormalBalance normalBalance,
            Instant openedAt) {
        Account account = new Account();
        account.accountId = accountId;
        account.legalEntityId = legalEntityId;
        account.accountRole = accountRole;
        account.glCode = glCode;
        account.accountNumberLast4 = accountNumberLast4;
        account.accountNumberHash = accountNumberHash;
        account.currency = currency;
        account.normalBalance = normalBalance;
        account.status = AccountStatus.OPEN;
        account.ledgerBalanceMinor = 0L;
        account.heldMinor = 0L;
        account.openedAt = openedAt;
        account.createdAt = openedAt;
        account.updatedAt = openedAt;
        return account;
    }

    public void placeHold(long amountMinor) {
        requireOpen();
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Hold amount must be positive");
        }
        if (isCustomerDeposit() && availableMinor() < amountMinor) {
            throw LedgerException.insufficientFunds();
        }
        heldMinor += amountMinor;
        touch();
    }

    public void releaseHeld(long amountMinor) {
        if (amountMinor <= 0 || heldMinor < amountMinor) {
            throw LedgerException.illegalState("Hold amount exceeds the reserved balance");
        }
        heldMinor -= amountMinor;
        touch();
    }

    public void apply(Direction direction, long amountMinor) {
        requireOpen();
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("Posting amount must be positive");
        }
        if (!currencyMatchesDirection(direction)) {
            ledgerBalanceMinor -= amountMinor;
        } else {
            ledgerBalanceMinor += amountMinor;
        }
        touch();
    }

    public void assertCustomerCanSpend() {
        if (isCustomerDeposit() && availableMinor() < 0) {
            throw LedgerException.insufficientFunds();
        }
    }

    public boolean decreasesOn(Direction direction) {
        return !currencyMatchesDirection(direction);
    }

    public long availableMinor() {
        return ledgerBalanceMinor - heldMinor;
    }

    public UUID getAccountId() {
        return accountId;
    }

    public String getLegalEntityId() {
        return legalEntityId;
    }

    public AccountRole getAccountRole() {
        return accountRole;
    }

    public String getGlCode() {
        return glCode;
    }

    public String getAccountNumberLast4() {
        return accountNumberLast4;
    }

    public String getCurrency() {
        return currency;
    }

    public NormalBalance getNormalBalance() {
        return normalBalance;
    }

    public AccountStatus getStatus() {
        return status;
    }

    public long getLedgerBalanceMinor() {
        return ledgerBalanceMinor;
    }

    public long getHeldMinor() {
        return heldMinor;
    }

    public Instant getOpenedAt() {
        return openedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    private boolean isCustomerDeposit() {
        return accountRole == AccountRole.CUSTOMER && normalBalance == NormalBalance.CREDIT;
    }

    private boolean currencyMatchesDirection(Direction direction) {
        return switch (normalBalance) {
            case CREDIT -> direction == Direction.CREDIT;
            case DEBIT -> direction == Direction.DEBIT;
        };
    }

    private void requireOpen() {
        if (status != AccountStatus.OPEN) {
            throw LedgerException.closedAccount();
        }
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    @Transient
    private boolean newlyOpened = true;

    @Override
    public UUID getId() {
        return accountId;
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
