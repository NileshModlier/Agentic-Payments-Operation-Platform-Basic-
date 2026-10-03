# 4. PostgreSQL schema design

| | |
| --- | --- |
| Status | Proposed |
| Audience | Service owners and data reviewers |
| Related | [Service boundaries](02-service-boundaries.md), [Kafka](03-kafka-event-design.md), [Security](07-security-architecture.md) |

This is the logical physical schema. It is the contract implementation will migrate from. It is not a migration script to run yet.

## Conventions

| Convention | Rule |
| --- | --- |
| Database | One database per service on the platform PostgreSQL topology. Ledger uses a dedicated primary. |
| Names | `snake_case`. Primary keys are `<entity>_id` UUID. Application generates UUIDv7. |
| Money | `bigint` minor units and `char(3)` currency. No `numeric` floats and no floating types. |
| Time | `timestamptz` stored in UTC. Business dates are `date` in the bank calendar. |
| Mutability | Journal lines, outbox payloads, audit rows, and processed-event rows are insert-only. |
| Optimistic lock | Mutable aggregates carry `version bigint`. |
| Tenancy | `legal_entity_id varchar(32)` on business roots. |
| Encryption | Disk encryption for every volume. External account numbers and party names use application envelope encryption. The key lives in Vault. The row stores ciphertext, key version, last four, and a SHA-256 fingerprint. |

Every database that publishes includes `outbox`. Every database that consumes includes `processed_event`.

```sql
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

CREATE TABLE processed_event (
    event_id      uuid PRIMARY KEY,
    event_type    varchar(160) NOT NULL,
    outcome       varchar(32)  NOT NULL,
    processed_at  timestamptz  NOT NULL DEFAULT now()
);
```

`payload` is the Avro-encoded CloudEvents body. It is bytes so the database does not become a second schema registry.

Grants are per service role. The Ledger role is the only role with `INSERT` on journal tables. No application role has `DELETE` on journal, audit, or outbox tables. History and case databases are not granted to the Ledger role.

## Payment Initiation database `initiation`

```sql
CREATE TABLE payment (
    payment_id            uuid PRIMARY KEY,
    legal_entity_id       varchar(32)  NOT NULL,
    idempotency_key       varchar(128) NOT NULL,
    end_to_end_id         varchar(35)  NOT NULL,
    rail                  varchar(24)  NOT NULL,
    direction             varchar(16)  NOT NULL DEFAULT 'OUTBOUND',
    status                varchar(32)  NOT NULL,
    amount_minor          bigint       NOT NULL,
    currency              char(3)      NOT NULL,
    debtor_account_id     uuid         NOT NULL,
    creditor_account_id   uuid,
    value_date            date         NOT NULL,
    requested_value_date  date         NOT NULL,
    remittance_ciphertext bytea,
    remittance_key_version integer,
    initiated_by          varchar(128) NOT NULL,
    correlation_id        uuid         NOT NULL,
    hold_id               uuid,
    journal_entry_id      uuid,
    reason_code           varchar(64),
    version               bigint       NOT NULL DEFAULT 0,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    updated_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT payment_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT payment_direction_chk CHECK (direction IN ('OUTBOUND')),
    CONSTRAINT payment_rail_chk CHECK (rail IN ('BOOK', 'SIMULATED_ACH', 'SIMULATED_WIRE')),
    CONSTRAINT payment_status_chk CHECK (status IN (
        'RECEIVED', 'VALIDATING', 'VALIDATION_FAILED', 'FRAUD_SCREENING',
        'FRAUD_REVIEW', 'FRAUD_DECLINED', 'AUTHORIZING', 'AUTHORIZED',
        'AUTHORIZATION_DECLINED', 'SETTLEMENT_PENDING', 'SETTLED',
        'SETTLEMENT_FAILED', 'CANCELLED', 'FAILED'
    )),
    CONSTRAINT payment_book_creditor_chk CHECK (
        rail <> 'BOOK' OR creditor_account_id IS NOT NULL
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
    account_number_last4     char(4),
    account_number_hash      char(64),
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
    request_hash     char(64)     NOT NULL,
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

CREATE TABLE payment_link (
    payment_link_id   uuid PRIMARY KEY,
    payment_id        uuid        NOT NULL REFERENCES payment (payment_id),
    related_payment_id uuid       NOT NULL REFERENCES payment (payment_id),
    link_role         varchar(24) NOT NULL,
    created_at        timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT payment_link_role_chk CHECK (link_role IN ('REVERSAL_OF')),
    CONSTRAINT payment_link_uq UNIQUE (payment_id, related_payment_id, link_role)
);
```

