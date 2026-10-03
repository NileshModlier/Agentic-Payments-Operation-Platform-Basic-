# 18. Product requirements document

| | |
| --- | --- |
| Status | Proposed |
| Product | Agentic Payment Operations Platform (APOP) |
| Date | 2026-10-02 |
| Audience | Product, engineering, risk, operations, and audit |
| Related | [High-level architecture](01-high-level-architecture.md), [Domain guide](11-payment-domain-guide.md), [Fraud detection](17-fraud-detection.md), [Investigation workbench](13-investigation-workbench.md) |

## Executive summary

APOP is a simulated bank payment platform. A channel submits an outbound payment. The platform validates it, scores it for fraud, reserves funds with a hold, and later settles it on an internal book transfer or a simulated ACH or wire rail. Operators search the payment, reconcile it against a statement, and resolve failures. Fraud analysts decide reviews. An investigation agent explains evidence and recommends a next step. A person approves every exception that moves money.

The product exists so engineers and operators can run a full payment lifecycle with production controls: one ledger, idempotent creates, fail-closed risk checks, an audit trail, and a human on every reversal. It does not connect to real clearing houses, card networks, or customer accounts. It is not an official JPMorgan Chase system.

Version 1 is the MVP. It is outbound only, in one currency per payment, for one legal entity. Customers of the product are channel applications and bank staff. The paying customer in the simulation, such as Maya paying a $1,250 invoice, is the person the channel represents.

## Goals

| Id | Goal | How we will know |
| --- | --- | --- |
| G1 | A channel can submit an outbound payment and receive one decision without creating a second payment on retry. | Duplicate-key tests return one payment id and one hold. |
| G2 | Authorized payments settle on the correct rail clock, and the official balance changes only when the journal posts. | Book payments reach `SETTLED` quickly. ACH waits for the next business day. Holds do not change ledger balance. |
| G3 | Risk checks stop a payment before funds are reserved when fraud or screening cannot answer. | Outage tests show no hold. |
| G4 | Staff can explain any payment from status, timeline, and Kafka facts, and can close breaks and reviews. | Workbench flows cover search, fraud review, breaks, and failures. |
| G5 | Agents shorten investigation time and never become the approver. | Recommendations cite evidence. Decisions store a human subject. |
| G6 | Operations can detect and recover incidents without double-posting. | Runbooks have an owner. Sampled keys show one financial effect. |

The product optimizes for a correct, explainable payment over a fully automatic one. A review queue is a successful outcome when the policy asked for a person.

## Success metrics

Metrics use the names in the [monitoring architecture](08-monitoring-architecture.md). Targets are for the production-shaped environment after MVP launch. They are rolling 30-day objectives unless noted.

| Metric | Target | Why it matters |
| --- | --- | --- |
| Decision availability | 99.9% of creates return `201`, `202`, or a business decline | Channels can trust the API |
| Decision latency | 99% under 800 ms | The synchronous path ends at authorization, review, or decline |
| Book settlement lag | 99% of book payments settle within 2 seconds of authorization | Internal transfers feel immediate and remain asynchronous |
| History freshness | 99% of timeline updates within 2 seconds | Staff do not chase a stale story |
| Ledger post acknowledgement | 99.99% of accepted posts commit on the primary | The book of record holds |
| Duplicate financial effects | Zero in acceptance and in incident samples | One key means one hold and one journal entry |
| Fail-closed correctness | 100% of fraud or screening outages in test leave no hold | Risk controls fail safe |
| Fraud-review leakage | 0% of `FRAUD_REVIEW` payments have a hold before a human release | Review is not a weak yes |
| Reversal control | 100% of reversals have a human approver, and a second person above the threshold | Four-eyes holds |
| Break age | High-severity breaks decided within 4 business hours | The ticket in the runbook matches the product promise |
| Agent assist availability | 99% of sessions finish `COMPLETED` or `STOPPED` with the case still visible | Assistance may fail. The queue may not. |
| Agent agreement | Reported weekly. A drop under 50% over a day with at least 20 decisions opens a review. | Quality is watched. It does not auto-change payment policy. |

Leading indicators on the payment funnel: creates, approvals, reviews, declines, and settlements by rail. A rise in `TECHNICAL_TIMEOUT` is an incident, not a change in fraud appetite.

## Business requirements

