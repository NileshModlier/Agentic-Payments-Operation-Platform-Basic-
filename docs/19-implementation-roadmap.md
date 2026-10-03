# 19. Implementation roadmap

| | |
| --- | --- |
| Status | Proposed |
| Audience | The engineer building APOP |
| Constraint | One engineer. No application code in this document. |
| Related | [Repository structure](10-repository-structure.md), [Service boundaries](02-service-boundaries.md), [PRD](18-product-requirements-document.md) |

This is the build order for the Agentic Payment Operations Platform. The architecture pack stays the design. This roadmap is the sequence a single engineer can finish without breaking the ledger rules, the single status writer, or fail-closed risk.

The [repository structure](10-repository-structure.md) describes a wider first cut that pulls fraud and observability forward. For one person, that cut context-switches too early. This document is the sequence to follow. Module names and database ownership do not change.

Planning shape for one engineer, full time, design already written: about 22 weeks. The weeks are a capacity sketch, not a commitment. Each phase ends only when its exit milestone passes. Do not start the next phase on a red milestone.

| Phase | Weeks | You can demo |
| --- | --- | --- |
| 1. Contracts, common, ledger | 1–4 | Place a hold and post a balanced entry, retry, get the same row |
| 2. Payment and validation | 5–8 | Create a book payment that reserves funds, with fraud not yet real |
| 3. Kafka | 9–11 | Status facts and the settlement command survive a restart |
| 4. Settlement | 12–15 | Authorized book payment becomes `SETTLED` once |
| 5. Observability | 16–17 | One payment id opens a trace, a metric, and a log line |
| 6. Fraud | 18–20 | Approve, review, and decline, and an outage places no hold |
| 7. Agentic layer | 21–22 | An agent recommends. A human decision is what moves the payment |

## Decisions for a single engineer

| Decision | Why |
| --- | --- |
| Ledger is its own deployable from phase 1 | It is the book of record. It must not grow inside the payment process. |
| Authorization lives inside `payment-service` until an extract is justified | One person does not need a ninth process on day one. The authorization rules still hold: no `AUTHORIZED` without a hold id, one decision per payment key. Tables stay separate from validation and from the ledger. |
| Fraud is a port until phase 6 | The local profile returns `APPROVE` for fixtures so phases 2–5 can post. Every other profile fail-closes. The port is deleted only when the real service is the only implementation. |
| Correlation ids start in phase 1 | Full OpenTelemetry waits until phase 5. Logs from the first commit still carry `correlation_id` and `payment_id`, and they omit remittance and full account numbers. |
| Reconciliation is the last milestone of phase 4 | Settlement of an external rail is unfinished until a statement can disagree without editing the journal. |
| History is a projector in phase 3 | The channel still reads status from payment initiation. The timeline is allowed to lag. |
| The workbench in phase 7 is API-first | A full console UI is follow-on. Analysts must be able to decide a case before any screen is polished. |

## Phase 1. Contracts, common, ledger

### Goals

Freeze the money language and the ledger invariants before any payment workflow exists. After this phase, a hold and a posting are safe to retry, and every later service talks to the ledger only through the contract.

### Dependencies

The architecture documents, especially the [schema](04-postgresql-schema-design.md), the [API](06-api-architecture.md), and the [event storm](14-event-storming.md). No runtime dependency on Kafka, fraud, or a channel.

### Deliverables

| Deliverable | Done when |
| --- | --- |
| Maven reactor and `platform-bom` | One build pins Java 21, Spring Boot, and test libraries |
| `contracts` for ledger hold, hold release, posting, and account read | OpenAPI examples cover success, insufficient funds, and replay of the same key |
| Shared money type and problem-detail shape in `common` | Minor units are `long`. There is no floating money type in the reactor |
| Idempotency helper in `common` | Callers pass a key. The ledger contract documents replay as `200` with the original id |
| Ledger service | Accounts, holds, journal entries, journal lines, the balance check, and the outbox table present but unused |
| Chart of accounts seed | Customer liability, clearing, nostro simulation, suspense |
| Ledger tests | Balanced entry required. Second post with the same key returns the first entry. Available balance never goes negative. A hold does not change ledger balance |

`common` in this phase is `platform-bom`, `platform-web`, and the money and idempotency types. It does not include a Kafka client. `platform-messaging` arrives in phase 3. `platform-observability` arrives in phase 5, aside from the log fields above.

