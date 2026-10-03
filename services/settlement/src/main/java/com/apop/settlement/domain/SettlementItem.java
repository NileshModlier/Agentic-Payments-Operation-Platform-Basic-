package com.apop.settlement.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "settlement_item")
public class SettlementItem implements Persistable<UUID> {

    @Id
    @Column(name = "settlement_item_id", nullable = false)
    private UUID settlementItemId;

    @Column(name = "batch_id", nullable = false)
    private UUID batchId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "command_id", nullable = false)
    private UUID commandId;

    @Column(name = "hold_id", nullable = false)
    private UUID holdId;

    @Column(name = "ledger_idempotency_key", nullable = false, length = 128)
    private String ledgerIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_status", nullable = false, length = 24)
    private ItemStatus itemStatus;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "rail", nullable = false, length = 24)
    private String rail;

    @Column(name = "debtor_account_id", nullable = false)
    private UUID debtorAccountId;

    @Column(name = "creditor_account_id")
    private UUID creditorAccountId;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean newlyCreated = true;

    protected SettlementItem() {
    }

    public static SettlementItem include(
            UUID batchId,
            UUID paymentId,
            UUID commandId,
            UUID holdId,
            long amountMinor,
            String currency,
            LocalDate valueDate,
            String rail,
            UUID debtorAccountId,
            UUID creditorAccountId,
            Instant createdAt) {
        SettlementItem item = new SettlementItem();
        item.settlementItemId = UuidV7.generate();
        item.batchId = batchId;
        item.paymentId = paymentId;
        item.commandId = commandId;
        item.holdId = holdId;
        item.ledgerIdempotencyKey = "settle:" + paymentId;
        item.itemStatus = ItemStatus.INCLUDED;
        item.amountMinor = amountMinor;
        item.currency = currency;
        item.valueDate = valueDate;
        item.rail = rail;
        item.debtorAccountId = debtorAccountId;
        item.creditorAccountId = creditorAccountId;
        item.createdAt = createdAt;
        item.updatedAt = createdAt;
        return item;
    }

    public void markPosted(UUID journalEntryId, Instant updatedAt) {
        this.itemStatus = ItemStatus.POSTED;
        this.journalEntryId = journalEntryId;
        this.reasonCode = null;
        this.updatedAt = updatedAt;
    }

    public void markUncertain(Instant updatedAt) {
        if (this.itemStatus == ItemStatus.POSTED) {
            return;
        }
        this.itemStatus = ItemStatus.POST_UNCERTAIN;
        this.updatedAt = updatedAt;
    }

    public void markRefused(String reasonCode, Instant updatedAt) {
        this.itemStatus = ItemStatus.POST_REFUSED;
        this.reasonCode = reasonCode;
        this.updatedAt = updatedAt;
    }

    public UUID getSettlementItemId() {
        return settlementItemId;
    }

    public UUID getBatchId() {
        return batchId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public UUID getCommandId() {
        return commandId;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public String getLedgerIdempotencyKey() {
        return ledgerIdempotencyKey;
    }

    public ItemStatus getItemStatus() {
        return itemStatus;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency == null ? null : currency.trim();
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public String getRail() {
        return rail;
    }

    public UUID getDebtorAccountId() {
        return debtorAccountId;
    }

    public UUID getCreditorAccountId() {
        return creditorAccountId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    @Override
    public UUID getId() {
        return settlementItemId;
    }

    @Override
    public boolean isNew() {
        return newlyCreated;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newlyCreated = false;
    }
}