| Id | Requirement |
| --- | --- |
| BR-1 | Accept an outbound payment for rails `BOOK`, `SIMULATED_ACH`, and `SIMULATED_WIRE` with an amount in integer minor units and an ISO 4217 currency. |
| BR-2 | Require an idempotency key on create. The same key and body return the original payment. A different body returns a conflict. |
| BR-3 | Validate account, currency, limits, business calendar, cutoff, and screening before fraud and before any hold. |
| BR-4 | Score fraud and return approve, review, or decline. Deny lists, velocity, session country, and device hash are in the MVP scorer. |
| BR-5 | Reserve funds with a hold only after an approve, or after a fraud analyst releases a review. Available balance falls. Ledger balance waits. |
| BR-6 | Decline when available balance would go below zero. Overdraft is out of the product. |
| BR-7 | Settle book payments immediately after authorization, wire payments in the next same-day window, and ACH payments on the next business day. |
| BR-8 | Post a balanced journal entry at settlement and release the hold in that same ledger transaction. |
| BR-9 | Allow cancel only before the settlement batch locks. After settlement, correction is a reversal, and the original payment stays settled. |
| BR-10 | Match external settlement items to a statement and open a break when they disagree. Do not edit the posted amount. |
| BR-11 | Keep a timeline and account activity that staff and channels can read, rebuilt from events if needed. |
| BR-12 | Give fraud analysts, operations analysts, and support engineers a workbench with search, queues, and decisions limited by role. |
| BR-13 | Record an audit event for creates, decisions, list changes, and report publication. Retain the financial and audit record for 7 years. |
| BR-14 | Fail closed when fraud scoring or screening is unavailable. |
| BR-15 | Keep agents off the hot path. A human decision is required to release a review, retry a stuck step, or reverse a settled payment. |

## User stories

Personas: channel integrator, fraud analyst, operations analyst, support engineer, senior approver, auditor.

### Channel integrator

**US-1.** As a channel integrator, I submit Maya's invoice payment once and get a stable payment id, so a lost response does not pay Northwind twice.

Acceptance criteria:

- Given a new idempotency key and a valid $1,250 book payment, the API returns `201` with a payment id and a status of `AUTHORIZED`, `SETTLEMENT_PENDING`, a terminal decline, or `FRAUD_REVIEW`.
- Given the same key and the same body after a lost response, the API returns `200` with the same payment id.
- Given the same key and a different amount, the API returns `409` and the original payment is unchanged.
- Given no key, the API returns `400`.

**US-2.** As a channel integrator, I can tell a pending payment from a finished one, so I do not tell Maya that Northwind has been paid at authorization time.

Acceptance criteria:

- The create response for a successful fraud approve is `AUTHORIZED` or `SETTLEMENT_PENDING`, not `SETTLED`.
- A later read shows `SETTLED` only after the journal entry exists.
- An ACH payment accepted after cutoff shows a value date on the next business day and stays pending until that window posts.

**US-3.** As a channel integrator, I can cancel before the batch locks, so Maya can withdraw a payment that has not been posted.

Acceptance criteria:

- Cancel from `AUTHORIZED` or `SETTLEMENT_PENDING` before lock returns `CANCELLED` and releases the hold.
- Cancel after lock returns `409`.
- Cancel of a settled payment is rejected. Reversal is an operator case.

### Fraud analyst

**US-4.** As a fraud analyst, I work the oldest review first and see why it scored, so I can decline or release it.

Acceptance criteria:

- A `REVIEW` result creates a case and leaves the payment in `FRAUD_REVIEW` with no hold.
- The case shows score, policy version, rule ids, and the feature snapshot to the fraud analyst role.
- `CONFIRM_DECLINE` moves the payment to `FRAUD_DECLINED` and still places no hold.
- `RELEASE_TO_AUTHORIZE` is rejected for the person who submitted the payment.
- A successful release is the first time a hold id appears.

**US-5.** As a fraud analyst, I can ask the Fraud Agent for a recommendation and still make the decision myself.

Acceptance criteria:

- Assist returns a recommendation of confirm decline, release, or escalate, with evidence ids from this case.
- The payment status does not change when the recommendation is stored.
- If the agent is off, the case remains decidable from the snapshot and the timeline.

### Operations analyst

**US-6.** As an operations analyst, I compare our posted amount with the statement, so a mismatch becomes a case instead of a silent edit.

Acceptance criteria:

