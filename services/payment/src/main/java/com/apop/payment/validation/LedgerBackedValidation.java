package com.apop.payment.validation;

import com.apop.payment.config.PaymentProperties;
import com.apop.payment.domain.Rail;
import com.apop.payment.ledger.LedgerClient;
import com.apop.payment.ledger.LedgerNotFoundException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.UUID;

@Component
public class LedgerBackedValidation implements ValidationPort {

    private final LedgerClient ledger;
    private final ScreeningList screening;
    private final PaymentProperties properties;
    private final Clock clock;

    public LedgerBackedValidation(
            LedgerClient ledger,
            ScreeningList screening,
            PaymentProperties properties,
            Clock clock) {
        this.ledger = ledger;
        this.screening = screening;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public ValidationOutcome validate(ValidationRequest request) {
        if (request.amountMinor() > properties.validation().maxAmountMinor()) {
            return ValidationOutcome.reject("VALIDATION_LIMIT_EXCEEDED");
        }
        if (screening.deniesAccount(request.debtorAccountId())
                || screening.deniesAccount(request.creditorAccountId())
                || screening.deniesName(request.creditorName())) {
            return ValidationOutcome.reject("VALIDATION_SANCTIONS_REJECT");
        }
        if (request.rail() == Rail.BOOK && request.creditorAccountId() == null) {
            return ValidationOutcome.reject("VALIDATION_CREDITOR_REQUIRED");
        }
        if (request.rail() != Rail.BOOK && (request.creditorName() == null || request.creditorName().isBlank())) {
            return ValidationOutcome.reject("VALIDATION_CREDITOR_REQUIRED");
        }
        if (request.creditorAccountId() != null && request.creditorAccountId().equals(request.debtorAccountId())) {
            return ValidationOutcome.reject("VALIDATION_SAME_ACCOUNT");
        }
        ZoneId zone = ZoneId.of(properties.validation().zone());
        LocalDate today = LocalDate.now(clock.withZone(zone));
        if (request.requestedValueDate().isBefore(today)) {
            return ValidationOutcome.reject("VALIDATION_VALUE_DATE");
        }
        ValidationOutcome debtor = requireOpen(request.debtorAccountId(), request.currency());
        if (debtor != null) {
            return debtor;
        }
        if (request.rail() == Rail.BOOK) {
            ValidationOutcome creditor = requireOpen(request.creditorAccountId(), request.currency());
            if (creditor != null) {
                return creditor;
            }
        }
        return ValidationOutcome.pass(rollForward(request.requestedValueDate(), today, zone));
    }

    private ValidationOutcome requireOpen(UUID accountId, String currency) {
        try {
            LedgerClient.AccountSnapshot account = ledger.getAccount(accountId);
            if (!"OPEN".equals(account.status())) {
                return ValidationOutcome.reject("VALIDATION_ACCOUNT_CLOSED");
            }
            String accountCurrency = account.currency() == null ? "" : account.currency().trim();
            if (!currency.equals(accountCurrency)) {
                return ValidationOutcome.reject("VALIDATION_CURRENCY_MISMATCH");
            }
            return null;
        } catch (LedgerNotFoundException exception) {
            return ValidationOutcome.reject("VALIDATION_ACCOUNT_NOT_FOUND");
        }
    }

    private LocalDate rollForward(LocalDate requested, LocalDate today, ZoneId zone) {
        LocalDate cursor = requested;
        LocalTime now = LocalTime.now(clock.withZone(zone));
        if (cursor.equals(today) && now.isAfter(properties.validation().cutoff())) {
            cursor = cursor.plusDays(1);
        }
        while (cursor.getDayOfWeek() == DayOfWeek.SATURDAY || cursor.getDayOfWeek() == DayOfWeek.SUNDAY) {
            cursor = cursor.plusDays(1);
        }
        return cursor;
    }
}
