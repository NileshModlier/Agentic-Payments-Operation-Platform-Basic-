# 3. Kafka event design

| | |
| --- | --- |
| Status | Proposed |
| Audience | Service owners and integration reviewers |
| Related | [Service boundaries](02-service-boundaries.md), [Schema](04-postgresql-schema-design.md), [Monitoring](08-monitoring-architecture.md) |

Kafka is the integration log. A record is published only after the publisher has committed the database transaction that makes the fact true. Consumers are at-least-once and idempotent.

## Naming and governance

| Kind | Topic pattern | Example |
| --- | --- | --- |
| Fact | `payments.<aggregate>.<fact>.v<major>` | `payments.payment.lifecycle.v1` |
| Command | `payments.command.<action>.v<major>` | `payments.command.settlement.v1` |
| Audit | `platform.audit.recorded.v1` | |
| Dead letter | `<source-topic>.<consumer-group>.dlq` | `payments.payment.lifecycle.v1.transaction-history-projector.dlq` |

Record keys are strings. The partition key is the id that must stay ordered for that consumer, listed per topic below.

Schema format is Avro. A schema registry enforces `BACKWARD` compatibility on each subject. A breaking change is a new major version and a new topic. During a migration the publisher dual-writes the old and new majors for one release, consumers move, and the old topic is retired after its retention plus a documented drain period.

Producers and consumers live in the service that owns them. A shared library may contain the generated Avro classes. It does not contain business decisions.

## Envelope

The value is a CloudEvents 1.0 structured record whose `data` payload is Avro.

| Field | Meaning |
| --- | --- |
| `specversion` | `1.0` |
| `id` | Event id, UUIDv7. Globally unique. Consumer deduplication key. |
| `source` | `urn:apop:<service>` |
| `type` | `com.apop.<topic-suffix>` for example `com.apop.payments.payment.lifecycle.v1` |
| `subject` | Same id used as the Kafka key |
| `time` | UTC timestamp of the commit |
| `datacontenttype` | `application/avro` |
| `data` | Versioned payload |

Kafka headers, separate from the body so interceptors can read them without decoding Avro:

| Header | Required | Meaning |
| --- | --- | --- |
| `ce_id` | Yes | Copy of the event id |
| `ce_type` | Yes | Copy of the type |
| `correlation-id` | Yes | End-to-end id of the business operation, UUIDv7 |
| `causation-id` | Yes | Event or request that caused this record |
| `idempotency-key` | Commands and ledger-related facts | Original operation key |
| `legal-entity-id` | Yes | Entity scope |
| `traceparent` | Yes | W3C trace context |
| `tracestate` | When present | Vendor trace state |
| `content-type` | Yes | `application/cloudevents+avro` |

Payloads carry account ids, amounts, statuses, and reason codes. They do not carry party names, raw account numbers, tokens, or free-text remittance. Remittance stays in Payment Initiation and is readable through an authorized API. This keeps the log useful for operations without turning every consumer into a confidential-data store.

## Topic catalog

Starting partition count is 12 for payment-keyed topics and 6 for lower-volume case topics. Replication factor is 3. Minimum in-sync replicas is 2. Cleanup policy is `delete`.

| Topic | Key | Retention | Owner |
| --- | --- | --- | --- |
| `payments.payment.lifecycle.v1` | `paymentId` | 30 days hot, then archive | Payment Initiation |
| `payments.fraud.assessment.v1` | `paymentId` | 30 days hot, then archive | Fraud Detection |
| `payments.ledger.movement.v1` | `accountId` | 30 days hot, then archive | Ledger |
| `payments.settlement.item.v1` | `paymentId` | 30 days hot, then archive | Payment Settlement |
| `payments.settlement.batch.v1` | `batchId` | 30 days hot, then archive | Payment Settlement |
| `payments.reconciliation.break.v1` | `breakId` | 30 days hot, then archive | Reconciliation |
| `payments.failure.case.v1` | `caseId` | 30 days hot, then archive | Failure Management |
| `payments.investigation.case.v1` | `caseId` | 30 days hot, then archive | Investigation Agent |
| `payments.command.settlement.v1` | `paymentId` | 14 days | Payment Initiation |
| `payments.command.compensation.v1` | `paymentId` | 14 days | Failure Management |
| `platform.audit.recorded.v1` | `auditId` | 30 days hot, then archive | Every service via a shared audit emitter |