`idempotency_record.request_hash` is the hash of the canonical request. A repeated key with a different hash is a conflict and creates no second payment. Remittance ciphertext is absent from events. `step_once_uq` makes the synchronous saga restartable.

## Payment Validation database `validation`

```sql
CREATE TABLE business_calendar (
    calendar_date   date PRIMARY KEY,
    is_business_day boolean NOT NULL,
    holiday_name    varchar(80)
);

CREATE TABLE rail_cutoff (
    rail            varchar(24) PRIMARY KEY,
    cutoff_local    time        NOT NULL,
    timezone        varchar(64) NOT NULL,
    roll_forward    boolean     NOT NULL
);

CREATE TABLE limit_policy (
    limit_policy_id  uuid PRIMARY KEY,
    rail             varchar(24) NOT NULL,
    currency         char(3)     NOT NULL,
    max_amount_minor bigint      NOT NULL,
    effective_from   timestamptz NOT NULL,
    effective_to     timestamptz,
    CONSTRAINT limit_positive CHECK (max_amount_minor > 0)
);

CREATE TABLE validation_result (
    validation_id    uuid PRIMARY KEY,
    payment_id       uuid         NOT NULL UNIQUE,
    idempotency_key  varchar(128) NOT NULL UNIQUE,
    outcome          varchar(16)  NOT NULL,
    reason_code      varchar(64),
    rolled_value_date date,
    policy_id        uuid,
    created_at       timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT validation_outcome_chk CHECK (outcome IN ('PASS', 'REJECT'))
);
```

Screening hits record `reason_code = VALIDATION_SANCTIONS_REJECT` and do not copy list content into this table. List data stays with the screening adapter's restricted store.

## Fraud Detection database `fraud`

```sql
CREATE TABLE fraud_policy (
    policy_version   integer PRIMARY KEY,
    document         jsonb       NOT NULL,
    active           boolean     NOT NULL,
    activated_at     timestamptz NOT NULL
);

CREATE TABLE fraud_assessment (
    assessment_id     uuid PRIMARY KEY,
    payment_id        uuid         NOT NULL UNIQUE,
    idempotency_key   varchar(128) NOT NULL UNIQUE,
    policy_version    integer      NOT NULL REFERENCES fraud_policy (policy_version),
    decision          varchar(16)  NOT NULL,
    score             integer      NOT NULL,
    feature_snapshot  jsonb        NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT fraud_decision_chk CHECK (decision IN ('APPROVE', 'REVIEW', 'DECLINE')),
    CONSTRAINT fraud_score_chk CHECK (score BETWEEN 0 AND 1000)
);

CREATE TABLE fraud_rule_hit (
    assessment_id  uuid        NOT NULL REFERENCES fraud_assessment (assessment_id),
    rule_id        varchar(64) NOT NULL,
    PRIMARY KEY (assessment_id, rule_id)
);

CREATE TABLE beneficiary_list (
    list_entry_id     uuid PRIMARY KEY,
    list_type         varchar(16) NOT NULL,
    creditor_hash     char(64)    NOT NULL,
    effective_from    timestamptz NOT NULL,
    effective_to      timestamptz,
    CONSTRAINT list_type_chk CHECK (list_type IN ('ALLOW', 'DENY')),
    CONSTRAINT beneficiary_list_uq UNIQUE (list_type, creditor_hash)
);
```

`feature_snapshot` is restricted to the fraud role and the audit extract role. It is how an investigator explains a decision. It is not published on the assessment topic. A partial unique index on `fraud_policy (active) WHERE active` keeps a single active policy version.

## Payment Authorization database `authorization`

