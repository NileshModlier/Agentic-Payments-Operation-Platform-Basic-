package com.apop.ledger.service;

import com.apop.ledger.domain.Account;
import com.apop.ledger.domain.BalanceHold;
import com.apop.ledger.domain.Direction;
import com.apop.ledger.domain.EntryType;
import com.apop.ledger.domain.HoldStatus;
import com.apop.ledger.domain.JournalEntry;
import com.apop.ledger.domain.JournalLine;
import com.apop.ledger.domain.LedgerException;
import com.apop.observability.ApopMeters;
import com.apop.ledger.domain.UuidV7;
import com.apop.ledger.persistence.AccountRepository;
import com.apop.ledger.persistence.BalanceHoldRepository;
import com.apop.ledger.persistence.JournalEntryRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
class PostingServiceImpl implements PostingService {

    private static final Logger log = LoggerFactory.getLogger(PostingServiceImpl.class);

    private final AccountRepository accounts;
    private final BalanceHoldRepository holds;
    private final JournalEntryRepository entries;
    private final IdempotentTransaction transactions;
    private final ApopMeters meters;

    PostingServiceImpl(
            AccountRepository accounts,
            BalanceHoldRepository holds,
            JournalEntryRepository entries,
            IdempotentTransaction transactions,
            ApopMeters meters) {
        this.accounts = accounts;
        this.holds = holds;
        this.entries = entries;
        this.transactions = transactions;
        this.meters = meters;
    }

    @Override
    public PostingView post(PostJournalCommand command) {
        return transactions.execute(() -> postNew(command), () -> recover(command));
    }

    @Override
    @Transactional(readOnly = true)
    public List<PostingView> findByPayment(UUID paymentId) {
        return entries.findByPaymentId(paymentId).stream().map(entry -> toView(entry, false)).toList();
    }

    private PostingView postNew(PostJournalCommand command) {
        return entries.findByIdempotencyKey(command.idempotencyKey())
                .map(existing -> replay(existing, command))
                .orElseGet(() -> insertPosting(command));
    }

    private PostingView insertPosting(PostJournalCommand command) {
        validateShape(command);
        Map<UUID, Account> locked = lockAccounts(command);
        BalanceHold hold = lockHold(command, locked);
        JournalEntry reversalOf = resolveReversal(command);
        for (PostJournalCommand.Line line : command.lines()) {
            Account account = locked.get(line.accountId());
            if (!account.getCurrency().trim().equals(line.currency())) {
                throw LedgerException.currencyMismatch();
            }
            account.apply(line.direction(), line.amountMinor());
        }
        if (hold != null) {
            Account held = locked.get(hold.getAccount().getAccountId());
            long decreasing = decreasingAmount(command, held);
            if (decreasing != hold.getAmountMinor()) {
                throw LedgerException.illegalState("Posting does not consume the full hold");
            }
            held.releaseHeld(hold.getAmountMinor());
            hold.consume(Instant.now());
        }
        locked.values().forEach(Account::assertCustomerCanSpend);
        Instant postedAt = Instant.now();
        JournalEntry entry = JournalEntry.create(
                UuidV7.generate(),
                command.paymentId(),
                command.idempotencyKey(),
                command.entryType(),
                reversalOf,
                command.valueDate(),
                postedAt);
        for (PostJournalCommand.Line line : command.lines()) {
            JournalLine journalLine = new JournalLine(
                    UuidV7.generate(),
                    entry,
                    locked.get(line.accountId()),
                    line.direction(),
                    line.amountMinor(),
                    line.currency(),
                    postedAt);
            entry.addLine(journalLine);
        }
        entries.saveAndFlush(entry);
        accounts.saveAllAndFlush(locked.values());
        if (hold != null) {
            holds.saveAndFlush(hold);
        }
        log.info("journal.posted journalEntryId={} paymentId={} correlationId={} replayed=false",
                entry.getJournalEntryId(), entry.getPaymentId(), MDC.get("correlationId"));
        meters.ledgerPost(command.entryType().name(), "posted");
        return toView(entry, false);
    }

    private PostingView recover(PostJournalCommand command) {
        return entries.findByIdempotencyKey(command.idempotencyKey())
                .map(existing -> replay(existing, command))
                .orElseThrow(() -> LedgerException.conflict("Posting idempotency key already exists"));
    }

    private PostingView replay(JournalEntry existing, PostJournalCommand command) {
        if (!fingerprint(existing).equals(fingerprint(command))) {
            meters.idempotencyReplay("conflict");
            throw LedgerException.conflict("Idempotency key was already used for a different posting");
        }
        log.info("journal.posted journalEntryId={} paymentId={} correlationId={} replayed=true",
                existing.getJournalEntryId(), existing.getPaymentId(), MDC.get("correlationId"));
        meters.ledgerPost(command.entryType().name(), "replayed");
        meters.idempotencyReplay("replay");
        return toView(existing, true);
    }