Hot retention is an operational buffer. The regulatory copy is the archive sink, retained 7 years for audit and ledger-related facts. The Ledger database backups are the financial record. Rebuilding the ledger from Kafka is not the recovery design.

Dead-letter topics retain for 30 days. They are not archived as business facts until an operator promotes a corrected record.

## Lifecycle spine

`payments.payment.lifecycle.v1` is the ordered status log for one payment. Only Payment Initiation publishes it.

`data` fields:

| Field | Type | Meaning |
| --- | --- | --- |
| `paymentId` | UUID | Aggregate id |
| `status` | enum | Canonical status from the [state model](01-high-level-architecture.md) |
| `previousStatus` | enum | Prior status |
| `rail` | enum | `BOOK`, `SIMULATED_ACH`, `SIMULATED_WIRE` |
| `amountMinor` | long | Positive minor units |
| `currency` | string | ISO 4217 |
| `debtorAccountId` | UUID | Internal debtor |
| `creditorAccountId` | UUID or null | Internal creditor for `BOOK` |
| `creditorFingerprint` | string or null | Hash of the external creditor identity |
| `valueDate` | date | Bank-calendar value date after cutoff rolling |
| `reasonCode` | string or null | Present on declines and failures |
| `stepId` | UUID | Orchestrator step that produced the transition |

Status values published on this topic are the full canonical set: `RECEIVED`, `VALIDATING`, `VALIDATION_FAILED`, `FRAUD_SCREENING`, `FRAUD_REVIEW`, `FRAUD_DECLINED`, `AUTHORIZING`, `AUTHORIZED`, `AUTHORIZATION_DECLINED`, `SETTLEMENT_PENDING`, `SETTLED`, `SETTLEMENT_FAILED`, `CANCELLED`, `FAILED`.

Reason codes used by publishers:

| Code | When |
| --- | --- |
| `VALIDATION_ACCOUNT_UNKNOWN` | Debtor or book creditor is unknown |
| `VALIDATION_ACCOUNT_CLOSED` | Account is not open |
| `VALIDATION_CURRENCY_MISMATCH` | Account currency differs from the payment |
| `VALIDATION_LIMIT_EXCEEDED` | Product limit exceeded |
| `VALIDATION_CUTOFF_PASSED` | Value date cannot be rolled into an open window |
| `VALIDATION_SANCTIONS_REJECT` | Screening port returned a hit |
| `VALIDATION_SCHEMA` | Structural rejection |
| `FRAUD_DECLINE` | Fraud decision `DECLINE` |
| `FRAUD_REVIEW_EXPIRED` | Review was not released before the case expiry |
| `AUTH_INSUFFICIENT_FUNDS` | Available balance would become negative |
| `SETTLEMENT_POST_FAILED` | Posting ended in a business refusal |
| `TECHNICAL_TIMEOUT` | Synchronous budget expired with no business decision |
| `TECHNICAL_DEPENDENCY` | Dependent service stayed unavailable through the failure policy |
| `HOLD_EXPIRED` | Hold expired before settlement lock |
| `CANCELLED_BY_CLIENT` | Accepted cancellation |

## Other payloads

### Fraud assessment

| Field | Meaning |
| --- | --- |
| `paymentId` | Payment |
| `assessmentId` | Decision row |
| `decision` | `APPROVE`, `REVIEW`, `DECLINE` |
| `score` | Integer 0 to 1000 |
| `policyVersion` | Version of the rule policy |
| `ruleIds` | Identifiers of rules that fired, not their parameters |

### Ledger movement