```sql
CREATE TABLE authorization_decision (
    authorization_id  uuid PRIMARY KEY,
    payment_id        uuid         NOT NULL UNIQUE,
    idempotency_key   varchar(128) NOT NULL UNIQUE,
    outcome           varchar(16)  NOT NULL,
    reason_code       varchar(64),
    hold_id           uuid,
    amount_minor      bigint       NOT NULL,
    currency          char(3)      NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT authorization_outcome_chk CHECK (outcome IN ('AUTHORIZED', 'DECLINED')),
    CONSTRAINT authorization_hold_chk CHECK (
        (outcome = 'AUTHORIZED' AND hold_id IS NOT NULL)
        OR (outcome = 'DECLINED' AND hold_id IS NULL)
    )
);
```

An authorized row without a hold cannot be represented. That is the structural form of "no approval without a reservation."

## Ledger database `ledger`

This database is the book of record. Backups, point-in-time recovery, and access reviews treat it as financial data. Retention of journal rows is 7 years.

```sql
CREATE TABLE account (
    account_id             uuid PRIMARY KEY,
    legal_entity_id        varchar(32) NOT NULL,
    account_role           varchar(16) NOT NULL,
    gl_code                varchar(32),
    account_number_ciphertext bytea,
    account_number_last4  char(4),
    account_number_hash   char(64),
    currency               char(3)     NOT NULL,
    normal_balance         varchar(6)  NOT NULL,
    status                 varchar(16) NOT NULL,
    ledger_balance_minor   bigint      NOT NULL DEFAULT 0,
    held_minor             bigint      NOT NULL DEFAULT 0,
    version                bigint      NOT NULL DEFAULT 0,
    opened_at              timestamptz NOT NULL,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
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
    idempotency_key   varchar(128) NOT NULL UNIQUE,
    amount_minor      bigint       NOT NULL,
    currency          char(3)      NOT NULL,
    status            varchar(16)  NOT NULL,
    expires_at        timestamptz  NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    released_at       timestamptz,
    CONSTRAINT hold_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT hold_status_chk CHECK (status IN ('OPEN', 'RELEASED', 'CONSUMED')),
    CONSTRAINT hold_payment_uq UNIQUE (payment_id)
);

CREATE INDEX balance_hold_expiry_idx
    ON balance_hold (expires_at)
    WHERE status = 'OPEN';

CREATE TABLE journal_entry (
    journal_entry_id          uuid PRIMARY KEY,
    payment_id                uuid,
    idempotency_key           varchar(128) NOT NULL UNIQUE,
    entry_type                varchar(16)  NOT NULL,
    reversal_of_entry_id      uuid REFERENCES journal_entry (journal_entry_id),
    value_date                date         NOT NULL,
    posted_at                 timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT entry_type_chk CHECK (entry_type IN ('POSTING', 'REVERSAL'))
);

CREATE TABLE journal_line (
    journal_line_id    uuid PRIMARY KEY,
    journal_entry_id   uuid        NOT NULL REFERENCES journal_entry (journal_entry_id),
    account_id         uuid        NOT NULL REFERENCES account (account_id),
    direction          varchar(6)  NOT NULL,
    amount_minor       bigint      NOT NULL,
    currency           char(3)     NOT NULL,
    posted_at          timestamptz NOT NULL,
    CONSTRAINT line_direction_chk CHECK (direction IN ('DEBIT', 'CREDIT')),
    CONSTRAINT line_amount_positive CHECK (amount_minor > 0)
);

CREATE INDEX journal_line_account_posted_idx
    ON journal_line (account_id, posted_at DESC);
```

`journal_line` is partitioned by month on `posted_at` when volume requires it. The parent key design stays the same. The service transaction rejects a posting unless, per currency, the sum of debit minor units equals the sum of credit minor units. A database constraint trigger is part of implementation; the invariant is already a ledger rule.

Chart of accounts seeded for the simulation:

| `gl_code` | Role | Normal balance | Use |
| --- | --- | --- | --- |
| `GL-CUST-LIAB` | Template for customer liabilities | `CREDIT` | Customer deposit accounts are their own rows, credit-normal |
| `GL-CLEARING` | `INTERNAL_GL` | `CREDIT` | External-rail settlement clearing |
| `GL-NOSTRO-SIM` | `INTERNAL_GL` | `DEBIT` | Simulated nostro |
| `GL-SUSPENSE` | `INTERNAL_GL` | `CREDIT` | Unmatched items awaiting a human disposition |
| `GL-FEE-INCOME` | `INTERNAL_GL` | `CREDIT` | Reserved for a later fee leg |

