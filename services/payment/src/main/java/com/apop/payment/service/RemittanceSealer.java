package com.apop.payment.service;

import com.apop.payment.config.PaymentProperties;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

@Component
public class RemittanceSealer {

    private static final int KEY_VERSION = 1;
    private static final int IV_LENGTH = 12;
    private static final int TAG_BITS = 128;

    private final byte[] key;
    private final SecureRandom random = new SecureRandom();

    public RemittanceSealer(PaymentProperties properties) {
        byte[] decoded = Base64.getDecoder().decode(properties.remittanceKey());
        if (decoded.length != 32) {
            throw new IllegalStateException("Remittance key must be 32 bytes");
        }
        this.key = decoded;
    }

    public Sealed seal(String plaintext) {
        if (plaintext == null || plaintext.isBlank()) {
            return new Sealed(null, null);
        }
        try {
            byte[] iv = new byte[IV_LENGTH];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] payload = Arrays.copyOf(iv, iv.length + encrypted.length);
            System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
            return new Sealed(payload, KEY_VERSION);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Remittance could not be sealed", exception);
        }
    }

    public String open(byte[] payload) {
        if (payload == null || payload.length <= IV_LENGTH) {
            return null;
        }
        try {
            byte[] iv = Arrays.copyOf(payload, IV_LENGTH);
            byte[] encrypted = Arrays.copyOfRange(payload, IV_LENGTH, payload.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Remittance could not be opened", exception);
        }
    }

    public String last4(String accountNumber) {
        if (accountNumber == null || accountNumber.length() < 4) {
            return null;
        }
        return accountNumber.substring(accountNumber.length() - 4);
    }

    public record Sealed(byte[] ciphertext, Integer keyVersion) {
    }
}
