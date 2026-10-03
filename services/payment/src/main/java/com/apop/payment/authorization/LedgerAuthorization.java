package com.apop.payment.authorization;

import com.apop.payment.domain.Payment;
import com.apop.payment.ledger.LedgerClient;
import com.apop.payment.ledger.LedgerUnavailableException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

@Component
public class LedgerAuthorization {

    private final LedgerClient ledger;

    public LedgerAuthorization(LedgerClient ledger) {
        this.ledger = ledger;
    }

    public UUID placeHold(Payment payment, Clock clock) {
        LedgerClient.HoldSnapshot hold = ledger.placeHold(new LedgerClient.HoldRequest(
                "hold:" + payment.getPaymentId(),
                payment.getPaymentId(),
                payment.getDebtorAccountId(),
                payment.getAmountMinor(),
                payment.getCurrency(),
                clock.instant().plus(Duration.ofDays(2))));
        if (hold.holdId() == null) {
            throw new LedgerUnavailableException("Ledger did not return a hold");
        }
        return hold.holdId();
    }

    public UUID findHold(UUID paymentId) {
        LedgerClient.HoldSnapshot hold = ledger.findHold(paymentId);
        return hold == null ? null : hold.holdId();
    }
}
