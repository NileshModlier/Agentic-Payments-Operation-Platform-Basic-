CREATE TABLE settlement_batch (
    batch_id      uuid PRIMARY KEY,
    rail          varchar(24) NOT NULL,
    window_date   date        NOT NULL,
    status        varchar(16) NOT NULL,
    item_count    integer     NOT NULL,
    total_minor   bigint      NOT NULL,
    currency      char(3)     NOT NULL,
    locked_at     timestamptz,
    created_at    timestamptz NOT NULL,
    CONSTRAINT batch_status_chk CHECK (status IN ('OPEN', 'LOCKED', 'POSTED', 'FAILED'))
);

CREATE TABLE settlement_item (
    settlement_item_id     uuid PRIMARY KEY,
    batch_id               uuid         NOT NULL REFERENCES settlement_batch (batch_id),
    payment_id             uuid         NOT NULL UNIQUE,
    command_id             uuid         NOT NULL UNIQUE,
    hold_id                uuid         NOT NULL,
    ledger_idempotency_key varchar(128) NOT NULL UNIQUE,
    item_status            varchar(24)  NOT NULL,
    journal_entry_id       uuid,
    amount_minor           bigint       NOT NULL,
    currency               char(3)      NOT NULL,
    value_date             date         NOT NULL,
    rail                   varchar(24)  NOT NULL,
    debtor_account_id      uuid         NOT NULL,
    creditor_account_id    uuid,
    reason_code            varchar(64),
    created_at             timestamptz  NOT NULL,
    updated_at             timestamptz  NOT NULL,
    CONSTRAINT item_status_chk CHECK (item_status IN (
        'INCLUDED', 'POSTED', 'POST_UNCERTAIN', 'POST_REFUSED'
    ))
);

CREATE INDEX settlement_item_status_idx
    ON settlement_item (item_status, updated_at);

CREATE TABLE settlement_timeline (
    timeline_id        uuid PRIMARY KEY,
    settlement_item_id uuid         NOT NULL REFERENCES settlement_item (settlement_item_id),
    payment_id         uuid         NOT NULL,
    event_type         varchar(64)  NOT NULL,
    item_status        varchar(24),
    summary_code       varchar(64)  NOT NULL,
    occurred_at        timestamptz  NOT NULL
);

CREATE INDEX settlement_timeline_payment_idx
    ON settlement_timeline (payment_id, occurred_at);

CREATE TABLE outbox (
    outbox_id       uuid PRIMARY KEY,
    aggregate_type  varchar(64)  NOT NULL,
    aggregate_id    uuid         NOT NULL,
    topic           varchar(160) NOT NULL,
    record_key      varchar(80)  NOT NULL,
    event_type      varchar(160) NOT NULL,
    payload         bytea        NOT NULL,
    headers         jsonb        NOT NULL,
    created_at      timestamptz  NOT NULL,
    published_at    timestamptz
);

CREATE INDEX outbox_unpublished_idx
    ON outbox (created_at, outbox_id)
    WHERE published_at IS NULL;

CREATE TABLE processed_event (
    event_id      uuid PRIMARY KEY,
    event_type    varchar(160) NOT NULL,
    outcome       varchar(32)  NOT NULL,
    processed_at  timestamptz  NOT NULL
);