One record per affected account so the partition key can be `accountId`.

| Field | Meaning |
| --- | --- |
| `movementId` | Unique movement id |
| `accountId` | Partition key |
| `paymentId` | Related payment, when the movement came from a payment |
| `journalEntryId` | Set when the movement is a posting or reversal |
| `holdId` | Set when the movement is a hold or release |
| `movementType` | `HOLD_PLACED`, `HOLD_RELEASED`, `POSTED`, `REVERSED` |
| `direction` | `DEBIT` or `CREDIT` for postings; null for holds |
| `amountMinor` | Positive minor units |
| `currency` | ISO 4217 |
| `ledgerBalanceMinor` | Balance of this account after the commit |
| `availableMinor` | Available balance after the commit |

### Settlement item and batch

Item: `settlementItemId`, `batchId`, `paymentId`, `rail`, `itemStatus` (`INCLUDED`, `POSTED`, `POST_UNCERTAIN`, `POST_REFUSED`), `amountMinor`, `currency`, `valueDate`, `journalEntryId`.

Batch: `batchId`, `rail`, `windowStart`, `windowEnd`, `batchStatus` (`OPEN`, `LOCKED`, `POSTED`, `FAILED`), `itemCount`, `totalMinor`.

### Reconciliation break

`breakId`, `runId`, `breakClass`, `paymentId` nullable, `statementLineId` nullable, `amountMinor`, `currency`, `valueDate`, `severity`.

### Failure case and investigation case

Both publish identifiers and state, not a copy of the payment.

Failure: `caseId`, `paymentId`, `caseType`, `caseStatus`, `reasonCode`, `attemptCount`.

Investigation: `caseId`, `paymentId`, `source` (`FRAUD_REVIEW`, `RECON_BREAK`, `ESCALATED_FAILURE`), `caseStatus`, `recommendationId` nullable, `humanDecision` nullable.

Opening a case is done by the owning service. Fraud review intake is a consumer of the lifecycle status `FRAUD_REVIEW` inside Investigation Agent. That consumer creates the case and then publishes `payments.investigation.case.v1`.

### Commands

Settlement command, published by Payment Initiation in the same transaction as `SETTLEMENT_PENDING`:

| Field | Meaning |
| --- | --- |
| `commandId` | Unique command id |
| `paymentId` | Payment to settle |
| `rail` | Rail |
| `amountMinor` | Amount |
| `currency` | Currency |
| `debtorAccountId` | Debtor |
| `creditorAccountId` | Creditor or null |
| `holdId` | Hold that settlement must consume |
| `valueDate` | Value date |
| `ledgerIdempotencyKey` | Key Ledger will see on the post |

Compensation command, published by Failure Management after a human or a pre-approved policy selects an action:

| Field | Meaning |
| --- | --- |
| `commandId` | Unique command id |
| `paymentId` | Payment |
| `action` | `RELEASE_HOLD` or `POST_REVERSAL` |
| `holdId` | Required for release |
| `originalJournalEntryId` | Required for reversal |
| `ledgerIdempotencyKey` | Key Ledger will see |
| `approvedBy` | Human subject, or `POLICY` when the action is an automatic hold release on expiry or pre-post cancel |

`POST_REVERSAL` requires `approvedBy` to be a human subject. The platform policy for automatic compensation is limited to releasing a hold before any posting exists.

### Audit

`auditId`, `actorType` (`USER`, `SERVICE`, `AGENT`), `actorId`, `action`, `resourceType`, `resourceId`, `result`, `correlationId`. The audit payload has no free-text customer content. Detail stays in the service audit table under the same `auditId`.

## Producers and consumers

