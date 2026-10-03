package com.apop.investigation.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "case_note")
public class CaseNote implements Persistable<UUID> {

    @Id
    private UUID noteId;
    private UUID caseId;
    private UUID sessionId;
    private String author;
    private String body;
    private Instant createdAt;

    @Transient
    private boolean newlyCreated = true;

    public static CaseNote create(UUID id, UUID caseId, UUID sessionId, String author, String body, Instant createdAt) {
        CaseNote row = new CaseNote();
        row.noteId = id;
        row.caseId = caseId;
        row.sessionId = sessionId;
        row.author = author;
        row.body = body;
        row.createdAt = createdAt;
        row.newlyCreated = true;
        return row;
    }

    @Override
    public UUID getId() {
        return noteId;
    }

    @Override
    public boolean isNew() {
        return newlyCreated;
    }

    @PostLoad
    @PostPersist
    void markNotNew() {
        newlyCreated = false;
    }

    public String getBody() {
        return body;
    }
}
