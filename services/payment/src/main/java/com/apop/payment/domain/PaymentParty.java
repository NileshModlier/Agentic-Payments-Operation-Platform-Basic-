package com.apop.payment.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PostLoad;
import jakarta.persistence.PostPersist;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.util.UUID;

@Entity
@Table(name = "payment_party")
public class PaymentParty implements Persistable<UUID> {

    @Id
    @Column(name = "payment_party_id", nullable = false)
    private UUID paymentPartyId;

    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Enumerated(EnumType.STRING)
    @Column(name = "role", nullable = false, length = 16)
    private PartyRole role;

    @Column(name = "account_id")
    private UUID accountId;

    @Column(name = "account_number_ciphertext")
    private byte[] accountNumberCiphertext;

    @Column(name = "account_number_last4", length = 4)
    private String accountNumberLast4;

    @Column(name = "account_number_hash", length = 64)
    private String accountNumberHash;

    @Column(name = "routing_number_ciphertext")
    private byte[] routingNumberCiphertext;

    @Column(name = "party_name_ciphertext")
    private byte[] partyNameCiphertext;

    @Column(name = "key_version", nullable = false)
    private int keyVersion;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "country", length = 2)
    private String country;

    @Transient
    private boolean newlyCreated = true;

    protected PaymentParty() {
    }

    public static PaymentParty create(
            UUID paymentId,
            PartyRole role,
            UUID accountId,
            byte[] accountNumberCiphertext,
            String accountNumberLast4,
            String accountNumberHash,
            byte[] routingNumberCiphertext,
            byte[] partyNameCiphertext,
            int keyVersion,
            String country) {
        PaymentParty party = new PaymentParty();
        party.paymentPartyId = UuidV7.generate();
        party.paymentId = paymentId;
        party.role = role;
        party.accountId = accountId;
        party.accountNumberCiphertext = accountNumberCiphertext;
        party.accountNumberLast4 = accountNumberLast4;
        party.accountNumberHash = accountNumberHash;
        party.routingNumberCiphertext = routingNumberCiphertext;
        party.partyNameCiphertext = partyNameCiphertext;
        party.keyVersion = keyVersion;
        party.country = country;
        return party;
    }

    @Override
    public UUID getId() {
        return paymentPartyId;
    }

    @Override
    public boolean isNew() {
        return newlyCreated;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newlyCreated = false;
    }

    public byte[] getPartyNameCiphertext() {
        return partyNameCiphertext;
    }
}
