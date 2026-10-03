CREATE TABLE account (
    account_id                  uuid PRIMARY KEY,
    legal_entity_id             varchar(32)  NOT NULL,
    account_role                varchar(16)  NOT NULL,
    gl_code                     varchar(32),
    account_number_ciphertext   bytea,
    account_number_last4       varchar(4),
    account_number_hash        varchar(64),
    currency                    char(3)      NOT NULL,
    normal_balance              varchar(6)   NOT NULL,
    status                      varchar(16)  NOT NULL,
    ledger_balance_minor        bigint       NOT NULL DEFAULT 0,
    held_minor                  bigint       NOT NULL DEFAULT 0,
    version                     bigint       NOT NULL DEFAULT 0,
    opened_at                   timestamptz  NOT NULL,
    created_at                  timestamptz  NOT NULL DEFAULT now(),
    updated_at                  timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT account_role_chk CHECK (account_role IN ('CUSTOMER', 'INTERNAL_GL')),
    CONSTRAINT account_normal_chk CHECK (normal_balance IN ('DEBIT', 'CREDIT')),
    CONSTRAINT account_status_chk CHECK (status IN ('OPEN', 'CLOSED')),
    CONSTRAINT account_held_non_negative CHECK (held_minor >= 0),
    CONSTRAINT account_number_hash_uq UNIQUE (account_number_hash)
);

CREATE TABLE balance_hold (
    hold_id           uuid PRIMARY KEY,
    account_id        uuid         NOT NULL REFERENCES account (account_id),
    payment_id        uuid         NOT NULL,
    idempotency_key   varchar(128) NOT NULL,
    amount_minor      bigint       NOT NULL,
    currency          char(3)      NOT NULL,
    status            varchar(16)  NOT NULL,
    expires_at        timestamptz  NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    released_at       timestamptz,
    CONSTRAINT hold_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT hold_status_chk CHECK (status IN ('OPEN', 'RELEASED', 'CONSUMED')),
    CONSTRAINT hold_idempotency_uq UNIQUE (idempotency_key),
    CONSTRAINT hold_payment_uq UNIQUE (payment_id)
);

CREATE INDEX balance_hold_expiry_idx
    ON balance_hold (expires_at)
    WHERE status = 'OPEN';

CREATE TABLE journal_entry (
    journal_entry_id      uuid PRIMARY KEY,
    payment_id            uuid,
    idempotency_key       varchar(128) NOT NULL,
    entry_type            varchar(16)  NOT NULL,
    reversal_of_entry_id  uuid REFERENCES journal_entry (journal_entry_id),
    value_date            date         NOT NULL,
    posted_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT entry_type_chk CHECK (entry_type IN ('POSTING', 'REVERSAL')),
    CONSTRAINT entry_idempotency_uq UNIQUE (idempotency_key),
    CONSTRAINT reversal_link_chk CHECK (
        (entry_type = 'POSTING' AND reversal_of_entry_id IS NULL)
        OR (entry_type = 'REVERSAL' AND reversal_of_entry_id IS NOT NULL)
    )
);

CREATE TABLE journal_line (
    journal_line_id   uuid PRIMARY KEY,
    journal_entry_id  uuid        NOT NULL REFERENCES journal_entry (journal_entry_id),
    account_id        uuid        NOT NULL REFERENCES account (account_id),
    direction         varchar(6)  NOT NULL,
    amount_minor      bigint      NOT NULL,
    currency          char(3)     NOT NULL,
    posted_at         timestamptz NOT NULL,
    CONSTRAINT line_direction_chk CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT line_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX journal_line_account_posted_idx
    ON journal_line (account_id, posted_at DESC);

CREATE OR REPLACE FUNCTION enforce_balanced_journal()
RETURNS trigger
LANGUAGE plpgsql
AS $$
DECLARE
    currency_count integer;
    debit_sum bigint;
    credit_sum bigint;
BEGIN
    SELECT COUNT(DISTINCT currency),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'DEBIT'), 0),
           COALESCE(SUM(amount_minor) FILTER (WHERE direction = 'CREDIT'), 0)
      INTO currency_count, debit_sum, credit_sum
      FROM journal_line
     WHERE journal_entry_id = NEW.journal_entry_id;

    IF currency_count <> 1 OR debit_sum <> credit_sum THEN
        RAISE EXCEPTION 'journal entry % is not balanced', NEW.journal_entry_id
            USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER journal_line_balanced
    AFTER INSERT ON journal_line
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION enforce_balanced_journal();

CREATE TABLE outbox (
    outbox_id       uuid PRIMARY KEY,
    aggregate_type  varchar(64)  NOT NULL,
    aggregate_id    uuid         NOT NULL,
    topic           varchar(160) NOT NULL,
    record_key      varchar(80)  NOT NULL,
    event_type      varchar(160) NOT NULL,
    payload         bytea        NOT NULL,
    headers         jsonb        NOT NULL,
    created_at      timestamptz  NOT NULL DEFAULT now(),
    published_at    timestamptz
);

CREATE INDEX outbox_unpublished_idx
    ON outbox (created_at)
    WHERE published_at IS NULL;

COMMENT ON TABLE outbox IS 'Present for a later relay. Phase 1 does not publish to Kafka.';
