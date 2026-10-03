package com.apop.settlement.service;

import com.apop.settlement.config.SettlementProperties;
import com.apop.settlement.domain.ItemStatus;
import com.apop.settlement.domain.SettlementBatch;
import com.apop.settlement.domain.SettlementItem;
import com.apop.settlement.domain.SettlementTimelineEntry;
import com.apop.settlement.domain.UuidV7;
import com.apop.settlement.messaging.OutboxMessage;
import com.apop.settlement.messaging.OutboxRepository;
import com.apop.settlement.payment.PaymentSnapshot;
import com.apop.settlement.persistence.SettlementBatchRepository;
import com.apop.settlement.persistence.SettlementItemRepository;
import com.apop.settlement.persistence.SettlementTimelineRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class SettlementStore {

    private final SettlementItemRepository items;
    private final SettlementBatchRepository batches;
    private final SettlementTimelineRepository timeline;
    private final OutboxRepository outbox;
    private final SettlementProperties properties;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transactions;

    public SettlementStore(
            SettlementItemRepository items,
            SettlementBatchRepository batches,
            SettlementTimelineRepository timeline,
            OutboxRepository outbox,
            SettlementProperties properties,
            ObjectMapper objectMapper,
            Clock clock,
            TransactionTemplate transactions) {
        this.items = items;
        this.batches = batches;
        this.timeline = timeline;
        this.outbox = outbox;
        this.properties = properties;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transactions = transactions;
    }

    public Optional<SettlementItem> find(UUID paymentId) {
        return items.findByPaymentId(paymentId);
    }

    public SettlementItem start(PaymentSnapshot payment, UUID commandId) {
        Optional<SettlementItem> existing = items.findByPaymentId(payment.paymentId());
        if (existing.isPresent()) {
            return existing.get();
        }
        try {
            return transactions.execute(status -> insert(payment, commandId));
        } catch (DataIntegrityViolationException conflict) {
            return items.findByPaymentId(payment.paymentId()).orElseThrow();
        }
    }

    private SettlementItem insert(PaymentSnapshot payment, UUID commandId) {
        Instant now = clock.instant();
        SettlementBatch batch = batches.save(SettlementBatch.lock(
                payment.rail(), payment.valueDate(), payment.amountMinor(), payment.currency(), now));
        SettlementItem item = items.saveAndFlush(SettlementItem.include(
                batch.getBatchId(),
                payment.paymentId(),
                commandId,
                payment.holdId(),
                payment.amountMinor(),
                payment.currency(),
                payment.valueDate(),
                payment.rail(),
                payment.debtorAccountId(),
                payment.creditorAccountId(),
                now));
        timeline.save(SettlementTimelineEntry.record(
                item.getSettlementItemId(),
                item.getPaymentId(),
                "SettlementRequested",
                ItemStatus.INCLUDED.name(),
                "SETTLEMENT_REQUESTED",
                now));
        return item;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementItem notePending(UUID itemId) {
        SettlementItem item = locked(itemId);
        timeline.save(SettlementTimelineEntry.record(
                item.getSettlementItemId(),
                item.getPaymentId(),
                "SettlementPending",
                ItemStatus.INCLUDED.name(),
                "SETTLEMENT_PENDING",
                clock.instant()));
        return item;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementItem markPosted(UUID itemId, UUID journalEntryId) {
        SettlementItem item = locked(itemId);
        if (item.getItemStatus() == ItemStatus.POSTED) {
            return item;
        }
        Instant now = clock.instant();
        item.markPosted(journalEntryId, now);
        batches.findById(item.getBatchId()).ifPresent(batch -> {
            batch.markPosted();
            batches.save(batch);
        });
        timeline.save(SettlementTimelineEntry.record(
                item.getSettlementItemId(),
                item.getPaymentId(),
                "SettlementItemPosted",
                ItemStatus.POSTED.name(),
                "POSTED",
                now));
        enqueue(item, now);
        return items.save(item);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementItem markUncertain(UUID itemId) {
        SettlementItem item = locked(itemId);
        if (item.getItemStatus() == ItemStatus.POSTED || item.getItemStatus() == ItemStatus.POST_REFUSED) {
            return item;
        }
        Instant now = clock.instant();
        item.markUncertain(now);
        timeline.save(SettlementTimelineEntry.record(
                item.getSettlementItemId(),
                item.getPaymentId(),
                "SettlementUncertain",
                ItemStatus.POST_UNCERTAIN.name(),
                "POST_UNCERTAIN",
                now));
        enqueue(item, now);
        return items.save(item);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SettlementItem markRefused(UUID itemId, String reasonCode) {
        SettlementItem item = locked(itemId);
        if (item.getItemStatus() == ItemStatus.POSTED) {
            return item;
        }
        Instant now = clock.instant();
        item.markRefused(reasonCode, now);
        batches.findById(item.getBatchId()).ifPresent(batch -> {
            batch.markFailed();
            batches.save(batch);
        });
        timeline.save(SettlementTimelineEntry.record(
                item.getSettlementItemId(),
                item.getPaymentId(),
                "SettlementRefused",
                ItemStatus.POST_REFUSED.name(),
                reasonCode == null ? "POST_REFUSED" : reasonCode,
                now));
        enqueue(item, now);
        return items.save(item);
    }

    private SettlementItem locked(UUID itemId) {
        return items.findByIdForUpdate(itemId).orElseThrow();
    }

    private void enqueue(SettlementItem item, Instant occurredAt) {
        UUID eventId = UuidV7.generate();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("settlementItemId", item.getSettlementItemId());
        data.put("batchId", item.getBatchId());
        data.put("paymentId", item.getPaymentId());
        data.put("rail", item.getRail());
        data.put("itemStatus", item.getItemStatus().name());
        data.put("amountMinor", item.getAmountMinor());
        data.put("currency", item.getCurrency());
        data.put("valueDate", item.getValueDate());
        data.put("journalEntryId", item.getJournalEntryId());
        data.put("reasonCode", item.getReasonCode());
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("specversion", "1.0");
        envelope.put("id", eventId);
        envelope.put("source", "urn:apop:settlement-service");
        envelope.put("type", properties.messaging().cloudEventType());
        envelope.put("subject", item.getPaymentId().toString());
        envelope.put("time", occurredAt);
        envelope.put("datacontenttype", "application/json");
        envelope.put("data", data);
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("ce_id", eventId.toString());
        headers.put("ce_type", properties.messaging().cloudEventType());
        headers.put("content-type", "application/cloudevents+json");
        try {
            outbox.save(OutboxMessage.create(
                    eventId,
                    item.getPaymentId(),
                    properties.messaging().itemTopic(),
                    properties.messaging().cloudEventType(),
                    objectMapper.writeValueAsBytes(envelope),
                    objectMapper.writeValueAsString(headers),
                    occurredAt));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not write the settlement item envelope", exception);
        }
    }
}
