package com.apop.ledger.service;

import com.apop.ledger.domain.Account;
import com.apop.ledger.domain.AccountRole;
import com.apop.ledger.domain.LedgerException;
import com.apop.ledger.domain.NormalBalance;
import com.apop.ledger.domain.UuidV7;
import com.apop.ledger.persistence.AccountRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
class AccountServiceImpl implements AccountService {

    private static final Logger log = LoggerFactory.getLogger(AccountServiceImpl.class);

    private final AccountRepository accounts;
    private final AccountNumberHasher hasher;

    AccountServiceImpl(AccountRepository accounts, AccountNumberHasher hasher) {
        this.accounts = accounts;
        this.hasher = hasher;
    }

    @Override
    public AccountView open(OpenAccountCommand command) {
        validate(command);
        String last4 = null;
        String hash = null;
        if (command.accountRole() == AccountRole.CUSTOMER) {
            last4 = hasher.last4(command.accountNumber());
            hash = hasher.hash(command.accountNumber());
        }
        Instant now = Instant.now();
        Account account = Account.open(
                UuidV7.generate(),
                command.legalEntityId().trim(),
                command.accountRole(),
                blankToNull(command.glCode()),
                last4,
                hash,
                command.currency().toUpperCase(),
                command.normalBalance(),
                now);
        Account saved = accounts.save(account);
        log.info("account.opened accountId={} correlationId={}", saved.getAccountId(), MDC.get("correlationId"));
        return toView(saved);
    }

    @Override
    public AccountView get(UUID accountId) {
        return toView(accounts.findById(accountId).orElseThrow(() -> LedgerException.notFound("Account was not found")));
    }

    static AccountView toView(Account account) {
        return new AccountView(
                account.getAccountId(),
                account.getLegalEntityId(),
                account.getAccountRole(),
                account.getGlCode(),
                account.getAccountNumberLast4(),
                account.getCurrency().trim(),
                account.getNormalBalance(),
                account.getStatus(),
                account.getLedgerBalanceMinor(),
                account.getHeldMinor(),
                account.availableMinor(),
                account.getOpenedAt(),
                account.getCreatedAt(),
                account.getUpdatedAt());
    }

    private static void validate(OpenAccountCommand command) {
        if (command.accountRole() == AccountRole.CUSTOMER) {
            if (command.accountNumber() == null || command.accountNumber().isBlank()) {
                throw new LedgerException(HttpStatus.BAD_REQUEST, "schema-invalid", "VALIDATION_SCHEMA", "Customer account number is required");
            }
            if (command.normalBalance() != NormalBalance.CREDIT) {
                throw new LedgerException(HttpStatus.BAD_REQUEST, "schema-invalid", "VALIDATION_SCHEMA", "Customer deposits are credit-normal");
            }
        }
        if (command.accountRole() == AccountRole.INTERNAL_GL && (command.glCode() == null || command.glCode().isBlank())) {
            throw new LedgerException(HttpStatus.BAD_REQUEST, "schema-invalid", "VALIDATION_SCHEMA", "Internal accounts require a GL code");
        }
    }

    private static String blankToNull(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
