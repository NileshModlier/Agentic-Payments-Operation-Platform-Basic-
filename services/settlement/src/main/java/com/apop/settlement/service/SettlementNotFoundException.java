package com.apop.settlement.service;

import org.springframework.http.HttpStatus;

import java.util.UUID;

public class SettlementNotFoundException extends RuntimeException {

    public SettlementNotFoundException(UUID paymentId) {
        super("Settlement item was not found for payment " + paymentId);
    }

    public HttpStatus status() {
        return HttpStatus.NOT_FOUND;
    }
}
