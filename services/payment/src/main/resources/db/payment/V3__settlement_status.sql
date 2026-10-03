ALTER TABLE payment DROP CONSTRAINT payment_status_chk;
ALTER TABLE payment ADD CONSTRAINT payment_status_chk CHECK (status IN (
    'RECEIVED', 'VALIDATED', 'FRAUD_REVIEW', 'AUTHORIZED', 'FAILED', 'SETTLEMENT_PENDING',
    'SETTLED', 'SETTLEMENT_FAILED',
    'VALIDATION_FAILED', 'FRAUD_DECLINED', 'AUTHORIZATION_DECLINED'
));

ALTER TABLE payment DROP CONSTRAINT payment_authorized_hold_chk;
ALTER TABLE payment ADD CONSTRAINT payment_authorized_hold_chk CHECK (
    status NOT IN ('AUTHORIZED', 'SETTLEMENT_PENDING', 'SETTLED') OR hold_id IS NOT NULL
);

ALTER TABLE payment ADD CONSTRAINT payment_settled_journal_chk CHECK (
    status <> 'SETTLED' OR journal_entry_id IS NOT NULL
);
