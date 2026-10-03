package com.apop.ledger.domain;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.Immutable;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "journal_entry")
@Immutable
public class JournalEntry implements Persistable<UUID> {

    @Id
    @Column(name = "journal_entry_id", nullable = false)
    private UUID journalEntryId;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false, length = 16)
    private EntryType entryType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "reversal_of_entry_id")
    private JournalEntry reversalOf;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "posted_at", nullable = false)
    private Instant postedAt;

    @OneToMany(mappedBy = "entry", cascade = CascadeType.PERSIST, orphanRemoval = false)
    @OrderBy("journalLineId")
    private List<JournalLine> lines = new ArrayList<>();

    protected JournalEntry() {
    }

    public static JournalEntry create(
            UUID journalEntryId,
            UUID paymentId,
            String idempotencyKey,
            EntryType entryType,
            JournalEntry reversalOf,
            LocalDate valueDate,
            Instant postedAt) {
        JournalEntry entry = new JournalEntry();
        entry.journalEntryId = journalEntryId;
        entry.paymentId = paymentId;
        entry.idempotencyKey = idempotencyKey;
        entry.entryType = entryType;
        entry.reversalOf = reversalOf;
        entry.valueDate = valueDate;
        entry.postedAt = postedAt;
        return entry;
    }

    public void addLine(JournalLine line) {
        lines.add(line);
    }

    public List<JournalLine> getLines() {
        return Collections.unmodifiableList(lines);
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public EntryType getEntryType() {
        return entryType;
    }

    public JournalEntry getReversalOf() {
        return reversalOf;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public Instant getPostedAt() {
        return postedAt;
    }

    @Transient
    private boolean newlyPosted = true;

    @Override
    public UUID getId() {
        return journalEntryId;
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
