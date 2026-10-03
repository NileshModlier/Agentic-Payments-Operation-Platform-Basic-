package com.apop.ledger.service;

import com.apop.ledger.domain.Account;
import com.apop.ledger.domain.BalanceHold;
import com.apop.ledger.domain.HoldStatus;
import com.apop.ledger.domain.LedgerException;
import com.apop.ledger.domain.UuidV7;
import com.apop.ledger.persistence.AccountRepository;
import com.apop.ledger.persistence.BalanceHoldRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
class HoldServiceImpl implements HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldServiceImpl.class);

    private final AccountRepository accounts;
    private final BalanceHoldRepository holds;
    private final IdempotentTransaction transactions;

    HoldServiceImpl(AccountRepository accounts, BalanceHoldRepository holds, IdempotentTransaction transactions) {
        this.accounts = accounts;
        this.holds = holds;
        this.transactions = transactions;
    }

    @Override
    public HoldView place(PlaceHoldCommand command) {
        return transactions.execute(() -> placeNew(command), () -> recoverPlace(command));
    }

    @Override
    public HoldView release(ReleaseHoldCommand command) {
        return transactions.execute(() -> releaseNew(command), () -> releaseNew(command));
    }

    @Override
    @Transactional(readOnly = true)
    public List<HoldView> findByPayment(UUID paymentId) {
        return holds.findByPaymentIdOrderByCreatedAtAsc(paymentId).stream().map(hold -> toView(hold, false)).toList();
    }

    private HoldView placeNew(PlaceHoldCommand command) {
        return holds.findByIdempotencyKey(command.idempotencyKey())
                .map(existing -> replay(existing, command))
                .orElseGet(() -> insertHold(command));
    }

    private HoldView insertHold(PlaceHoldCommand command) {
        if (!command.expiresAt().isAfter(Instant.now())) {
            throw LedgerException.illegalState("Hold expiry must be in the future");
        }
        Account account = accounts.findByIdForUpdate(command.accountId())
                .orElseThrow(() -> LedgerException.notFound("Account was not found"));
        if (!account.getCurrency().trim().equals(command.currency())) {
            throw LedgerException.currencyMismatch();
        }
        account.placeHold(command.amountMinor());
        BalanceHold hold = BalanceHold.open(
                UuidV7.generate(),
                account,
                command.paymentId(),
                command.idempotencyKey(),
                command.amountMinor(),
                command.currency(),
                command.expiresAt(),
                Instant.now());
        holds.saveAndFlush(hold);
        accounts.saveAndFlush(account);
        log.info("hold.placed holdId={} paymentId={} correlationId={} replayed=false",
                hold.getHoldId(), hold.getPaymentId(), MDC.get("correlationId"));
        return toView(hold, false);
    }

    private HoldView recoverPlace(PlaceHoldCommand command) {
        return holds.findByIdempotencyKey(command.idempotencyKey())
                .map(existing -> replay(existing, command))
                .orElseThrow(() -> holds.findByPaymentId(command.paymentId())
                        .map(existing -> LedgerException.conflict("Payment already has a hold"))
                        .orElseGet(() -> LedgerException.conflict("Hold idempotency key already exists")));
    }

    private HoldView replay(BalanceHold existing, PlaceHoldCommand command) {
        if (!existing.matchesPlacement(command.accountId(), command.paymentId(), command.amountMinor(), command.currency())) {
            throw LedgerException.conflict("Idempotency key was already used for a different hold");
        }
        log.info("hold.placed holdId={} paymentId={} correlationId={} replayed=true",
                existing.getHoldId(), existing.getPaymentId(), MDC.get("correlationId"));
        return toView(existing, true);
    }

    private HoldView releaseNew(ReleaseHoldCommand command) {
        BalanceHold located = holds.findByIdempotencyKey(command.idempotencyKey())
                .orElseThrow(() -> LedgerException.notFound("Hold was not found"));
        accounts.findByIdForUpdate(located.getAccount().getAccountId())
                .orElseThrow(() -> LedgerException.notFound("Account was not found"));
        BalanceHold hold = holds.findByIdForUpdate(located.getHoldId())
                .orElseThrow(() -> LedgerException.notFound("Hold was not found"));
        if (hold.getStatus() == HoldStatus.RELEASED) {
            log.info("hold.released holdId={} paymentId={} correlationId={} replayed=true",
                    hold.getHoldId(), hold.getPaymentId(), MDC.get("correlationId"));
            return toView(hold, true);
        }
        if (hold.getStatus() != HoldStatus.OPEN) {
            throw LedgerException.illegalState("Hold is already consumed");
        }
        Account account = accounts.findByIdForUpdate(hold.getAccount().getAccountId())
                .orElseThrow(() -> LedgerException.notFound("Account was not found"));
        account.releaseHeld(hold.getAmountMinor());
        hold.release(Instant.now());
        accounts.saveAndFlush(account);
        holds.saveAndFlush(hold);
        log.info("hold.released holdId={} paymentId={} correlationId={} replayed=false",
                hold.getHoldId(), hold.getPaymentId(), MDC.get("correlationId"));
        return toView(hold, false);
    }

    private HoldView toView(BalanceHold hold, boolean replayed) {
        Account account = hold.getAccount();
        return new HoldView(
                hold.getHoldId(),
                account.getAccountId(),
                hold.getPaymentId(),
                hold.getIdempotencyKey(),
                hold.getAmountMinor(),
                hold.getCurrency().trim(),
                hold.getStatus(),
                "COMPLETED",
                null,
                replayed,
                account.getLedgerBalanceMinor(),
                account.getHeldMinor(),
                account.availableMinor(),
                hold.getExpiresAt(),
                hold.getCreatedAt(),
                hold.getReleasedAt());
    }
}