### Milestones

| Id | Milestone | Exit |
| --- | --- | --- |
| M1.1 | Contracts reviewed against the schema doc | Hold, post, and replay examples match the field names in the API design |
| M1.2 | Ledger accepts a hold and a balanced book posting in one database | Debit minor units equal credit minor units |
| M1.3 | Timeout drill | Kill the client after a possible commit. Retry the same key. One hold or one journal entry |
| M1.4 | Log line check | A ledger log contains `correlation_id` and an id, and does not contain a raw account number |

## Phase 2. Payment service and validation service

### Goals

Accept a payment, validate it, and reserve funds. Payment initiation is the only writer of payment status. Validation owns the pass or the business reject. The ledger remains the only writer of balances.

### Dependencies

Phase 1 ledger API and the idempotency helper. A local PostgreSQL. The fraud port described above. No Kafka yet: status changes are rows. The settlement command is a row in `orchestrator_step`, not a topic.

### Deliverables

| Deliverable | Done when |
| --- | --- |
| `payment-service` | `POST /v1/payments` and `GET /v1/payments/{id}` with idempotency key and request hash |
| Payment aggregate | Statuses through `AUTHORIZED`, `AUTHORIZATION_DECLINED`, and `VALIDATION_FAILED`. `SETTLED` is not set in this phase |
| Authorization module inside payment-service | Calls ledger `PlaceHold`. Refuses to mark `AUTHORIZED` without a hold id |
| `validation-service` | Account, currency, limit, calendar, cutoff, and a screening port |
| Screening port | A fixture file. A hit becomes `VALIDATION_FAILED` and no hold |
| Cancel before settlement | Releases the hold through the ledger key and sets `CANCELLED` |
| Local fraud port | `APPROVE` only in the local profile. Fail-closed elsewhere |

Map names: `payment-service` is Payment Initiation in the architecture. `validation-service` is Payment Validation. They do not share a database.

### Milestones

| Id | Milestone | Exit |
| --- | --- | --- |
| M2.1 | Maya's $1,250 book payment reaches `AUTHORIZED` locally | One payment row, one hold, ledger balance unchanged, available balance down |
| M2.2 | Duplicate key | Same body returns the same payment. Different body returns `409`. No second hold |
| M2.3 | Business rejects | Closed account, limit breach, and screening hit stop before the ledger |
| M2.4 | Insufficient funds | `AUTHORIZATION_DECLINED`, no leftover hold |
| M2.5 | Non-local profile | Fraud port errors. Status is a technical failure. Hold count is zero |

## Phase 3. Kafka integration

### Goals

Make committed facts leave the process, in order, per payment, without a dual write. Teach payment-service to publish the lifecycle and the settlement command from the outbox. Teach a projector to build a timeline that can lag.

### Dependencies

Phases 1 and 2. A local Kafka broker. The topic catalog and envelope in the [Kafka design](03-kafka-event-design.md). Avro schemas added to `contracts` in this phase, not before, so the schemas describe facts the services already commit.

### Deliverables

| Deliverable | Done when |
| --- | --- |
| `platform-messaging` | Outbox relay, CloudEvents envelope, `traceparent` header slot |
| Topics | Lifecycle, fraud assessment (schema only, producer in phase 6), ledger movement, settlement command, settlement item (schema only until phase 4) |
| Payment outbox | `PaymentReceived` through `PaymentAuthorized` and `RequestSettlement` publish after commit |
| Ledger outbox | `FundsHeld` and, once settlement exists, `JournalPosted` |
| Consumer deduplication | `processed_event` primary key. Offset commits after that insert |
| History projector | Timeline and payment history from lifecycle and ledger movements. Rebuild instructions in the module README |
| Dead-letter path | A poison lifecycle record is quarantined and the partition continues |

Do not seek consumer groups as a development shortcut. Replay in tests uses a new event id or a fresh broker.

### Milestones

| Id | Milestone | Exit |
| --- | --- | --- |
| M3.1 | Kill the process after commit and before publish | The relay publishes the unpublished outbox row on restart. One fact on the topic |
| M3.2 | Ordering | Two statuses for one payment stay in commit order on `payments.payment.lifecycle.v1` |
| M3.3 | Duplicate delivery | The projector stores one timeline row per event id |
| M3.4 | Channel read | `GET` still uses payment-service. The timeline is allowed to be behind, and a test proves it can catch up |