- A statement line of `125100` against a posted `125000` with the same end-to-end id opens `AMOUNT_MISMATCH`.
- The payment read remains `SETTLED` for `125000`.
- The journal lines are unchanged.
- Closing the break with no money movement records `NO_ACTION` and a human subject.

**US-7.** As an operations analyst, I reverse a settled payment only through a new entry, with a second person when the amount is large.

Acceptance criteria:

- `PROPOSE_REVERSAL` below the threshold posts one reversing entry after one approver.
- Above the threshold the reversing entry waits for a different senior approver.
- The original payment stays `SETTLED` and the original lines remain.
- The agent cannot be either approver.

**US-8.** As an operations analyst, I retry a technical failure with the original key, so a timeout does not create a second post.

Acceptance criteria:

- A settlement timeout after a possible commit shows `POST_UNCERTAIN`.
- Accepting `RETRY_TECHNICAL` calls the ledger with the same key and yields one journal entry.
- The analyst's subject is on the decision audit row.

### Support engineer

**US-9.** As a support engineer, I look up Maya's payment and explain why it is pending, without changing it.

Acceptance criteria:

- Search by payment id opens a dossier whose status comes from Payment Initiation.
- The timeline and Kafka facts are available, and the page says when the timeline is behind.
- Support has no decline, release, retry, or reversal action.
- Support can escalate a failure case into investigation.

### Auditor

**US-10.** As an auditor, I reconstruct who decided a payment months later.

Acceptance criteria:

- The audit read for a payment id lists creates, fraud decisions, human decisions, and ledger actions with actor and time.
- Journal lines for that payment are still present and unmodified.
- Agent recommendations are distinguishable from human decisions.

## Acceptance criteria

MVP is accepted when the following are true in the production-shaped environment, on synthetic data:

| Id | Bar |
| --- | --- |
| AC-1 | US-1 through US-10 pass in acceptance tests. |
| AC-2 | The six sequences in the [sequence diagrams](15-sequence-diagrams.md) match observed status, hold, and journal counts. |
| AC-3 | Decision availability and latency meet the targets over the launch soak, or misses are explained by a named incident. |
| AC-4 | A forced fraud outage and a forced Redis outage produce no new holds. |
| AC-5 | A ledger timeout during post, followed by retry, produces one journal entry. |
| AC-6 | High-severity breaks and fraud reviews appear on the workbench without an engineer reading a database. |
| AC-7 | Audit for a sampled settled payment joins initiation, ledger, and the human decision when one exists. |
| AC-8 | The agent cannot call a ledger post. A policy-guard failure stores no recommendation text as advice. |
| AC-9 | Runbooks RB-1 through RB-7 are linked from their alerts and have a named owner. |
| AC-10 | No card, inbound, foreign-exchange, or overdraft path is exposed on the API. |

## Operational requirements

| Id | Requirement |
| --- | --- |
| OR-1 | Run on Kubernetes with separate namespaces for edge, payments, risk, ledger, operations, and data. |
| OR-2 | Publish payment facts to Kafka only after the matching database commit, through an outbox. |
| OR-3 | Keep the ledger on its own PostgreSQL primary with synchronous replicas and a backup younger than 24 hours. |
| OR-4 | Treat Redis as recoverable cache. Idempotency and assessments remain in PostgreSQL. |
| OR-5 | Page on decision-availability burn, fraud circuit open, ledger replication lag, settlement consumer lag, and outbox age, using the severities in the [runbook](16-sre-runbook.md). |
| OR-6 | Propagate a trace id from the gateway onto Kafka. An operator can start from a payment id and reach status, step, and ledger key. |
| OR-7 | Roll back a bad release by image digest. Fraud and limit policy changes ship as a new version, not a console edit. |
| OR-8 | Recover a stuck payment by retrying its original key. Consumer offsets are not discarded to "skip ahead." |
| OR-9 | Keep the rail simulator out of production. Statement files in non-production use the same match rules as a real file would. |
| OR-10 | Mask account numbers to the last four in the workbench, logs, and events. Retain audit and journal data for 7 years. |

## Agentic AI requirements

