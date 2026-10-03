package com.apop.fraud.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
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
@Table(name = "country_list")
public class CountryEntry implements Persistable<UUID> {

    @Id
    @Column(name = "list_entry_id")
    private UUID listEntryId;

    @Column(name = "list_type", nullable = false, length = 16)
    private String listType;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country_hash", nullable = false, length = 64)
    private String countryHash;

    @Column(name = "effective_from", nullable = false)
    private Instant effectiveFrom;

    @Column(name = "effective_to")
    private Instant effectiveTo;

    @Column(name = "created_by", nullable = false, length = 64)
    private String createdBy;

    @Column(name = "approved_by", length = 64)
    private String approvedBy;

    @Transient
    private boolean newlyCreated = true;

    protected CountryEntry() {
    }

    public static CountryEntry create(UUID id, String hash, Instant from, String createdBy) {
        CountryEntry entry = new CountryEntry();
        entry.listEntryId = id;
        entry.listType = "DENY";
        entry.countryHash = hash;
        entry.effectiveFrom = from;
        entry.createdBy = createdBy;
        return entry;
    }

    public void approve(String approver) {
        this.approvedBy = approver;
    }

    public UUID getListEntryId() {
        return listEntryId;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public boolean isEffective(Instant now) {
        return approvedBy != null
                && !effectiveFrom.isAfter(now)
                && (effectiveTo == null || effectiveTo.isAfter(now));
    }

    @Override
    public UUID getId() {
        return listEntryId;
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
