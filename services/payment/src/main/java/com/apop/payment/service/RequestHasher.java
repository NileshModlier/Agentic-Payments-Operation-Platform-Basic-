package com.apop.payment.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class RequestHasher {

    private RequestHasher() {
    }

    public static String hash(CreatePaymentCommand command) {
        String canonical = String.join("|",
                command.legalEntityId(),
                command.endToEndId(),
                command.rail().name(),
                Long.toString(command.amountMinor()),
                command.currency(),
                command.debtorAccountId().toString(),
                text(command.creditorAccountId()),
                text(command.creditorName()),
                text(command.creditorAccountNumber()),
                text(command.creditorRoutingNumber()),
                text(command.creditorCountry()),
                command.requestedValueDate().toString(),
                text(command.remittance()));
        return sha256(canonical);
    }

    public static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    private static String text(Object value) {
        return value == null ? "" : value.toString();
    }
}