    private Map<UUID, Account> lockAccounts(PostJournalCommand command) {
        List<UUID> ids = command.lines().stream()
                .map(PostJournalCommand.Line::accountId)
                .distinct()
                .sorted()
                .toList();
        Map<UUID, Account> locked = new LinkedHashMap<>();
        for (UUID id : ids) {
            Account account = accounts.findByIdForUpdate(id)
                    .orElseThrow(() -> LedgerException.notFound("Account was not found"));
            locked.put(id, account);
        }
        return locked;
    }

    private BalanceHold lockHold(PostJournalCommand command, Map<UUID, Account> locked) {
        if (command.holdId() == null) {
            return null;
        }
        BalanceHold hold = holds.findByIdForUpdate(command.holdId())
                .orElseThrow(() -> LedgerException.notFound("Hold was not found"));
        if (hold.getStatus() != HoldStatus.OPEN) {
            throw LedgerException.illegalState("Hold is not open");
        }
        if (command.paymentId() != null && !hold.getPaymentId().equals(command.paymentId())) {
            throw LedgerException.illegalState("Hold belongs to a different payment");
        }
        if (!locked.containsKey(hold.getAccount().getAccountId())) {
            throw LedgerException.illegalState("Posting does not include the held account");
        }
        if (!hold.getCurrency().trim().equals(command.lines().getFirst().currency())) {
            throw LedgerException.currencyMismatch();
        }
        return hold;
    }

    private JournalEntry resolveReversal(PostJournalCommand command) {
        if (command.entryType() != EntryType.REVERSAL) {
            return null;
        }
        return entries.findById(command.reversalOfEntryId())
                .orElseThrow(() -> LedgerException.notFound("Original journal entry was not found"));
    }

    private static long decreasingAmount(PostJournalCommand command, Account account) {
        return command.lines().stream()
                .filter(line -> line.accountId().equals(account.getAccountId()))
                .filter(line -> account.decreasesOn(line.direction()))
                .mapToLong(PostJournalCommand.Line::amountMinor)
                .sum();
    }

    private static void validateShape(PostJournalCommand command) {
        if (command.lines() == null || command.lines().size() < 2) {
            throw LedgerException.unbalanced();
        }
        String currency = command.lines().getFirst().currency();
        long debits = 0L;
        long credits = 0L;
        for (PostJournalCommand.Line line : command.lines()) {
            if (line.amountMinor() <= 0 || !currency.equals(line.currency())) {
                throw LedgerException.unbalanced();
            }
            if (line.direction() == Direction.DEBIT) {
                debits += line.amountMinor();
            } else {
                credits += line.amountMinor();
            }
        }
        if (debits != credits) {
            throw LedgerException.unbalanced();
        }
        if (command.entryType() == EntryType.REVERSAL && command.reversalOfEntryId() == null) {
            throw LedgerException.illegalState("A reversal must name the original journal entry");
        }
        if (command.entryType() == EntryType.POSTING && command.reversalOfEntryId() != null) {
            throw LedgerException.illegalState("A posting cannot reference another entry");
        }
    }

    private static String fingerprint(JournalEntry entry) {
        List<String> parts = new ArrayList<>();
        parts.add(entry.getEntryType().name());
        parts.add(String.valueOf(entry.getPaymentId()));
        parts.add(String.valueOf(entry.getValueDate()));
        parts.add(entry.getReversalOf() == null ? "" : entry.getReversalOf().getJournalEntryId().toString());
        entry.getLines().stream()
                .sorted(Comparator.comparing(line -> line.getAccount().getAccountId().toString() + line.getDirection() + line.getAmountMinor()))
                .forEach(line -> parts.add(line.getAccount().getAccountId() + ":" + line.getDirection() + ":" + line.getAmountMinor() + ":" + line.getCurrency().trim()));
        return String.join("|", parts);
    }

    private static String fingerprint(PostJournalCommand command) {
        List<String> parts = new ArrayList<>();
        parts.add(command.entryType().name());
        parts.add(String.valueOf(command.paymentId()));
        parts.add(String.valueOf(command.valueDate()));
        parts.add(command.reversalOfEntryId() == null ? "" : command.reversalOfEntryId().toString());
        command.lines().stream()
                .sorted(Comparator.comparing(line -> line.accountId().toString() + line.direction() + line.amountMinor()))
                .forEach(line -> parts.add(line.accountId() + ":" + line.direction() + ":" + line.amountMinor() + ":" + line.currency()));
        return String.join("|", parts);
    }

    private static PostingView toView(JournalEntry entry, boolean replayed) {
        List<PostingView.LineView> lines = entry.getLines().stream()
                .map(line -> new PostingView.LineView(
                        line.getJournalLineId(),
                        line.getAccount().getAccountId(),
                        line.getDirection(),
                        line.getAmountMinor(),
                        line.getCurrency().trim()))
                .toList();
        return new PostingView(
                entry.getJournalEntryId(),
                entry.getPaymentId(),
                entry.getIdempotencyKey(),
                entry.getEntryType(),
                entry.getReversalOf() == null ? null : entry.getReversalOf().getJournalEntryId(),
                entry.getValueDate(),
                entry.getPostedAt(),
                "COMPLETED",
                replayed,
                lines);
    }
}
