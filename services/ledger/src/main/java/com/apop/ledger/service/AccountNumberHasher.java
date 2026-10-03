package com.apop.ledger.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

@Component
class AccountNumberHasher {

    private final String pepper;

    AccountNumberHasher(@Value("${ledger.account-number-pepper}") String pepper) {
        this.pepper = pepper;
    }

    String hash(String accountNumber) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hashed = digest.digest((pepper + ":" + accountNumber).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hashed);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is not available", exception);
        }
    }

    String last4(String accountNumber) {
        if (accountNumber.length() < 4) {
            return accountNumber;
        }
        return accountNumber.substring(accountNumber.length() - 4);
    }
}
