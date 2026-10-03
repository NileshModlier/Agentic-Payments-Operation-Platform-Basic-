# 8. Monitoring architecture

| | |
| --- | --- |
| Status | Proposed |
| Audience | Site reliability, service owners, and operations |
| Related | [Deployment](09-deployment-architecture.md), [Events](03-kafka-event-design.md), [Agentic AI](05-agentic-ai-architecture.md) |

The platform emits traces, metrics, and logs that share one trace id. Prometheus stores metrics. Grafana displays them and fires alerts. OpenTelemetry SDKs in each Java service export to a collector, which routes metrics to Prometheus, traces to the trace store, and logs to the log store. Grafana is the single operator console for all three.

Tempo is the trace store and Loki is the log store in this design. They are the query engines behind Grafana. Prometheus remains the metrics store, which matches the operational model the platform is built around.

## Signals

| Signal | Source | Cardinality rule |
| --- | --- | --- |
| Metrics | Micrometer via the OpenTelemetry metrics SDK, plus collector scrapes of Kafka, PostgreSQL, and Redis exporters | Labels are service, operation, status class, rail, and reason code. No payment id, account id, or customer id. |
| Traces | OpenTelemetry Java agent and manual spans around ledger transactions and outbox relays | Span attributes are ids needed to debug one payment: `payment.id`, `end_to_end.id`. Sampling keeps those traces when the payment is slow or failed. |
| Logs | JSON to stdout, collected by the collector | Include `trace_id`, `span_id`, `payment_id`, `correlation_id`, and `reason_code`. Exclude remittance, names, and full account numbers. |

Sampling: 5 percent of successful authorization traces, 100 percent of traces that end in error, timeout, fraud review, or settlement failure. Tail sampling is done at the collector so the decision sees the whole trace.

W3C `traceparent` is injected at the gateway and copied onto Kafka headers, as specified in the event design. A payment's HTTP decision and its later settlement consume appear as linked traces. The shared `correlation_id` joins them even when they are separate traces.

## Service level objectives

Windows are rolling 30 days. Latency SLOs use successful and failed calls, because a timeout is a user-visible miss.

| SLO | Indicator | Objective |
| --- | --- | --- |
| Decision availability | Proportion of `POST /v1/payments` that return `201`, `202`, or a business `201` decline, excluding caller `400` and `409` | 99.9 percent |
| Decision latency | Server time of `POST /v1/payments` | 99 percent under 800 ms |
| Status read latency | `GET /v1/payments/{id}` | 99 percent under 200 ms |
| History freshness | Age of the latest unprojected lifecycle event | 99 percent under 2 seconds |
| Book settlement lag | Time from `AUTHORIZED` to `SETTLED` for `BOOK` | 99 percent under 2 seconds |
| Ledger durability | Successful posts acknowledged by the primary | 99.99 percent of accepted post calls |
| Investigation assist | Sessions that finish `COMPLETED` or `STOPPED` with the case still visible to an analyst | 99 percent. This SLO does not cover model quality. |

Business declines count as successful operation of the decision API. They are excluded from the latency error budget only when the response was sent inside the objective. Dependency failures that become `503` consume the availability budget.

## RED and USE metrics

Every HTTP server and client exports rate, errors, and duration. Every JVM exports CPU, heap, virtual-thread pinning where available, and garbage-collection pauses. PostgreSQL exports connection usage, transaction rate, deadlocks, and replication lag. Kafka exports broker under-replicated partitions, produce latency, and consumer lag per group. Redis exports memory, evictions, and hit ratio.

Names follow OpenTelemetry semantic conventions mapped to Prometheus via the collector. The stable set operators alert on:

| Metric | Meaning |
| --- | --- |
| `http.server.request.duration` | Gateway and service latency |
| `http.client.request.duration` | Validation, fraud, authorization, ledger, LLM |
| `messaging.process.duration` | Consumer handling time |
| `messaging.consumer.lag` | Records the consumer is behind, per group and topic |
| `db.client.operation.duration` | Database calls |
| `resilience4j.circuit.state` | `closed`, `open`, `half_open` per client |
| `resilience4j.call.duration` | Calls inside a decorator |
| `resilience4j.retry.calls` | Retries, tagged successful or failed |
| `resilience4j.bulkhead.available` | Remaining concurrent slots |

## Business metrics

| Metric | Labels |
| --- | --- |
| `apop.payment.created` | `rail` |
| `apop.payment.decision` | `rail`, `status`, `reason_code` |
| `apop.payment.settled` | `rail` |
| `apop.hold.open` | `rail` |
| `apop.ledger.post` | `entry_type`, `result` |
| `apop.recon.break.open` | `break_class`, `severity` |
| `apop.failure.case.open` | `case_type` |
| `apop.agent.session` | `role`, `status` |
| `apop.agent.recommendation` | `disposition` |
| `apop.agent.human_agreement` | `agreed` true or false |
| `apop.idempotency.replay` | `result` replay or conflict |

