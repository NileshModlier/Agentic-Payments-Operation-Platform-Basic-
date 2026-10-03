# Architecture pack

| | |
| --- | --- |
| System | Agentic Payment Operations Platform (APOP) |
| Status | Proposed reference architecture |
| Date | 2026-10-02 |
| Scope | Design only. No services, schemas, or deployable manifests are implemented yet. |

APOP is a simulated payment platform shaped like a production bank system. It processes outbound book transfers and simulated ACH and wire rails. It is a design for this project. It is not an official JPMorgan Chase system and it does not connect to real clearing houses, card networks, or customer accounts.

## How to read it

Engineers who are new to payments should read the [domain guide](11-payment-domain-guide.md) first. The design documents are the same system seen from one concern. Start with [High-level architecture](01-high-level-architecture.md) when you already know the vocabulary.

| # | Document | Question it answers |
| --- | --- | --- |
| 1 | [High-level architecture](01-high-level-architecture.md) | How does a payment move through the bank? |
| 2 | [Service boundaries](02-service-boundaries.md) | Which service owns which decision and which data? |
| 3 | [Kafka event design](03-kafka-event-design.md) | Which facts are published, and who consumes them? |
| 4 | [PostgreSQL schema design](04-postgresql-schema-design.md) | What is the book of record and the supporting stores? |
| 5 | [Agentic AI architecture](05-agentic-ai-architecture.md) | How do agents investigate without moving money? |
| 6 | [API architecture](06-api-architecture.md) | How do channels and services call the platform? |
| 7 | [Security architecture](07-security-architecture.md) | How are identity, data, and audit protected? |
| 8 | [Monitoring architecture](08-monitoring-architecture.md) | How do we know the platform is healthy? |
| 9 | [Deployment architecture](09-deployment-architecture.md) | How does it run on Kubernetes? |
| 10 | [Repository structure](10-repository-structure.md) | Where will the code live when implementation starts? |
| 11 | [Payment domain guide](11-payment-domain-guide.md) | What do the banking words mean, with examples? |
| 12 | [Failure scenarios](12-failure-scenarios.md) | What customers and operations see when a dependency fails? |
| 13 | [Investigation workbench](13-investigation-workbench.md) | Operator console, agents, and human approval? |
| 14 | [Event storming](14-event-storming.md) | Commands, events, aggregates, and policies across the lifecycle? |
| 15 | [Sequence diagrams](15-sequence-diagrams.md) | Step-by-step flows for success, decline, failure, duplicates, breaks, and investigation? |
| 16 | [SRE runbook](16-sre-runbook.md) | Severity, detection, and recovery for production incidents? |
| 17 | [Fraud detection](17-fraud-detection.md) | Rules, velocity, lists, geo and device risk, reviews, and agent support? |
| 18 | [Product requirements](18-product-requirements-document.md) | Goals, scope, user stories, and launch acceptance? |
| 19 | [Implementation roadmap](19-implementation-roadmap.md) | Single-engineer build order from contracts and ledger through agents? |
| 20 | [Interview guide](20-interview-guide.md) | How to explain the design in a payments interview? |

## Rules that every document follows

1. The ledger database is the book of record for balances and postings.
2. Kafka carries committed facts and asynchronous commands. A published event means the publisher's database transaction already committed.
3. Payment Initiation is the single writer of payment status.
4. The synchronous path ends at `AUTHORIZED`, `FRAUD_REVIEW`, or a terminal decline. Settlement, reconciliation, and investigation continue on the stream.
5. Every external write carries an idempotency key. Repeating the same key and body returns the original result.
6. Journal lines are immutable. Corrections are new reversing entries.
7. Agents investigate and recommend. A human decides exceptions. No agent posts to the ledger.
8. When fraud screening or sanctions screening is unavailable, the payment fails closed.
9. Services share published contracts. They do not share database tables.
10. OpenTelemetry context travels on every HTTP call and every Kafka record.

## Design assumptions

| Id | Assumption |
| --- | --- |
| A1 | Design load is 50 payment initiations per second sustained and 200 per second at peak. |
| A2 | The caller-visible decision budget is 800 ms at p99, ending at authorization, review, or decline. |
| A3 | Book-transfer settlement completes asynchronously and is alerted if it lags beyond 2 seconds at p99 after authorization. |
| A4 | Simulated ACH settles on the next business day. Simulated wire settles in the next same-day window before cutoff. |
| A5 | One legal entity is in scope. `legal_entity_id` is stored so a later entity split does not require a schema break. |
| A6 | Version 1 is outbound only. Inbound credits, cards, and standing orders are later products on the same ledger. |
| A7 | The bank timezone is configurable and defaults to `America/New_York`. Persisted instants are UTC. |
| A8 | Amounts are integer minor units plus an ISO 4217 currency code. Floating-point money is not used. |

## Glossary

| Term | Meaning |
| --- | --- |
| Payment | One outbound instruction and its lifecycle, identified by `payment_id`. |
| End-to-end id | Client reference, unique per debtor account, aligned with the ISO 20022 length of 35. |
| Rail | `BOOK`, `SIMULATED_ACH`, or `SIMULATED_WIRE`. |
| Hold | A reservation that reduces available balance and does not change ledger balance. |
| Posting | An immutable balanced journal entry that changes ledger balances. |
| Available balance | Ledger balance minus open holds, computed only inside the Ledger service. |
| Book of record | The Ledger PostgreSQL database. |
| Lifecycle spine | The `payments.payment.lifecycle.v1` topic. |
| Break | A reconciliation mismatch between an internal settlement item and a rail statement line. |
| Case | An investigation or failure record opened because a human or an operator workflow must decide. |
| Disposition | A structured recommendation from an agent. It becomes action only after a human decision. |
