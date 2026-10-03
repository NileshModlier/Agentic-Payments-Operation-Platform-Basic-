package com.apop.ledger.service;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.function.Supplier;

@Component
class IdempotentTransaction {

    private final TransactionTemplate transactionTemplate;

    IdempotentTransaction(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;
    }

    <T> T execute(Supplier<T> work, Supplier<T> onUniqueConflict) {
        try {
            return transactionTemplate.execute(status -> work.get());
        } catch (DataIntegrityViolationException conflict) {
            return transactionTemplate.execute(status -> onUniqueConflict.get());
        }
    }
}
