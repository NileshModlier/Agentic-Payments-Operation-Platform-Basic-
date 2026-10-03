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
import org.hibernate.annotations.Immutable;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "journal_line")
@Immutable
public class JournalLine implements Persistable<UUID> {

    @Id
    @Column(name = "journal_line_id", nullable = false)
    private UUID journalLineId;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "journal_entry_id", nullable = false)
    private JournalEntry entry;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction", nullable = false, length = 6)
    private Direction direction;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    protected JournalLine() {
    }

    public JournalLine(
            UUID journalLineId,
            JournalEntry entry,
            Account account,
            Direction direction,
            long amountMinor,
            String currency,
            Instant postedAt) {
        this.journalLineId = journalLineId;
        this.entry = entry;
        this.account = account;
        this.direction = direction;
        this.amountMinor = amountMinor;
        this.currency = currency;
        this.postedAt = postedAt;
    }

    public UUID getJournalLineId() {
        return journalLineId;
    }

    public JournalEntry getEntry() {
        return entry;
    }

    public Account getAccount() {
        return account;
    }

    public Direction getDirection() {
        return direction;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    @Transient
    private boolean newlyPosted = true;

    @Override
    public UUID getId() {
        return journalLineId;
    }

    @Override
    public boolean isNew() {
        return newlyPosted;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newlyPosted = false;
    }
}