| Topic | Producers | Consumer groups |
| --- | --- | --- |
| Lifecycle | `payment-initiation` | `transaction-history-projector`, `investigation-intake`, `failure-watch`, `audit-archive` |
| Fraud assessment | `fraud-detection` | `transaction-history-projector`, `audit-archive` |
| Ledger movement | `ledger` | `payment-initiation-hold-outcomes`, `transaction-history-projector`, `reconciliation-internal`, `audit-archive` |
| Settlement item | `payment-settlement` | `payment-initiation-settlement-results`, `transaction-history-projector`, `reconciliation-internal`, `audit-archive` |
| Settlement batch | `payment-settlement` | `reconciliation-internal`, `rail-simulator` (simulation only), `audit-archive` |
| Break | `reconciliation` | `investigation-intake`, `audit-archive` |
| Failure case | `failure-management` | `investigation-intake`, `audit-archive` |
| Investigation case | `investigation-agent` | `audit-archive` |
| Settlement command | `payment-initiation` | `payment-settlement-executor` |
| Compensation command | `failure-management` | `ledger-compensation` |
| Audit | every service | `audit-archive` |

`failure-watch` observes `FAILED`, `SETTLEMENT_FAILED`, and long gaps. It does not change payment status. It opens a failure case when the durable step in its own intake says the grace period has elapsed. Payment Initiation remains the status writer. It advances the aggregate when a settlement item fact arrives, and when `payment-initiation-hold-outcomes` observes `HOLD_RELEASED` for a payment that Initiation cancelled or that Failure Management expired.

`ledger-compensation` is a consumer inside the Ledger service. It accepts only the compensation catalog and applies the hold release or reversing entry in process. Any other action is dead-lettered. Settlement retries an uncertain post itself, on request from Failure Management, by calling the Ledger posting API with the original idempotency key.


## Delivery semantics

| Rule | Design |
| --- | --- |
| Publish | Outbox relay. The relay marks `published_at` only after the broker acknowledges the record at `acks=all`. |
| Producer identity | `enable.idempotence=true`. The outbox row id is the producer record identity. |
| Consume | At least once. Each consumer database has a `processed_event` primary key on event id. |
| Offset commit | After the local transaction that stores the effect and the `processed_event` row. |
| Ordering | Guaranteed per key, not across keys. Ledger movements for one payment's two accounts can land on different partitions. The journal entry id ties the lines together inside Ledger. |
| Time | Consumers do not use wall clock to order events. They use the per-key order and the `time` field for display. |

The relay publishes in outbox insertion order per aggregate, one in-flight publish per aggregate id, so a slow broker cannot reorder two statuses of the same payment at the producer.

## Dead letters and poison records

A consumer retries a failed record five times with exponential backoff inside the consumer, without committing the offset and without blocking the whole application beyond that partition. The partition's other keys continue on other consumer threads. After the fifth failure the consumer publishes the original value, headers, and a failure reason to its dead-letter topic, writes a `processed_event` row with outcome `DEAD_LETTERED`, commits the offset, and emits a failure-case command to its local outbox when the owning workflow is Failure Management's watch. Domain consumers that are not Failure Management emit a small `processing-failed` audit and Failure Management's dead-letter consumer opens the case.

A dead-letter consumer does not automatically replay. An operator, or a Failure Management retry after a fix, republishes a corrected record with a new event id and a `causation-id` pointing at the dead-lettered id. The original event id stays processed so a blind replay is a no-op.

## Trace propagation

The publisher copies `traceparent` from the active span onto the Kafka header. The consumer extracts it and starts a consumer span linked to that context. The outbox relay creates a publish span so a trace shows the HTTP decision, the database commit, the relay publish, and each downstream projection as one tree. Span naming follows OpenTelemetry messaging semantic conventions.

## Versioning examples

Adding an optional `operatorCommentCode` to a break, with a default of null and `BACKWARD` compatibility, stays on `v1`.

Changing the meaning of `amountMinor` or removing `holdId` from the settlement command is a `v2` topic. Settlement consumes both majors during the migration and deduplicates on `paymentId` plus command purpose.

## Archive sink

A dedicated consumer group `audit-archive` writes Avro records to immutable object storage partitioned by topic, date, and legal entity. The sink is idempotent on event id. Access to the archive is an auditor role, separate from operational consumer credentials.
