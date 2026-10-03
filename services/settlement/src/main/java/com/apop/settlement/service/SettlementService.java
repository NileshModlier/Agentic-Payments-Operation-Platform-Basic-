package com.apop.settlement.service;

import com.apop.observability.ApopMeters;
import com.apop.settlement.domain.ItemStatus;
import com.apop.settlement.domain.JournalPlan;
import com.apop.settlement.domain.SettlementItem;
import com.apop.settlement.domain.SettlementTimelineEntry;
import com.apop.settlement.ledger.HttpLedgerClient;
import com.apop.settlement.ledger.LedgerBusinessException;
import com.apop.settlement.ledger.LedgerUnavailableException;
import com.apop.settlement.payment.HttpPaymentClient;
import com.apop.settlement.payment.PaymentBusinessException;
import com.apop.settlement.payment.PaymentSnapshot;
import com.apop.settlement.payment.PaymentUnavailableException;
import com.apop.settlement.persistence.SettlementItemRepository;
import com.apop.settlement.persistence.SettlementTimelineRepository;
import com.apop.settlement.config.SettlementProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class SettlementService {

    private static final Logger log = LoggerFactory.getLogger(SettlementService.class);
    private static final Set<String> OPENABLE = Set.of("AUTHORIZED", "SETTLEMENT_PENDING");

    private final HttpPaymentClient payments;
    private final HttpLedgerClient ledger;
    private final SettlementStore store;
    private final SettlementItemRepository items;
    private final SettlementTimelineRepository timeline;
    private final SettlementProperties properties;
    private final ApopMeters meters;
    private final Clock clock;

    public SettlementService(
            HttpPaymentClient payments,
            HttpLedgerClient ledger,
            SettlementStore store,
            SettlementItemRepository items,
            SettlementTimelineRepository timeline,
            SettlementProperties properties,
            ApopMeters meters,
            Clock clock) {
        this.payments = payments;
        this.ledger = ledger;
        this.store = store;
        this.items = items;
        this.timeline = timeline;
        this.properties = properties;
        this.meters = meters;
        this.clock = clock;
    }

    public void settle(UUID paymentId, UUID commandId) {
        PaymentSnapshot payment = payments.get(paymentId);
        if (!OPENABLE.contains(payment.status()) && !"SETTLED".equals(payment.status())) {
            return;
        }
        if ("SETTLED".equals(payment.status())) {
            return;
        }
        if (payment.holdId() == null) {
            throw new LedgerBusinessException("SETTLEMENT_POST_FAILED", "Authorized payment has no hold");
        }
        SettlementItem item = store.start(payment, commandId == null ? payment.paymentId() : commandId);
        if (item.getItemStatus() == ItemStatus.POST_REFUSED) {
            if ("SETTLEMENT_PENDING".equals(payment.status()) || "AUTHORIZED".equals(payment.status())) {
                payments.progress(paymentId, "SETTLEMENT_FAILED", null, item.getReasonCode());
            }
            return;
        }
        if (item.getItemStatus() == ItemStatus.POSTED) {
            payments.progress(paymentId, "SETTLED", item.getJournalEntryId(), null);
            return;
        }
        if ("AUTHORIZED".equals(payment.status())) {
            payments.progress(paymentId, "SETTLEMENT_PENDING", null, null);
            store.notePending(item.getSettlementItemId());
        }
        try {
            UUID journalId = postOrRecover(item);
            SettlementItem posted = store.markPosted(item.getSettlementItemId(), journalId);
            payments.progress(paymentId, "SETTLED", posted.getJournalEntryId(), null);
            recordSettled(item);
            log.info("settlement.posted paymentId={} journalEntryId={}", paymentId, journalId);
        } catch (LedgerBusinessException exception) {
            SettlementItem refused = store.markRefused(item.getSettlementItemId(), exception.reasonCode());
            payments.progress(paymentId, "SETTLEMENT_FAILED", null, refused.getReasonCode());
            log.info("settlement.refused paymentId={} reasonCode={}", paymentId, exception.reasonCode());
        } catch (LedgerUnavailableException exception) {
            UUID recovered = recoverQuietly(item);
            if (recovered != null) {
                SettlementItem posted = store.markPosted(item.getSettlementItemId(), recovered);
                payments.progress(paymentId, "SETTLED", posted.getJournalEntryId(), null);
                recordSettled(item);
                return;
            }
            store.markUncertain(item.getSettlementItemId());
            log.info("settlement.uncertain paymentId={}", paymentId);
        }
    }

    public void retryUncertain() {
        Instant cutoff = clock.instant().minusMillis(properties.messaging().retryDelayMs());
        List<SettlementItem> due = items.findByItemStatusInAndUpdatedAtBefore(
                List.of(ItemStatus.POST_UNCERTAIN, ItemStatus.INCLUDED), cutoff);
        for (SettlementItem item : due) {
            try {
                settle(item.getPaymentId(), item.getCommandId());
            } catch (PaymentUnavailableException | LedgerUnavailableException exception) {
                log.info("settlement.retry-deferred paymentId={}", item.getPaymentId());
            } catch (PaymentBusinessException exception) {
                log.info("settlement.retry-stopped paymentId={} reasonCode={}",
                        item.getPaymentId(), exception.reasonCode());
            }
        }
    }

    public SettlementView view(UUID paymentId) {
        SettlementItem item = items.findByPaymentId(paymentId)
                .orElseThrow(() -> new SettlementNotFoundException(paymentId));
        List<SettlementView.TimelineEntry> entries = timeline
                .findByPaymentIdOrderByOccurredAtAscTimelineIdAsc(paymentId)
                .stream()
                .map(SettlementService::toEntry)
                .toList();
        return toView(item, entries);
    }

    private void recordSettled(SettlementItem item) {
        meters.paymentSettled(item.getRail());
        if (item.getCreatedAt() == null) {
            return;
        }
        Duration lag = Duration.between(item.getCreatedAt(), clock.instant());
        if (!lag.isNegative()) {
            meters.settlementLag(item.getRail(), lag);
        }
    }

    private UUID postOrRecover(SettlementItem item) {
        try {
            return ledger.post(item, JournalPlan.lines(
                    item.getRail(),
                    item.getDebtorAccountId(),
                    item.getCreditorAccountId(),
                    properties.ledger().clearingAccountId(),
                    item.getAmountMinor(),
                    item.getCurrency()));
        } catch (IllegalArgumentException exception) {
            throw new LedgerBusinessException("SETTLEMENT_POST_FAILED", exception.getMessage());
        } catch (LedgerUnavailableException exception) {
            UUID recovered = ledger.findPostedJournal(item.getPaymentId(), item.getLedgerIdempotencyKey());
            if (recovered != null) {
                return recovered;
            }
            throw exception;
        }
    }

    private UUID recoverQuietly(SettlementItem item) {
        try {
            return ledger.findPostedJournal(item.getPaymentId(), item.getLedgerIdempotencyKey());
        } catch (LedgerUnavailableException exception) {
            return null;
        }
    }

    private static SettlementView toView(SettlementItem item, List<SettlementView.TimelineEntry> entries) {
        return new SettlementView(
                item.getPaymentId(),
                item.getSettlementItemId(),
                item.getBatchId(),
                item.getItemStatus().name(),
                item.getJournalEntryId(),
                item.getAmountMinor(),
                item.getCurrency(),
                item.getReasonCode(),
                entries);
    }

    private static SettlementView.TimelineEntry toEntry(SettlementTimelineEntry entry) {
        return new SettlementView.TimelineEntry(
                entry.getEventType(),
                entry.getItemStatus(),
                entry.getSummaryCode(),
                entry.getOccurredAt());
    }
}
