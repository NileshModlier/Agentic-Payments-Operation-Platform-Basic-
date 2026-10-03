CREATE TABLE fraud_policy (
    policy_version   integer PRIMARY KEY,
    document         jsonb        NOT NULL,
    active           boolean      NOT NULL,
    activated_at     timestamptz  NOT NULL
);

CREATE UNIQUE INDEX fraud_policy_one_active ON fraud_policy (active) WHERE active;

CREATE TABLE fraud_assessment (
    assessment_id      uuid PRIMARY KEY,
    payment_id         uuid         NOT NULL UNIQUE,
    idempotency_key    varchar(128) NOT NULL UNIQUE,
    debtor_account_id  uuid         NOT NULL,
    policy_version     integer      NOT NULL REFERENCES fraud_policy (policy_version),
    decision           varchar(16)  NOT NULL,
    score              integer      NOT NULL,
    feature_snapshot   jsonb        NOT NULL,
    created_at         timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fraud_decision_chk CHECK (decision IN ('APPROVE', 'REVIEW', 'DECLINE')),
    CONSTRAINT fraud_score_chk CHECK (score BETWEEN 0 AND 1000)
);

CREATE INDEX fraud_assessment_debtor_idx ON fraud_assessment (debtor_account_id, created_at);

CREATE TABLE fraud_rule_hit (
    assessment_id  uuid        NOT NULL REFERENCES fraud_assessment (assessment_id),
    rule_id        varchar(64) NOT NULL,
    points         integer     NOT NULL,
    PRIMARY KEY (assessment_id, rule_id),
    CONSTRAINT fraud_rule_points_chk CHECK (points BETWEEN 0 AND 1000)
);

CREATE TABLE beneficiary_list (
    list_entry_id   uuid PRIMARY KEY,
    list_type       varchar(16) NOT NULL,
    creditor_hash   char(64)    NOT NULL,
    effective_from  timestamptz NOT NULL,
    effective_to    timestamptz,
    created_by      varchar(64) NOT NULL,
    approved_by     varchar(64),
    CONSTRAINT beneficiary_list_type_chk CHECK (list_type IN ('ALLOW', 'DENY')),
    CONSTRAINT beneficiary_list_uq UNIQUE (list_type, creditor_hash)
);

CREATE TABLE device_list (
    list_entry_id   uuid PRIMARY KEY,
    list_type       varchar(16) NOT NULL,
    device_hash     char(64)    NOT NULL,
    effective_from  timestamptz NOT NULL,
    effective_to    timestamptz,
    created_by      varchar(64) NOT NULL,
    approved_by     varchar(64),
    CONSTRAINT device_list_type_chk CHECK (list_type IN ('DENY')),
    CONSTRAINT device_list_uq UNIQUE (list_type, device_hash)
);

CREATE TABLE country_list (
    list_entry_id   uuid PRIMARY KEY,
    list_type       varchar(16) NOT NULL,
    country_hash    char(64)    NOT NULL,
    effective_from  timestamptz NOT NULL,
    effective_to    timestamptz,
    created_by      varchar(64) NOT NULL,
    approved_by     varchar(64),
    CONSTRAINT country_list_type_chk CHECK (list_type IN ('DENY')),
    CONSTRAINT country_list_uq UNIQUE (list_type, country_hash)
);

CREATE TABLE fraud_review (
    review_id      uuid PRIMARY KEY,
    assessment_id  uuid         NOT NULL UNIQUE REFERENCES fraud_assessment (assessment_id),
    payment_id     uuid         NOT NULL UNIQUE,
    status         varchar(32)  NOT NULL,
    decision       varchar(32),
    rationale      varchar(500),
    submitted_by   varchar(64)  NOT NULL,
    decided_by     varchar(64),
    reason_code    varchar(64),
    opened_at      timestamptz  NOT NULL,
    decided_at     timestamptz,
    expires_at     timestamptz  NOT NULL,
    CONSTRAINT fraud_review_status_chk CHECK (status IN ('OPEN', 'DECIDED', 'ESCALATED', 'EXPIRED')),
    CONSTRAINT fraud_review_decision_chk CHECK (
        decision IS NULL OR decision IN ('CONFIRM_DECLINE', 'RELEASE_TO_AUTHORIZE', 'ESCALATE')
    )
);

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

INSERT INTO fraud_policy (policy_version, document, active, activated_at)
VALUES (
    1,
    '{
      "reviewThreshold": 200,
      "declineThreshold": 500,
      "newCreditorWeight": 150,
      "reviewTtlSeconds": 86400,
      "windows": [
        {"name":"15m","seconds":900,"debtorCount":100,"debtorAmountMinor":200000,"pairCount":100,"pairAmountMinor":100000000,"deviceCount":100,"deviceAmountMinor":100000000,"debtorWeight":400,"pairWeight":200,"deviceWeight":200},
        {"name":"1h","seconds":3600,"debtorCount":100,"debtorAmountMinor":100000000,"pairCount":100,"pairAmountMinor":100000000,"deviceCount":100,"deviceAmountMinor":100000000,"debtorWeight":200,"pairWeight":150,"deviceWeight":150},
        {"name":"24h","seconds":86400,"debtorCount":100,"debtorAmountMinor":100000000,"pairCount":100,"pairAmountMinor":100000000,"deviceCount":100,"deviceAmountMinor":100000000,"debtorWeight":150,"pairWeight":100,"deviceWeight":100}
      ],
      "geo": {
        "lookbackSeconds": 2592000,
        "sessionChangeSeconds": 3600,
        "newCountryWeight": 120,
        "mismatchWeight": 250,
        "sessionChangeWeight": 220,
        "missingCountryHardReviewChannels": ["MOBILE", "WEB"]
      },
      "device": {
        "lookbackSeconds": 2592000,
        "newDeviceWeight": 100,
        "manyDebtors": 2,
        "manyDebtorsWeight": 220,
        "missingDeviceHardReviewChannels": ["MOBILE", "WEB"]
      },
      "allowedCorridors": [
        {"rail":"BOOK","debtorCountry":"US","creditorCountry":"US"}
      ]
    }'::jsonb,
    true,
    now()
);