## Phase 4. Settlement service

### Goals

Turn `RequestSettlement` into one journal entry and a `SETTLED` status. Cover book immediately, and ACH and wire on their windows. Recover a post that might have committed. Open a break when a statement disagrees, without editing the original lines.

### Dependencies

Phase 3 settlement command and ledger posting API. Payment-service must consume `SettlementItemPosted` and be the service that writes `SETTLED`.

### Deliverables

| Deliverable | Done when |
| --- | --- |
| `settlement-service` | Consumes `payments.command.settlement.v1`, locks a batch, posts through the ledger key |
| Rails | Book posts without waiting. Wire uses the same-day window. ACH uses the next business day |
| Status handoff | Item `POSTED` is a settlement fact. Lifecycle `SETTLED` is an initiation fact |
| Uncertain post | `POST_UNCERTAIN` retries the same ledger key and yields one journal entry |
| Hold expiry | An unlocked hold past expiry releases and the payment becomes `FAILED` with `HOLD_EXPIRED` |
| Reconciliation slice | Import a statement fixture. `125000` versus `125100` opens `AMOUNT_MISMATCH`. Posted lines stay `125000` |
| Rail simulator | Non-production only. It writes the statement file. It cannot call the ledger |

Failure management in this phase is the retry worker for uncertain posts and hold expiry, not the full case console. That keeps a single engineer from building a second product inside settlement.

### Milestones

| Id | Milestone | Exit |
| --- | --- | --- |
| M4.1 | Book happy path | `201` at `SETTLEMENT_PENDING`, then `SETTLED`, one balanced entry, hold consumed |
| M4.2 | ACH cutoff | Friday after cutoff stores Monday's value date and does not post on Friday |
| M4.3 | Crash after the ledger commit | Restart settlement. Same ledger key. One journal entry. Status reaches `SETTLED` |
| M4.4 | Break | Statement mismatch creates a break and does not create a second posting |
| M4.5 | Cancel race | Cancel before lock releases the hold. Cancel after lock returns `409` |

## Phase 5. Observability

### Goals

Make one payment explainable from a trace, a metric, and a log line, using the SLOs and dashboards already specified. Retrofit the services built in phases 1–4. Do not change their business rules in this phase.

### Dependencies

Running payment, validation, ledger, settlement, and Kafka. The [monitoring architecture](08-monitoring-architecture.md).

### Deliverables

| Deliverable | Done when |
| --- | --- |
| `platform-observability` | OpenTelemetry setup shared by the services |
| Collector, Prometheus, Grafana | Local stack. Tempo and Loki as in the monitoring design |
| Trace propagation | Gateway or payment entry creates a trace. The outbox copies `traceparent` onto Kafka. The settlement consumer continues the trace |
| Metrics | Decision latency, decision result, ledger post result, consumer lag, outbox age, idempotency replay |
| Dashboards | Payment funnel, service RED, ledger, Kafka. The others wait until their services exist |
| Alerts as documents | Rules for decision burn, ledger lag, consumer lag, and outbox age checked into the repo. Paging can be a local log until a real receiver exists |
| Cardinality check | No payment id or account id on a metric label |

### Milestones

| Id | Milestone | Exit |
| --- | --- | --- |
| M5.1 | One book payment | Trace shows initiation, validation, ledger hold, outbox publish, settlement, ledger post |
| M5.2 | Failed validation | Trace stops before the ledger span |
| M5.3 | Lag visible | Stop the history projector. The Kafka dashboard shows lag. Status `GET` still works |
| M5.4 | Log join | The same `payment_id` and `trace_id` appear in the log line and the trace |

## Phase 6. Fraud service

### Goals

Replace the local approve port with Fraud Detection. Decisions are approve, review, or decline under a versioned policy. An outage or a Redis miss fail-closes. A review places no hold until a human releases it.

### Dependencies

Phases 1–5. Redis. The [fraud design](17-fraud-detection.md). The assessment topic schema from phase 3.

### Deliverables

| Deliverable | Done when |
| --- | --- |
| `fraud-service` | `AssessFraud` is idempotent per payment. One assessment row |
| Rule engine | Deny list, allow list limited to the new-creditor rule, velocity, country, device hash |
| Redis windows | A payment id updates a window once. Empty Redis fail-closes until warmup |
| Policy version | Thresholds live in `fraud_policy`. Changing them inserts a new version |
| Kafka | `FraudAssessmentCompleted` carries decision, score, policy version, and rule ids. The snapshot stays in the fraud database |
| Review decision API | `CONFIRM_DECLINE`, `RELEASE_TO_AUTHORIZE`, `ESCALATE` recorded with a human subject. No agent yet |
| Port removal | The local `APPROVE` stub is gone from every profile |