Available balance for a credit-normal customer account is `ledger_balance_minor - held_minor`. The service enforces that this value stays greater than or equal to zero. Other services receive it as a response field. They do not store a second balance.

## Payment Settlement database `settlement`

```sql
CREATE TABLE settlement_window (
    rail            varchar(24) NOT NULL,
    window_date     date        NOT NULL,
    opens_at        timestamptz NOT NULL,
    locks_at        timestamptz NOT NULL,
    PRIMARY KEY (rail, window_date)
);

CREATE TABLE settlement_batch (
    batch_id      uuid PRIMARY KEY,
    rail          varchar(24) NOT NULL,
    window_date   date        NOT NULL,
    status        varchar(16) NOT NULL,
    item_count    integer     NOT NULL DEFAULT 0,
    total_minor   bigint      NOT NULL DEFAULT 0,
    currency      char(3)     NOT NULL,
    locked_at     timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
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
    updated_at             timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT item_status_chk CHECK (item_status IN (
        'INCLUDED', 'POSTED', 'POST_UNCERTAIN', 'POST_REFUSED'
    ))
);
```

## Transaction History database `history`

Rebuildable. Not a financial record. Monthly partitions on `occurred_at` are the expected physical layout.

```sql
CREATE TABLE payment_history (
    payment_id           uuid PRIMARY KEY,
    legal_entity_id      varchar(32) NOT NULL,
    status               varchar(32) NOT NULL,
    rail                 varchar(24) NOT NULL,
    amount_minor         bigint      NOT NULL,
    currency             char(3)     NOT NULL,
    debtor_account_id    uuid        NOT NULL,
    creditor_account_id  uuid,
    creditor_fingerprint char(64),
    value_date           date        NOT NULL,
    reason_code          varchar(64),
    fraud_decision       varchar(16),
    updated_at           timestamptz NOT NULL
);

CREATE INDEX payment_history_debtor_idx
    ON payment_history (debtor_account_id, value_date DESC);

CREATE TABLE payment_timeline (
    event_id      uuid PRIMARY KEY,
    payment_id    uuid         NOT NULL,
    event_type    varchar(160) NOT NULL,
    status        varchar(32),
    summary_code  varchar(64)  NOT NULL,
    occurred_at   timestamptz  NOT NULL
);

CREATE INDEX payment_timeline_payment_idx
    ON payment_timeline (payment_id, occurred_at);

CREATE TABLE account_activity (
    activity_id    uuid PRIMARY KEY,
    account_id     uuid        NOT NULL,
    payment_id     uuid,
    movement_type  varchar(24) NOT NULL,
    direction      varchar(6),
    amount_minor   bigint      NOT NULL,
    currency       char(3)     NOT NULL,
    occurred_at    timestamptz NOT NULL
);

CREATE INDEX account_activity_account_idx
    ON account_activity (account_id, occurred_at DESC);
```

The projector writes these rows in the same transaction as `processed_event`.

## Reconciliation database `reconciliation`