Amounts are not labels. A separate histogram `apop.payment.amount.minor` with the currency label is allowed only for the three supported currencies and uses coarse buckets so it cannot identify a payment.

## Dashboards

| Dashboard | Primary audience | Panels |
| --- | --- | --- |
| Payment funnel | Operations | Created, approved, in review, declined, settled, by rail. Decision latency. Fail-closed count. |
| Service RED | Service owner | Rate, error ratio, latency heatmap, saturation, top reason codes. |
| Ledger | Ledger owner | Post rate, hold count, lock wait, replication lag, idempotent replay rate. |
| Kafka | Platform | Consumer lag, dead-letter rate, under-replicated partitions, outbox unpublished age. |
| Resilience | Service owner | Circuit state, bulkhead saturation, retry rate, time-limiter expirations. |
| Reconciliation | Operations | Open breaks by class, age of oldest break, run success. |
| Investigation | Operations and risk | Queue depth, session outcomes, agreement rate, token use, tool refusals. |
| Platform | Site reliability | Node saturation, pod restarts, certificate expiry, backup age. |

Each dashboard links from a payment id search in logs and traces. Grafana's correlation uses `trace_id` and `payment_id`.

## Alerts

Alerts page on symptoms and SLO burn, not on every error log. Multi-window burn rates follow the standard fast and slow pair: a two-percent budget burn over one hour and five minutes for the page, and a ten-percent burn over six hours and thirty minutes for a ticket.

| Alert | Condition | Action |
| --- | --- | --- |
| Decision availability burn | Fast burn on the decision SLO | Page the payments primary |
| Decision latency burn | Fast burn on the 800 ms objective | Page the payments primary |
| Fraud circuit open | Circuit open for 2 minutes | Page. New payments are failing closed. |
| Ledger replication lag | Above 10 seconds for 5 minutes | Page the ledger owner |
| Ledger backup age | Last successful base backup older than 24 hours | Page |
| Consumer lag | History or initiation settlement-results lag above 10 seconds for 5 minutes | Page |
| Dead letters | Any new dead-letter record | Ticket, page if the rate exceeds 1 per minute |
| Outbox age | Oldest unpublished outbox row older than 30 seconds | Page the owning service |
| Break age | High-severity break open more than 4 business hours | Ticket to reconciliation |
| Agent agreement drop | Agreement rate under 50 percent over a day with at least 20 decisions | Ticket to the investigation owner |
| Certificate expiry | Mesh or ingress certificate inside 14 days | Ticket, page inside 3 days |
| Kafka under-replicated | Any under-replicated partition for 10 minutes | Page platform |

Runbooks live in the same approved corpus the agent can retrieve. Each alert links to a runbook id.

## Resilience4j and the golden signals

The decorator order on synchronous clients is bulkhead, then time limiter, then circuit breaker, then a single retry for idempotent transport failures. Metrics are tagged with the client name (`fraud`, `ledger`, `llm`). An open fraud or screening circuit is a business incident because the platform fails closed. An open LLM circuit is an operations incident for the queue and is not a payment-availability incident. The decision-availability SLO excludes agent failures by construction, because the agent is not on that path. Dashboards still show the LLM circuit so investigators know assistance is paused.

## Health

Each service exposes:

| Endpoint | Meaning |
| --- | --- |
| `/live` | Process is running. It does not check Kafka. |
| `/ready` | Database connections are usable and, for consumers, the group has joined. |
| `/health` | Detail for operators, not for the load balancer. Includes circuit state and outbox depth. |

The gateway readiness fails when it cannot validate tokens. A service that is alive and not ready is removed from the mesh endpoints and does not receive new work. In-flight ledger transactions finish or roll back with the pod's grace period, which deployment sets longer than the database lock timeout.

## Local diagnosis of one payment

An operator or an authorized support user starts from `payment_id`:

1. Status and step rows in Initiation show the single-writer view.
2. The trace, if retained, shows which dependency used the budget.
3. Logs with that `payment_id` show reason codes.
4. The lifecycle topic archive shows the committed transitions.
5. Ledger shows the hold and, when settled, the journal entry.

The investigation case is the analyst view of the same identifiers. It is not a second status authority.

## Retention of telemetry

| Store | Retention |
| --- | --- |
| Prometheus | 15 days local, long-term downsampled metrics for 13 months |
| Traces | 14 days |
| Logs | 30 days hot, 1 year archive for error and audit-class logs |
| Business audit | 7 years, in the audit archive, not in Loki |

Telemetry retention is shorter than financial retention on purpose. The ledger and the audit archive remain the record after traces expire.
