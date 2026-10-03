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

CREATE TABLE lifecycle_projection (
    event_id         uuid PRIMARY KEY,
    payment_id       uuid         NOT NULL,
    event_name       varchar(64)  NOT NULL,
    status           varchar(32)  NOT NULL,
    previous_status  varchar(32),
    occurred_at      timestamptz  NOT NULL
);

CREATE INDEX lifecycle_projection_payment_idx
    ON lifecycle_projection (payment_id, occurred_at);
