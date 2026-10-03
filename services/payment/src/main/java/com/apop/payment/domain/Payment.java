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
import jakarta.persistence.Version;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;
import org.springframework.data.domain.Persistable;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "payment")
public class Payment implements Persistable<UUID> {

    @Id
    @Column(name = "payment_id", nullable = false)
    private UUID paymentId;

    @Column(name = "legal_entity_id", nullable = false, length = 32)
    private String legalEntityId;

    @Column(name = "idempotency_key", nullable = false, length = 128)
    private String idempotencyKey;

    @Column(name = "end_to_end_id", nullable = false, length = 35)
    private String endToEndId;

    @Enumerated(EnumType.STRING)
    @Column(name = "rail", nullable = false, length = 24)
    private Rail rail;

    @Column(name = "direction", nullable = false, length = 16)
    private String direction;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private PaymentStatus status;

    @Column(name = "amount_minor", nullable = false)
    private long amountMinor;

    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "currency", nullable = false, length = 3)
    private String currency;

    @Column(name = "debtor_account_id", nullable = false)
    private UUID debtorAccountId;

    @Column(name = "creditor_account_id")
    private UUID creditorAccountId;

    @Column(name = "value_date", nullable = false)
    private LocalDate valueDate;

    @Column(name = "requested_value_date", nullable = false)
    private LocalDate requestedValueDate;

    @Column(name = "remittance_ciphertext")
    private byte[] remittanceCiphertext;

    @Column(name = "remittance_key_version")
    private Integer remittanceKeyVersion;

    @Column(name = "initiated_by", nullable = false, length = 128)
    private String initiatedBy;

    @Column(name = "correlation_id", nullable = false)
    private UUID correlationId;

    @Column(name = "hold_id")
    private UUID holdId;

    @Column(name = "journal_entry_id")
    private UUID journalEntryId;

    @Column(name = "reason_code", length = 64)
    private String reasonCode;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Transient
    private boolean newlyReceived = true;

    protected Payment() {
    }

    public static Payment receive(
            UUID paymentId,
            String legalEntityId,
            String idempotencyKey,
            String endToEndId,
            Rail rail,
            long amountMinor,
            String currency,
            UUID debtorAccountId,
            UUID creditorAccountId,
            LocalDate requestedValueDate,
            byte[] remittanceCiphertext,
            Integer remittanceKeyVersion,
            String initiatedBy,
            UUID correlationId,
            Instant receivedAt) {
        Payment payment = new Payment();
        payment.paymentId = paymentId;
        payment.legalEntityId = legalEntityId;
        payment.idempotencyKey = idempotencyKey;
        payment.endToEndId = endToEndId;
        payment.rail = rail;
        payment.direction = "OUTBOUND";
        payment.status = PaymentStatus.RECEIVED;
        payment.amountMinor = amountMinor;
        payment.currency = currency;
        payment.debtorAccountId = debtorAccountId;
        payment.creditorAccountId = creditorAccountId;
        payment.valueDate = requestedValueDate;
        payment.requestedValueDate = requestedValueDate;
        payment.remittanceCiphertext = remittanceCiphertext;
        payment.remittanceKeyVersion = remittanceKeyVersion;
        payment.initiatedBy = initiatedBy;
        payment.correlationId = correlationId;
        payment.createdAt = receivedAt;
        payment.updatedAt = receivedAt;
        return payment;
    }

    public void markValidated(LocalDate valueDate) {
        require(PaymentStatus.RECEIVED);
        this.valueDate = valueDate;
        this.status = PaymentStatus.VALIDATED;
        this.reasonCode = null;
        touch();
    }

    public void markValidationFailed(String reasonCode) {
        require(PaymentStatus.RECEIVED);
        this.status = PaymentStatus.VALIDATION_FAILED;
        this.reasonCode = reasonCode;
        touch();
    }

    public void markFraudReview(String reasonCode) {
        require(PaymentStatus.VALIDATED);
        this.status = PaymentStatus.FRAUD_REVIEW;
        this.reasonCode = reasonCode;
        touch();
    }

    public void markFraudDeclined(String reasonCode) {
        require(PaymentStatus.VALIDATED);
        this.status = PaymentStatus.FRAUD_DECLINED;
        this.reasonCode = reasonCode;
        touch();
    }

    public void markAuthorized(UUID holdId) {
        require(PaymentStatus.VALIDATED);
        if (holdId == null) {
            throw PaymentException.illegal("AUTHORIZED requires a ledger hold");
        }
        this.holdId = holdId;
        this.status = PaymentStatus.AUTHORIZED;
        this.reasonCode = null;
        touch();
    }

    public void markAuthorizationDeclined(String reasonCode) {
        require(PaymentStatus.VALIDATED);
        this.status = PaymentStatus.AUTHORIZATION_DECLINED;
        this.reasonCode = reasonCode;
        touch();
    }

    public void markSettlementPending() {
        require(PaymentStatus.AUTHORIZED);
        this.status = PaymentStatus.SETTLEMENT_PENDING;
        this.reasonCode = null;
        touch();
    }

    public void markSettled(UUID journalEntryId) {
        require(PaymentStatus.SETTLEMENT_PENDING);
        if (journalEntryId == null) {
            throw PaymentException.illegal("SETTLED requires a journal entry");
        }
        this.journalEntryId = journalEntryId;
        this.status = PaymentStatus.SETTLED;
        this.reasonCode = null;
        touch();
    }

    public void markSettlementFailed(String reasonCode) {
        require(PaymentStatus.SETTLEMENT_PENDING);
        this.status = PaymentStatus.SETTLEMENT_FAILED;
        this.reasonCode = reasonCode;
        touch();
    }

    public void markFailed(String reasonCode) {
        if (status.isDecided()) {
            throw PaymentException.illegal("Payment is already decided");
        }
        this.status = PaymentStatus.FAILED;
        this.reasonCode = reasonCode;
        touch();
    }

    private void require(PaymentStatus expected) {
        if (status != expected) {
            throw PaymentException.illegal("Payment is " + status + " and cannot move from " + expected);
        }
    }

    private void touch() {
        updatedAt = Instant.now();
    }

    @Override
    public UUID getId() {
        return paymentId;
    }

    @Override
    public boolean isNew() {
        return newlyReceived;
    }

    @PostLoad
    @PostPersist
    void markPersisted() {
        newlyReceived = false;
    }

    public UUID getPaymentId() {
        return paymentId;
    }

    public String getLegalEntityId() {
        return legalEntityId;
    }

    public String getIdempotencyKey() {
        return idempotencyKey;
    }

    public String getEndToEndId() {
        return endToEndId;
    }

    public Rail getRail() {
        return rail;
    }

    public PaymentStatus getStatus() {
        return status;
    }

    public long getAmountMinor() {
        return amountMinor;
    }

    public String getCurrency() {
        return currency == null ? null : currency.trim();
    }

    public UUID getDebtorAccountId() {
        return debtorAccountId;
    }

    public UUID getCreditorAccountId() {
        return creditorAccountId;
    }

    public LocalDate getValueDate() {
        return valueDate;
    }

    public LocalDate getRequestedValueDate() {
        return requestedValueDate;
    }

    public String getInitiatedBy() {
        return initiatedBy;
    }

    public UUID getCorrelationId() {
        return correlationId;
    }

    public UUID getHoldId() {
        return holdId;
    }

    public UUID getJournalEntryId() {
        return journalEntryId;
    }

    public String getReasonCode() {
        return reasonCode;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