```sql
CREATE TABLE statement_file (
    statement_file_id uuid PRIMARY KEY,
    rail              varchar(24)  NOT NULL,
    file_hash         char(64)     NOT NULL UNIQUE,
    value_date        date         NOT NULL,
    received_at       timestamptz  NOT NULL DEFAULT now(),
    status            varchar(16)  NOT NULL,
    CONSTRAINT statement_file_status_chk CHECK (status IN ('RECEIVED', 'LOADED', 'FAILED'))
);

CREATE TABLE statement_line (
    statement_line_id uuid PRIMARY KEY,
    statement_file_id uuid        NOT NULL REFERENCES statement_file (statement_file_id),
    end_to_end_id     varchar(35) NOT NULL,
    amount_minor      bigint      NOT NULL,
    currency          char(3)     NOT NULL,
    value_date        date        NOT NULL,
    debtor_fingerprint  char(64)  NOT NULL,
    creditor_fingerprint char(64) NOT NULL,
    CONSTRAINT statement_line_natural_uq UNIQUE (
        statement_file_id, end_to_end_id, amount_minor, currency
    )
);

CREATE TABLE recon_run (
    recon_run_id  uuid PRIMARY KEY,
    rail          varchar(24) NOT NULL,
    value_date    date        NOT NULL,
    started_at    timestamptz NOT NULL,
    finished_at   timestamptz,
    status        varchar(16) NOT NULL,
    CONSTRAINT recon_run_status_chk CHECK (status IN ('RUNNING', 'COMPLETED', 'FAILED'))
);

CREATE TABLE recon_match (
    recon_match_id     uuid PRIMARY KEY,
    recon_run_id       uuid NOT NULL REFERENCES recon_run (recon_run_id),
    settlement_item_id uuid NOT NULL UNIQUE,
    statement_line_id  uuid NOT NULL UNIQUE,
    matched_at         timestamptz NOT NULL
);

CREATE TABLE recon_break (
    break_id           uuid PRIMARY KEY,
    recon_run_id       uuid        NOT NULL REFERENCES recon_run (recon_run_id),
    break_class        varchar(32) NOT NULL,
    severity           varchar(16) NOT NULL,
    settlement_item_id uuid,
    statement_line_id  uuid,
    payment_id         uuid,
    amount_minor       bigint,
    currency           char(3),
    value_date         date,
    status             varchar(16) NOT NULL,
    opened_at          timestamptz NOT NULL DEFAULT now(),
    CONSTRAINT break_class_chk CHECK (break_class IN (
        'MISSING_INTERNAL', 'MISSING_STATEMENT', 'AMOUNT_MISMATCH',
        'VALUE_DATE_MISMATCH', 'DUPLICATE_STATEMENT'
    )),
    CONSTRAINT break_severity_chk CHECK (severity IN ('LOW', 'MEDIUM', 'HIGH')),
    CONSTRAINT break_status_chk CHECK (status IN ('OPEN', 'RESOLVED'))
);
```

## Failure Management database `failure`

```sql
CREATE TABLE failure_case (
    case_id        uuid PRIMARY KEY,
    payment_id     uuid,
    case_type      varchar(40)  NOT NULL,
    case_status    varchar(16)  NOT NULL,
    reason_code    varchar(64)  NOT NULL,
    source_event_id uuid,
    attempt_count  integer      NOT NULL DEFAULT 0,
    next_attempt_at timestamptz,
    opened_at      timestamptz  NOT NULL DEFAULT now(),
    closed_at      timestamptz,
    CONSTRAINT failure_type_chk CHECK (case_type IN (
        'UNCERTAIN_STEP', 'DEAD_LETTER', 'POST_UNCERTAIN', 'HOLD_EXPIRY'
    )),
    CONSTRAINT failure_status_chk CHECK (case_status IN (
        'OPEN', 'RETRYING', 'COMPENSATING', 'ESCALATED', 'CLOSED'
    ))
);

CREATE TABLE failure_attempt (
    attempt_id     uuid PRIMARY KEY,
    case_id        uuid        NOT NULL REFERENCES failure_case (case_id),
    attempted_at   timestamptz NOT NULL,
    outcome        varchar(16) NOT NULL,
    detail_code    varchar(64),
    CONSTRAINT attempt_outcome_chk CHECK (outcome IN ('SUCCEEDED', 'FAILED', 'UNCERTAIN'))
);

CREATE TABLE compensation_action (
    compensation_id       uuid PRIMARY KEY,
    case_id               uuid         NOT NULL REFERENCES failure_case (case_id),
    payment_id            uuid         NOT NULL,
    action                varchar(24)  NOT NULL,
    ledger_idempotency_key varchar(128) NOT NULL UNIQUE,
    approved_by           varchar(128) NOT NULL,
    action_status         varchar(16)  NOT NULL,
    created_at            timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT compensation_action_chk CHECK (action IN ('RELEASE_HOLD', 'POST_REVERSAL')),
    CONSTRAINT compensation_status_chk CHECK (action_status IN (
        'REQUESTED', 'APPLIED', 'REJECTED'
    ))
);
```

A check that implementation will add in the service, and that a constraint can mirror: `POST_REVERSAL` rows require `approved_by` to be a user subject, not `POLICY`.

## Investigation database `investigation`