| Id | Requirement |
| --- | --- |
| AI-1 | Provide four roles: Supervisor, Fraud, Root Cause, and Reporting. They run inside the investigation service, off the create path. |
| AI-2 | Start the Supervisor on every assisted case. It chooses at most one specialist. |
| AI-3 | Allow the Fraud Agent to recommend decline, release, or escalate, using only that case's payment and assessment. |
| AI-4 | Allow the Root Cause Agent to recommend retry, no action, or escalate, and a reversal only when the case is a reconciliation break. |
| AI-5 | Allow the Reporting Agent to draft a report from decisions already recorded. Publishing the report does not move money. |
| AI-6 | Enforce a tool allow-list, a turn budget, and a policy guard. A recommendation must cite evidence ids returned in that session. |
| AI-7 | Treat remittance and party names as untrusted data. They cannot change the tool list or the system prompt. |
| AI-8 | Keep a kill switch that stops new sessions and leaves queues and human decisions working. |
| AI-9 | Retain prompts for 30 days for incident review, then delete them. Do not use them to train a model. |
| AI-10 | Gate agent prompt and tool changes on an offline golden-set evaluation. Production agreement is monitored and does not change policy by itself. |

## MVP scope

The MVP is one legal entity, outbound payments, synthetic data, and the staff workflows above.

| In the MVP | Notes |
| --- | --- |
| Book, simulated ACH, and simulated wire | One currency on the payment. USD is the launch currency. |
| Create, read, cancel, timeline, account activity | External API `/v1` |
| Validation, screening port, fraud rules and velocity, lists, country and device context | Rules are the scorer. A model is not required to launch. |
| Holds, double-entry ledger, settlement windows | Ledger is the book of record |
| Reconciliation breaks and the workbench | Humans close breaks |
| Failure retry and compensation | Same idempotency key |
| Investigation agents and human decisions | Four-eyes on large reversals |
| Audit, metrics, traces, and the SRE runbook | Owners assigned before launch |
| Rail simulator | Non-production only |

Launch soaks against the success metrics. AC-1 through AC-10 are the release gate.

## Post-MVP scope

These are real needs and they are not in the first release. Each one should reopen the PRD rather than arrive as an unscoped API field.

| Horizon | Capability | Why it waits |
| --- | --- | --- |
| Next | Inbound credits on the same ledger | MVP is outbound. Inbound needs a second instruction shape and matching rules. |
| Next | Governed model score inside the existing fraud decision | The interface is reserved. Launch does not depend on a model, and a model outage must keep fail-closed behavior. |
| Next | Fee leg on `GL-FEE-INCOME` | The account exists. Pricing is a product decision. |
| Next | Customer-visible status notifications | Channels poll in the MVP. Push notification is a separate contract. |
| Later | Card presentment and chargebacks | Chargeback is a network dispute, not a cancel. It needs its own intake and must not rewrite `SETTLED`. |
| Later | Foreign exchange and multi-currency journal entries | MVP posts one currency per entry. |
| Later | Overdraft and interest | Available balance may not go negative in the MVP. |
| Later | More than one legal entity in production booking | `legal_entity_id` is stored so this does not break the schema. |
| Later | A second region | Backups and the archive are the recovery story until that decision. |
| Later | A customer dispute portal | Staff cases cover investigation in the MVP. |

## Future roadmap

| Phase | Outcome | Exit |
| --- | --- | --- |
| Phase 0, foundation | Contracts, ledger, idempotency, observability hooks | A hold and a balanced post can be retried safely in test |
| Phase 1, MVP | This PRD's scope, including workbench and agents | AC-1 through AC-10 |
| Phase 2, straighten the operation | Inbound credits, fee leg, notifications, model score behind the fraud policy | Fail-closed model tests and an inbound acceptance set |
| Phase 3, broader products | Cards and chargebacks, foreign exchange, multi-entity | A separate PRD per product, reusing the same journal and case model |

Phase 1 does not start Phase 2 work on the public API. Optional risk context for country and device is part of Phase 1 because fraud review needs it. Card fields, FX quotes, and inbound instruction types are not.

## Open product decisions

| Decision | Owner | Needed before |
| --- | --- | --- |
| Four-eyes amount threshold | Risk and product | MVP launch |
| Fraud review expiry | Risk | MVP launch |
| Review and decline score floors for the first policy version | Risk | First policy migration |
| Which synthetic channels may omit a device id | Product and fraud | Channel contract freeze |

Until those numbers are set, engineering wires the policy document and the workbench to read them. The PRD does not hard-code a threshold that would become an unreviewed control.
