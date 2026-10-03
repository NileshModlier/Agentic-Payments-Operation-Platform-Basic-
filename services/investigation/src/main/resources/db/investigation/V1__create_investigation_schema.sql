CREATE TABLE investigation_case (
    case_id         uuid PRIMARY KEY,
    payment_id      uuid         NOT NULL,
    source          varchar(32)  NOT NULL,
    source_ref      uuid         NOT NULL,
    case_status     varchar(24)  NOT NULL,
    opened_at       timestamptz  NOT NULL DEFAULT now(),
    closed_at       timestamptz,
    submitted_by    varchar(128) NOT NULL,
    severity        varchar(16)  NOT NULL,
    queue_name      varchar(16)  NOT NULL,
    amount_minor    bigint       NOT NULL,
    currency        char(3)      NOT NULL,
    evidence        jsonb        NOT NULL,
    escalated_by    varchar(128),
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
    case_id        uuid         NOT NULL REFERENCES investigation_case (case_id),
    model_route    varchar(64)  NOT NULL,
    status         varchar(16)  NOT NULL,
    started_at     timestamptz  NOT NULL DEFAULT now(),
    finished_at    timestamptz,
    token_input    integer,
    token_output   integer,
    agent_role     varchar(32)  NOT NULL,
    guard_check    varchar(64),
    memory         jsonb        NOT NULL,
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

CREATE TABLE case_note (
    note_id     uuid PRIMARY KEY,
    case_id     uuid         NOT NULL REFERENCES investigation_case (case_id),
    session_id  uuid,
    author      varchar(128) NOT NULL,
    body        varchar(4000) NOT NULL,
    created_at  timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE case_report (
    report_id     uuid PRIMARY KEY,
    case_id       uuid         NOT NULL REFERENCES investigation_case (case_id),
    session_id    uuid         NOT NULL REFERENCES agent_session (session_id),
    draft         text         NOT NULL,
    published_by  varchar(128),
    published_at  timestamptz,
    created_at    timestamptz  NOT NULL DEFAULT now()
);

CREATE TABLE audit_event (
    audit_id      uuid PRIMARY KEY,
    actor         varchar(128) NOT NULL,
    action        varchar(64)  NOT NULL,
    resource_type varchar(64)  NOT NULL,
    resource_id   varchar(64)  NOT NULL,
    detail        jsonb        NOT NULL,
    created_at    timestamptz  NOT NULL DEFAULT now()
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