```sql
CREATE TABLE investigation_case (
    case_id         uuid PRIMARY KEY,
    payment_id      uuid         NOT NULL,
    source          varchar(32)  NOT NULL,
    source_ref      uuid         NOT NULL,
    case_status     varchar(24)  NOT NULL,
    opened_at       timestamptz  NOT NULL DEFAULT now(),
    closed_at       timestamptz,
    CONSTRAINT investigation_source_chk CHECK (source IN (
        'FRAUD_REVIEW', 'RECON_BREAK', 'ESCALATED_FAILURE'
    )),
    CONSTRAINT investigation_status_chk CHECK (case_status IN (
        'OPEN', 'AGENT_RUNNING', 'AWAITING_HUMAN', 'DECIDED', 'CLOSED'
    )),
    CONSTRAINT investigation_source_uq UNIQUE (source, source_ref)
);

CREATE TABLE agent_session (
    session_id     uuid PRIMARY KEY,
    case_id        uuid        NOT NULL REFERENCES investigation_case (case_id),
    model_route    varchar(64) NOT NULL,
    status         varchar(16) NOT NULL,
    started_at     timestamptz NOT NULL DEFAULT now(),
    finished_at    timestamptz,
    token_input    integer,
    token_output   integer,
    CONSTRAINT agent_session_status_chk CHECK (status IN (
        'RUNNING', 'COMPLETED', 'STOPPED', 'FAILED'
    ))
);

CREATE TABLE agent_tool_call (
    tool_call_id   uuid PRIMARY KEY,
    session_id     uuid         NOT NULL REFERENCES agent_session (session_id),
    tool_name      varchar(64)  NOT NULL,
    request_hash   char(64)     NOT NULL,
    response_code  varchar(32)  NOT NULL,
    called_at      timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE agent_recommendation (
    recommendation_id uuid PRIMARY KEY,
    case_id           uuid         NOT NULL REFERENCES investigation_case (case_id),
    session_id        uuid         NOT NULL REFERENCES agent_session (session_id),
    disposition       varchar(40)  NOT NULL,
    confidence        numeric(4,3) NOT NULL,
    evidence_ids      jsonb        NOT NULL,
    narrative         text         NOT NULL,
    policy_version    varchar(32)  NOT NULL,
    created_at        timestamptz  NOT NULL DEFAULT now(),
    CONSTRAINT disposition_chk CHECK (disposition IN (
        'CONFIRM_DECLINE', 'RELEASE_TO_AUTHORIZE', 'RETRY_TECHNICAL',
        'PROPOSE_REVERSAL', 'ESCALATE', 'NO_ACTION'
    )),
    CONSTRAINT confidence_chk CHECK (confidence >= 0 AND confidence <= 1)
);

CREATE TABLE human_decision (
    decision_id        uuid PRIMARY KEY,
    case_id            uuid         NOT NULL REFERENCES investigation_case (case_id),
    recommendation_id  uuid REFERENCES agent_recommendation (recommendation_id),
    decision           varchar(40)  NOT NULL,
    decision_role      varchar(24)  NOT NULL,
    decided_by         varchar(128) NOT NULL,
    decided_at         timestamptz  NOT NULL DEFAULT now(),
    rationale          varchar(512) NOT NULL,
    CONSTRAINT human_decision_role_chk CHECK (decision_role IN ('PRIMARY', 'COUNTER_APPROVAL')),
    CONSTRAINT human_decision_actor_uq UNIQUE (case_id, decided_by),
    CONSTRAINT human_decision_role_uq UNIQUE (case_id, decision_role)
);
```

`narrative` is operator-facing text produced by the agent. It is stored here and is not copied to Kafka. Access is limited to investigator and auditor roles. `decision_role` separates the investigator's primary decision from the second approver required for a large reversal. The two roles on one case must be different subjects.

## Cross-database keys

`payment_id` is the join key across services. It is not a foreign key across databases. Each service that stores it treats it as an immutable external identity. `hold_id` and `journal_entry_id` are minted by Ledger and copied onto initiation, authorization, and settlement rows after a successful call.

There is no distributed transaction. The idempotency keys on hold, post, validation, fraud, and authorization are how the workflow reaches exactly one business effect under at-least-once execution.
