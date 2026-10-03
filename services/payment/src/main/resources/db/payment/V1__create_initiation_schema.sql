CREATE TABLE payment (
    payment_id              uuid PRIMARY KEY,
    legal_entity_id         varchar(32)  NOT NULL,
    idempotency_key         varchar(128) NOT NULL,
    end_to_end_id           varchar(35)  NOT NULL,
    rail                    varchar(24)  NOT NULL,
    direction               varchar(16)  NOT NULL DEFAULT 'OUTBOUND',
    status                  varchar(32)  NOT NULL,
    amount_minor            bigint       NOT NULL,
    currency                char(3)      NOT NULL,
    debtor_account_id       uuid         NOT NULL,
    creditor_account_id     uuid,
    value_date              date         NOT NULL,
    requested_value_date    date         NOT NULL,
    remittance_ciphertext   bytea,
    remittance_key_version  integer,
    initiated_by            varchar(128) NOT NULL,
    correlation_id          uuid         NOT NULL,
    hold_id                 uuid,
    journal_entry_id        uuid,
    reason_code             varchar(64),
    version                 bigint       NOT NULL DEFAULT 0,
    created_at              timestamptz  NOT NULL DEFAULT now(),
    updated_at              timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT payment_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT payment_direction_chk CHECK (direction = 'OUTBOUND'),
    CONSTRAINT payment_rail_chk CHECK (rail IN ('BOOK', 'SIMULATED_ACH', 'SIMULATED_WIRE')),
    CONSTRAINT payment_status_chk CHECK (status IN (
        'RECEIVED', 'VALIDATED', 'FRAUD_REVIEW', 'AUTHORIZED', 'FAILED', 'SETTLEMENT_PENDING',
        'VALIDATION_FAILED', 'FRAUD_DECLINED', 'AUTHORIZATION_DECLINED'
    )),
    CONSTRAINT payment_book_creditor_chk CHECK (
        rail <> 'BOOK' OR creditor_account_id IS NOT NULL OR status IN ('RECEIVED', 'VALIDATION_FAILED', 'FAILED')
    ),
    CONSTRAINT payment_authorized_hold_chk CHECK (
        status <> 'AUTHORIZED' OR hold_id IS NOT NULL
    ),
    CONSTRAINT payment_idempotency_uq UNIQUE (legal_entity_id, idempotency_key),
    CONSTRAINT payment_e2e_uq UNIQUE (legal_entity_id, debtor_account_id, end_to_end_id)
);

CREATE INDEX payment_debtor_created_idx
    ON payment (debtor_account_id, created_at DESC);

CREATE INDEX payment_status_updated_idx
    ON payment (status, updated_at);

CREATE TABLE payment_party (
    payment_party_id          uuid PRIMARY KEY,
    payment_id                uuid         NOT NULL REFERENCES payment (payment_id),
    role                      varchar(16)  NOT NULL,
    account_id                uuid,
    account_number_ciphertext bytea,
    account_number_last4     varchar(4),
    account_number_hash      varchar(64),
    routing_number_ciphertext bytea,
    party_name_ciphertext    bytea,
    key_version               integer      NOT NULL,
    country                   char(2),
    CONSTRAINT payment_party_role_chk CHECK (role IN ('DEBTOR', 'CREDITOR')),
    CONSTRAINT payment_party_role_uq UNIQUE (payment_id, role)
);

CREATE TABLE idempotency_record (
    legal_entity_id  varchar(32)  NOT NULL,
    idempotency_key  varchar(128) NOT NULL,
    request_hash     varchar(64)  NOT NULL,
    payment_id       uuid         NOT NULL REFERENCES payment (payment_id),
    created_at       timestamptz  NOT NULL DEFAULT now(),
    expires_at       timestamptz  NOT NULL,
    PRIMARY KEY (legal_entity_id, idempotency_key)
);

CREATE TABLE orchestrator_step (
    step_id           uuid PRIMARY KEY,
    payment_id        uuid         NOT NULL REFERENCES payment (payment_id),
    step_name         varchar(32)  NOT NULL,
    step_status       varchar(32)  NOT NULL,
    idempotency_key   varchar(128) NOT NULL,
    downstream_ref    uuid,
    reason_code       varchar(64),
    started_at        timestamptz  NOT NULL DEFAULT now(),
    finished_at       timestamptz,
    CONSTRAINT step_name_chk CHECK (step_name IN (
        'VALIDATE', 'FRAUD', 'AUTHORIZE', 'SETTLE', 'CANCEL', 'REVERSE'
    )),
    CONSTRAINT step_status_chk CHECK (step_status IN (
        'STARTED', 'SUCCEEDED', 'BUSINESS_REJECTED', 'UNCERTAIN', 'FAILED'
    )),
    CONSTRAINT step_once_uq UNIQUE (payment_id, step_name)
);