The person who submitted the payment cannot release it. Enforce that in the decision API in this phase so phase 7 does not invent the control.

### Milestones

| Id | Milestone | Exit |
| --- | --- | --- |
| M6.1 | Straight-through approve | Hold appears only after `APPROVE` |
| M6.2 | Decline | `FRAUD_DECLINED`, hold count zero, replay returns the same assessment |
| M6.3 | Review | `FRAUD_REVIEW`, no hold. Release then creates one hold. Decline creates none |
| M6.4 | Fraud process down | Circuit open. Creates fail closed. Sample payments have no hold |
| M6.5 | Redis flush | Scoring does not treat an empty window as a quiet customer |

## Phase 7. Agentic AI layer

### Goals

Add investigation on top of cases the earlier phases already create. Agents recommend. Humans decide. The payment hot path does not call the model.

### Dependencies

Phase 6 review cases and phase 4 breaks. The [agent architecture](05-agentic-ai-architecture.md) and the [workbench](13-investigation-workbench.md). An LLM gateway the engineer can point at a local or enterprise endpoint. The golden-set fixtures can be synthetic and small.

### Deliverables

| Deliverable | Done when |
| --- | --- |
| `investigation-agent` | Intake from `FRAUD_REVIEW`, high-severity breaks, and escalated failures |
| Four roles | Supervisor, Fraud, Root Cause, Reporting, each with a tool subset and an output schema |
| Tool gateway | Reads bound to the case's payment id. No ledger post tool |
| Policy guard | Rejects a recommendation that cites an evidence id the session did not retrieve |
| Human decision | Existing phase 6 decision API remains the only path to initiation or to a reversal command |
| Kill switch | `investigation.agent.enabled` stops new sessions. Intake and human decisions still work |
| Evaluation fixtures | A handful of synthetic cases with a required disposition family and a forbidden one |
| Case API | Queue, assist, decision, and a report draft. A minimal page is optional and last |

Build the Fraud Agent first, then Root Cause on a break, then the Reporting draft. The Supervisor is a router in front of those, not a separate research project.

### Milestones

| Id | Milestone | Exit |
| --- | --- | --- |
| M7.1 | Fraud review assist | Recommendation stored. Payment still `FRAUD_REVIEW`. No hold |
| M7.2 | Human decline | Status becomes `FRAUD_DECLINED` only after the decision API |
| M7.3 | Break recommendation | Root Cause may propose reversal. The journal does not change until a human approves |
| M7.4 | Guard | A recommendation that names a payment id outside the case is not shown as advice |
| M7.5 | Kill switch | Assist is refused. An analyst can still decline the same case |
| M7.6 | Model down | LLM circuit open. The queue remains usable. Creates and settlement stay up |

## After phase 7

The MVP release bar in the PRD is larger than these seven phases. Finish these before calling the MVP done, still as one engineer, still without new product scope:

| Follow-on | Why it is not inside the seven phases |
| --- | --- |
| API gateway as its own process, with Redis rate limits | Phases 2–6 can authenticate in a filter. The gateway becomes worth a deployable when more than one service is on the edge |
| Full failure-case console | Phase 4 already retries uncertain posts. The console is the workbench queue from phase 7, extended |
| Four-eyes threshold wired to a real number from risk | The second approver path exists in the case model. The amount is an open product decision |
| Alert receiver and an on-call drill of RB-3 and RB-5 | Phase 5 checks the rules in. A drill is an operational rehearsal, not a feature |
| Extract authorization to its own deployable | Only if the in-process module becomes a change bottleneck |

Do not start inbound payments, cards, foreign exchange, or a fraud model until the phase 7 exit milestones and AC-1 through AC-10 in the PRD are true.

## Working agreement

One phase branch at a time. Each milestone is a test or a documented drill, not a screenshot. Contracts change before service behavior. A schema change is expand-only while the previous process is still what a rollback would run. When a later phase is tempted to update a journal line or to let the agent call the ledger, stop. That change contradicts the architecture and it is not a shortcut.
