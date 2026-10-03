# 16. SRE runbook

| | |
| --- | --- |
| Status | Proposed |
| Audience | Primary on-call and service owners |
| Related | [Monitoring](08-monitoring-architecture.md), [Failure scenarios](12-failure-scenarios.md), [Deployment](09-deployment-architecture.md) |

This runbook is the production response for the Agentic Payment Operations Platform. The platform is a controlled simulation with production controls. Treat `prod` with the same discipline as a payments environment: one commander, recorded impact, and recovery that preserves a single financial effect per idempotency key.

Alerts, metric names, and dashboards are the ones in the monitoring architecture. Customer-facing detail for each failure shape is in the failure scenarios. This document is how on-call moves from a page to a recovered service.

## Severity

| Level | Definition | Response | Updates | Postmortem |
| --- | --- | --- | --- | --- |
| SEV-1 | New payments cannot be decided, settlement of authorized payments has stopped, or a duplicate or missing financial effect is possible | Page immediately. Incident commander within 5 minutes. | Every 15 minutes | Within 3 business days |
| SEV-2 | Decision or book-settlement SLO is burning, a dependency is degraded, or operators are blind while money is still moving | Page the primary. Commander if it exceeds 30 minutes. | Every 30 minutes | Within 5 business days |
| SEV-3 | Analyst or investigation workflow is down. Payment decisions and settlement continue. | Ticket the owning queue. Page only if it blocks a SEV-1 already in progress. | At handover | On request of the owner |
| SEV-4 | Single-pod noise, a certificate outside the page window, or a dashboard gap with a second signal still healthy | Next business day | None | None |

Raise severity when customer impact or money correctness is worse than the first report. Lower it only when the exit criteria for that incident are true. A fraud circuit that fail-closes creates is SEV-1, even though the ledger is healthy, because customers cannot pay.

| Incident | Default severity | Raises to SEV-1 when |
| --- | --- | --- |
| Service outage | By service, below | The decision path or the ledger write path is down |
| Kafka lag | SEV-2 | Settlement commands are not consumed, or outbox age means facts are not leaving PostgreSQL |
| Database failover | SEV-1 while the ledger primary is unavailable | Already SEV-1. Platform-cluster failover alone is SEV-2 if ledger writes still commit. |
| Redis failure | SEV-1 | Creates are rejected at the gateway |
| Fraud service failure | SEV-1 | The fraud circuit is open or assessments are timing out |
| OpenTelemetry failure | SEV-3 | Metrics used for paging have stopped for 15 minutes |
| Agent service failure | SEV-3 | It does not raise payment severity. Payments do not wait on the agent. |

## Roles and standards

| Role | Duty |
| --- | --- |
| Incident commander | One person. Assigns investigation, owns severity, owns the clock. |
| Service owner | The on-call for the failing deployable or data plane. |
| Communications | Writes the impact statement. Keeps raw account numbers and remittance out of the channel. |
| Scribe | Timeline of facts: what changed, what was observed, which payment ids were sampled. |

Every SEV-1 and SEV-2 starts with an impact statement:

| Field | Content |
| --- | --- |
| Start time | First page or first customer symptom, in UTC |
| Customer effect | Creates accepted, creates fail-closed, or settlement delayed |
| Money effect | Holds open, posts stopped, or one-post-per-key still holding |
| Workaround | Poll status, or wait. Do not ask clients to send a new idempotency key for the same invoice. |

Production rules for every recovery:

| Rule | Practice |
| --- | --- |
| One writer of money | Ledger remains the only service that posts. Recovery retries the original ledger key. |
| One writer of status | Payment Initiation moves status when a fact arrives. On-call does not update status in SQL. |
| Roll back by digest | A bad release returns to the previous image digest through GitOps. |
| Failover by the operator | CloudNativePG promotes a synchronous replica. A restore is a new cluster cut over by GitOps. |
| Offsets stay put | Consumer groups are not seeked to latest or earliest during an incident. Latest skips settlement. Earliest replays the retention window. |
| Evidence | Snapshot the dashboard and the alert. Sample payment ids. Leave customer free text out of the ticket. |
| Change control | During SEV-1, the only production change is the one the commander has named. |

Exit criteria are specific to each incident. Closing the incident means those criteria are true, not only that the pods are green.

Shared postmortem questions, answered in addition to the checklist on the incident:

| Question | Why it is in every review |
| --- | --- |
| What did the customer experience, and for how long? | Ties the incident to the decision and settlement SLOs. |
| Did any payment get two holds or two journal entries? | Confirms idempotency under retry. |
| Which alert fired, and how long after impact? | Tests the page, not only the repair. |
| What will detect this faster? | One action item with an owner and a date. |

## RB-1. Service outages

A service outage is a deployable that is not ready, crash-looping, or failing its dependency checks. Use this section to classify it, then follow the specific runbook when the failing service is Kafka, PostgreSQL, Redis, fraud, telemetry, or the agent.

### Severity

| Service down | Severity | Why |
| --- | --- | --- |
| API Gateway, Payment Initiation, Fraud Detection, Ledger application or ledger primary | SEV-1 | Creates fail closed or holds and posts cannot commit |
| Payment Authorization, Payment Settlement | SEV-1 if it lasts past a few minutes | Authorized payments stop moving, or holds cannot be placed |
| Payment Validation | SEV-1 | Fail closed before fraud and before any hold |
| Transaction History, Reconciliation | SEV-2 | Decisions continue. Analysts and the channel timeline lag. |
| Failure Management | SEV-2 | In-place retries pause. New decisions still commit. |
| Investigation Agent | SEV-3 | See RB-7. |

### Symptoms

| Signal | What operators see |
| --- | --- |
| Ready pods | Desired replicas greater than ready replicas for 2 minutes |
| Gateway errors | `503` or `504` on `POST /v1/payments`, or `202` responses that stay on `RECEIVED` |
| Funnel | `apop.payment.decision` drops, or `reason_code` shifts to `TECHNICAL_DEPENDENCY` or `TECHNICAL_TIMEOUT` |
| Holds | `apop.hold.open` stops increasing while creates are still accepted, or increases with no matching `apop.payment.settled` |

### Metrics

`http.server.request.duration` and error ratio on the failing service. `http.client.request.duration` on callers. `resilience4j.circuit.state` for the client name of the down dependency. Restart count and readiness from the platform dashboard. Pod CPU and memory against the requests in the deployment architecture.

### Logs

Structured application logs for one `payment_id` and the matching `trace_id`. Readiness failure text from the pod. Lines that contain `UNCERTAIN` on `orchestrator_step`. Leave remittance and full account numbers out of the incident channel. A useful line has service name, `reason_code`, and dependency name.

### Dashboards

Platform for restarts and saturation. Service RED for the failing service and for Payment Initiation. Payment funnel for customer impact. Resilience if a circuit is open. Ledger if the outage is on the write path.

### Investigation process

1. Name the service from the alert and confirm namespace: `edge`, `payments`, `risk`, `ledger`, `operations`, or `data`.
2. Record severity from the table above and post the impact statement.
3. Check whether a deploy, config, or node event started in the last 30 minutes.
4. Read `/ready` and `/health` from a ready pod if one remains, and from a failing pod's last logs if none remain. `/health` shows circuit state and outbox depth.
5. Sample three payment ids created at the start of impact. For each, record status, `orchestrator_step`, hold count, and journal count.
6. If the failure is Kafka, PostgreSQL, Redis, fraud, telemetry, or the agent, continue in that runbook. Otherwise stay here.

### Recovery procedure

1. If the last change was a release, roll back to the previous image digest through GitOps. Wait until ready replicas match desired.
2. If the pods are crash-looping on config, revert the config commit. A threshold change for fraud or limits is a policy rollback, reviewed as a risk-control change, not a live edit.
3. If the node is unhealthy, let the pods reschedule. Disruption budgets keep gateway, initiation, and ledger capacity during voluntary drain.
4. After ready, send one synthetic book payment in `uat` first when the outage is rehearsed there. In `prod`, sample a new real create only if the environment is the controlled simulation and the commander has approved a test payment.
5. For payments left `FAILED` or `UNCERTAIN`, let Failure Management retry the original key. Confirm one hold and, if settled, one journal entry.

Exit criteria: ready replicas hold for 10 minutes, decision error ratio has returned under the burn threshold, and the three sample payments have a single financial outcome.

### Postmortem checklist

| Item | Done |
| --- | --- |
| Shared postmortem questions answered | |
| Customer minutes of fail-closed or delayed settlement counted from the funnel | |
| Rollback digest recorded | |
| Sample payments checked for a single hold and a single post | |
| Readiness probe gap fixed if the pod was Running and not Ready without a page | |
| Runbook link on the alert verified | |

## RB-2. Kafka lag

Lag means a consumer group is behind the log end. Outbox age means a committed fact has not been acknowledged by the broker. They are different failures and they are both in this section.

### Severity

SEV-2 when history lag or `payment-initiation-settlement-results` lag stays above 10 seconds for 5 minutes. SEV-1 when `payment-settlement-executor` is stalled, outbox age is above 30 seconds and climbing, or under-replicated partitions last 10 minutes. Dead letters page at more than one record a minute and are SEV-2 until money is at risk, then SEV-1.

### Symptoms

| Signal | Meaning |
| --- | --- |
| Book payments stay `SETTLEMENT_PENDING` | Settlement commands are not being applied |
| Timeline behind the dossier status | History projector lag. The payment itself may be fine. |
| Outbox age page | Broker did not acknowledge publishes. Creates may still return `201`. |
| Under-replicated page | A broker is out. Produce latency climbs. |
| Dead-letter rate | One poison record, or a bad deploy of a projector |

### Metrics

`messaging.consumer.lag` by group and topic. Produce latency and under-replicated partition count on the Kafka dashboard. Outbox unpublished age. `apop.payment.settled` against `apop.payment.decision` for status `AUTHORIZED`. Dead-letter records per minute. `messaging.process.duration` to see a slow handler rather than a stuck one.

Groups that matter first: `payment-settlement-executor`, `payment-initiation-settlement-results`, `transaction-history-projector`, `ledger-compensation`, `investigation-intake`.

### Logs

Consumer errors that include topic, partition, and event id. Relay errors that include `outbox_id` and a broker acknowledgement failure. Dead-letter reason codes. Correlate with `correlation-id` from the Kafka header. Do not paste payload bodies into chat. They can contain account identifiers even when remittance is excluded.

### Dashboards

Kafka dashboard first. Payment funnel second, to see whether lag is customer-visible settlement delay or only history delay. Service RED for the lagging consumer's deployable. Platform if a data-pool node is under pressure.

### Investigation process

1. Record the group, topic, partition, and lag slope. Lag that is flat and high is a stopped consumer. Lag that grows then shrinks is catch-up.
2. Separate broker trouble from consumer trouble. Under-replicated partitions and produce errors mean the cluster. A single group lagging while produces succeed means that group.
3. Check ready replicas of the consumer deployment and whether a deploy landed.
4. If dead letters are increasing, read one event id and the failure class. A poison payload is not fixed by adding replicas.
5. Sample one `payment_id` on the lagged topic. Confirm whether the ledger already has the hold or the journal. Status in Initiation may still say `SETTLEMENT_PENDING` when the journal already exists, if initiation's own consumer is the one behind.

### Recovery procedure

1. Broker down: restore the broker through Strimzi. Producers stay on `acks=all`. Wait for under-replicated partitions to clear. The outbox relay publishes unpublished rows in order when acknowledgements return.
2. Consumer stopped: restore ready replicas. Scale the deployment up to the partition count of that topic when the handler is healthy and CPU-bound. More replicas than partitions do not add consumption.
3. Poison record: the consumer dead-letters after its bounded retries and continues the partition. Leave the offset committed past that record. Replay only after a fix, as a new event id with `causation-id` set to the dead-lettered id, through Failure Management. A blind replay of the original id is a no-op.
4. Leave group offsets where they are. Settlement catch-up will post with the original ledger keys.
5. Confirm book-settlement lag returns under 2 seconds at p99 and history lag under 2 seconds at p99.

Exit criteria: lag for the paged groups is under the page threshold for 15 minutes, outbox age is under 30 seconds, and a sampled payment that was stuck in `SETTLEMENT_PENDING` has one journal entry and status `SETTLED` or a recorded business failure.

### Postmortem checklist

| Item | Done |
| --- | --- |
| Shared postmortem questions answered | |
| Lag versus outbox age called correctly in the timeline | |
| No offset seek performed, or any seek recorded with commander approval and a reason | |
| Dead-letter event ids listed and either replayed safely or still open as failure cases | |
| Partition count versus consumer replicas reviewed if lag was throughput | |
| Customer-visible settlement delay measured against the 2 second book objective | |

## RB-3. Database failover

Two clusters fail over independently. `ledger-pg` is one primary and two synchronous replicas. `platform-pg` holds every other service database. Ledger authorization and posting use the primary.

### Severity

SEV-1 from the moment the ledger primary stops accepting commits until the promoted primary serves holds and posts and sample keys show one financial effect. SEV-2 when only `platform-pg` is failing over and ledger commits are succeeding. Replication lag above 10 seconds for 5 minutes pages the ledger owner before a full loss.

### Symptoms

| Signal | Meaning |
| --- | --- |
| `503` `ledger-unavailable` or `504` on creates | Callers cannot finish a hold or a post |
| Authorization steps `UNCERTAIN` | The call died after it might have committed |
| Ledger replication lag alert | The replica is behind. Promotion would lose the lag window if it were not synchronous. |
| Read errors on `GET /v1/payments/{id}` | Platform primary trouble. Settled payments are intact once promotion finishes. |
| Idempotent replay rate climbs after recovery | Callers are retrying keys. This is the recovery working. |

### Metrics

`db.client.operation.duration` for Ledger and Initiation. Replication lag and connection errors on the ledger dashboard. `apop.ledger.post` result counts. `apop.idempotency.replay`. `apop.hold.open`. CloudNativePG primary changes and replica sync state on the platform dashboard. Backup age. A backup older than 24 hours is its own page and stays open until a base backup succeeds.

### Logs

Connection reset and failover messages from the database operator. Application errors that name the ledger idempotency key and the SQL state. `UNCERTAIN` step logs with `payment_id`. Keep journal amounts in the operator timeline. Keep account numbers out.

### Dashboards

Ledger dashboard for primary identity, lag, lock waits, and replay rate. Service RED for Authorization, Settlement, and Initiation. Payment funnel for fail-closed volume. Platform for operator pod health.

### Investigation process

1. Identify which cluster lost its primary: `ledger-pg` or `platform-pg`.
2. Declare SEV-1 for ledger primary loss before deep investigation.
3. Confirm synchronous replica state at the moment of loss. Synchronous commit means a committed hold or post is on a survivor.
4. Sample in-flight payments. For each, record whether `balance_hold` and `journal_entry` exist for the ledger key.
5. Watch that only one promotion is in progress. A second promotion during the first splits the picture.

### Recovery procedure

1. Allow CloudNativePG to promote a synchronous replica. Point applications at the cluster service, which follows the primary. Do not pin pods at a named instance.
2. Keep hold and post retries on the original ledger idempotency key. The promoted primary returns the committed row or creates the single missing row.
3. Leave customer balances as the journal defines them. A wrong balance is a new reversing entry after a human decision, which is a case workflow, not an incident SQL update.
4. For platform-cluster failover, expect Initiation replays to return the same `payment_id` when the row committed.
5. After the new primary is steady, check replication of the remaining replica, lock waits, and a balanced sample entry: debit minor units equal credit minor units.
6. If the primary is corrupt rather than merely lost, restore onto a new cluster from the backup and WAL archive and cut over by GitOps. That is a commander decision. Rehearse it in `uat`, not as a first attempt on the live primary.

Exit criteria: a primary is serving, replication lag is under 10 seconds, new holds and posts succeed, and every sampled in-flight key has exactly one hold or one journal entry.

### Postmortem checklist

| Item | Done |
| --- | --- |
| Shared postmortem questions answered | |
| Which cluster failed, and the promotion time, recorded | |
| In-flight keys sampled and duplicates ruled out | |
| Backup age at the time of failure recorded | |
| Decision made on whether `uat` failover rehearsal was current | |
| No manual balance update performed | |

## RB-4. Redis failure

Redis holds rate limits, fraud velocity, a hot idempotency pointer, and short-lived reference cache. PostgreSQL holds the idempotency record and the payment. Losing Redis rejects new creates and fail-closes fraud scoring. It does not delete payments or balances.

### Severity

SEV-1 when `POST /v1/payments` is fail-closed because the gateway cannot use Redis, or when in-flight fraud assessments error because velocity cannot be read. SEV-2 when Redis is degraded, hit ratio has collapsed, and the circuit is not yet open.

### Symptoms

| Signal | Meaning |
| --- | --- |
| Gateway `503` on create, `GET` still succeeds | Rate-limit store is down. The gateway applies a small local limit on reads. |
| `TECHNICAL_DEPENDENCY` after a payment row exists | The create passed the gateway and fraud could not score |
| Velocity warmup | After Redis returns, approvals stay fail-closed until the warmup flag is set. A cold cache is not zero velocity. |
| Idempotency still holds | Replays resolve in PostgreSQL |

### Metrics

Redis memory, evictions, connected clients, and command errors on the platform dashboard. Gateway `http.server.request.duration` and 503 ratio on the write route. `resilience4j.circuit.state` for the `fraud` client. `apop.payment.decision` with technical reason codes. Decision-availability burn.

### Logs

Gateway connection errors to Redis with correlation id and no payment id. Fraud logs that name a velocity read timeout and a `payment_id`. Initiation logs for step `FRAUD` in `UNCERTAIN` or `FAILED`. Confirm the log line does not contain the raw account number.

### Dashboards

Platform for Redis. Payment funnel for fail-closed creates. Resilience for the fraud circuit. Service RED for the gateway and Fraud Detection.

### Investigation process

1. Confirm Redis is the failing dependency and not the fraud process itself. Both can fail-close creates. Fraud pods that are Ready and Redis that is not reachable is this runbook.
2. Check whether a flush, eviction storm, or node loss started the incident.
3. Separate gateway rejects, which have no payment id, from payments already stored.
4. For stored payments, confirm Ledger has no hold when the step failed at fraud.
5. Confirm PostgreSQL idempotency rows still deduplicate a retry.

### Recovery procedure

1. Restore the Redis deployment in `data`: TLS, authentication, and persistence as deployed. A restart that loads the persistence file keeps recent windows.
2. If the dataset is empty after restore, leave fraud fail-closed until velocity warmup completes. Approving on empty counters would treat a quiet cache as a quiet customer.
3. Gateway writes resume when the rate-limit commands succeed and the circuit closes.
4. Failure Management retries uncertain fraud steps with the same payment key. One assessment row per payment is the expected result.
5. Watch `apop.payment.decision` return to business outcomes (`AUTHORIZED`, declines, review) rather than `TECHNICAL_DEPENDENCY`.

Exit criteria: creates succeed for 10 minutes, the fraud circuit is closed, warmup is complete, and a retried payment has at most one assessment and one hold.

### Postmortem checklist

| Item | Done |
| --- | --- |
| Shared postmortem questions answered | |
| Gateway rejects versus in-flight fraud failures counted separately | |
| Empty-cache warmup held before approvals resumed | |
| No duplicate idempotency rows created | |
| Persistence and memory headroom reviewed | |
| Customer copy verified as "not accepted, no funds reserved" for pure gateway rejects | |

## RB-5. Fraud service failure

Fraud Detection times out, crash-loops, or returns errors. Initiation fail-closes. No hold is placed. A timeout is stored as a technical failure, not as `FRAUD_DECLINED`.

### Severity

SEV-1 when the fraud circuit has been open for 2 minutes or decision availability is in fast burn because assessments are failing. SEV-2 when latency is burning the 800 ms objective and the circuit is still closed.

### Symptoms

| Signal | Meaning |
| --- | --- |
| Fraud circuit open page | New payments fail fast at initiation |
| `503` `risk-control-unavailable` | The create was refused as a technical risk-control outage |
| Status `FAILED`, reason `TECHNICAL_TIMEOUT` or `TECHNICAL_DEPENDENCY` | A payment row exists and authorization did not run |
| `201` `FRAUD_DECLINED` | A real decision. This runbook does not "fix" those. |
| Queue of `FRAUD_REVIEW` unchanged | Reviews already open still belong to analysts |

### Metrics

`resilience4j.circuit.state` and time-limiter expirations for client `fraud`. `http.client.request.duration` from Initiation to Fraud. Fraud service RED: error ratio, saturation, restarts. `apop.payment.decision` by `reason_code`. `apop.hold.open` should not rise for payments that failed at this step. Decision-availability and decision-latency burn.

### Logs

Fraud client spans ending in time-limiter timeout. Absence of an authorization span on those traces. `orchestrator_step` logs for `FRAUD` with status `UNCERTAIN`. One `payment_id` followed from gateway to fraud. Feature snapshots stay in the fraud database. They are not pasted into the incident channel.

### Dashboards

Resilience and Fraud service RED first. Payment funnel for the volume fail-closed. Investigation dashboard only to show that analyst queues are still usable. Ledger dashboard to prove holds were not created for the failed creates.

### Investigation process

1. Confirm the circuit and the reason: deploy, saturation, Redis velocity, or a downstream screening-style dependency inside fraud. Redis itself is RB-4.
2. Declare SEV-1 when creates are fail-closed.
3. Sample payments. Expected pattern: step `FRAUD` not succeeded, no `AUTHORIZE` step, no hold, no journal.
4. Check the last fraud policy version deployed. A bad policy is rolled back as a reviewed config change. An incident is not the time to loosen a threshold to force approvals.
5. Distinguish `FRAUD_DECLINED` business results from technical failures so communications do not tell a customer that a real decline was an outage.

### Recovery procedure

1. Restore Fraud Detection replicas in `risk`, or roll back the digest that started the errors.
2. Keep the initiation client fail-closed while the circuit is open. Half-open probes should be few and should not authorize on an error.
3. When the circuit closes, Failure Management retries `UNCERTAIN` fraud steps with the same key. The replay returns the original assessment if one committed, or creates one assessment.
4. Authorization runs only after a stored `APPROVE`.
5. Watch hold creation resume only for new approvals, and watch technical reason codes fall.

Exit criteria: fraud circuit closed for 10 minutes, decision availability no longer in fast burn, sample technical failures either retried to a real decision or still visibly `FAILED` with no hold.

### Postmortem checklist

| Item | Done |
| --- | --- |
| Shared postmortem questions answered | |
| Technical failures separated from business declines in the impact count | |
| No threshold was weakened during the incident | |
| Sample set has zero holds on fail-closed payments | |
| Time-limiter and circuit settings reviewed against the 800 ms budget | |
| Alert "Fraud circuit open" time compared with the first customer `503` | |

## RB-6. OpenTelemetry failure

Services export traces, metrics, and logs to the OpenTelemetry Collector in `observability`. The collector feeds Prometheus, Tempo, and Loki. Payment commits do not depend on the collector. A collector outage makes the platform harder to see. It does not, by itself, decline a payment.

Pod stdout still exists when Loki is behind. Use it while the pipeline is down.

### Severity

SEV-3 when traces or logs are missing and Prometheus is still scraping. SEV-2 when the metrics path through the collector has been stale for 15 minutes, because SLO burn alerts may be blind. SEV-1 only when a payment incident is already SEV-1 and telemetry loss blocks the diagnosis. Fixing telemetry does not outrank restoring the decision path.

### Symptoms

| Signal | Meaning |
| --- | --- |
| Grafana panels flat or gap | Collector or Prometheus remote write has stopped |
| Tempo returns no trace for a new `trace_id` | Span export is down. The payment may still have a status. |
| Loki query empty while the pod logged | Log pipeline stopped. `kubectl` logs remain the source. |
| Payment funnel unchanged in the product | Customers are unaffected |
| Collector not Ready | Export is the failing component |

### Metrics

Collector exporter failure counts, queue size, and refused spans. Prometheus target health and the age of the latest sample for `http.server.request.duration` and `apop.payment.decision`. Collector CPU and memory in `observability`. A healthy payment service still moves `apop.payment.created` once metrics return. Until then, pod access logs and database activity are the substitutes.

### Logs

Collector logs for exporter errors, authentication to the backend, and dropped batches. Application pods continue to emit JSON with `trace_id` and `payment_id` on stdout. Search recent pod logs by `payment_id` while Loki is empty. Do not raise application log level to `DEBUG` across the mesh during a SEV-1. That load can become a second incident.

### Dashboards

Platform dashboard for the collector and the observability node pool. A known-good service RED panel to see whether data stopped for one service or for the estate. Payment funnel, once metrics resume, to confirm the gap was telemetry and not a quiet failure of creates.

### Investigation process

1. Prove whether payments are healthy before spending the incident on telemetry. One `GET` of a recent payment and a look at pod restarts answer that.
2. If payments are unhealthy, open the matching runbook and treat telemetry as a parallel workstream.
3. If payments are healthy, set SEV-2 or SEV-3 from the table and assign the observability owner.
4. Check collector readiness, exporter errors, and disk or memory on the observability pool.
5. Note the start of the gap so the postmortem can mark which minutes have no traces.

### Recovery procedure

1. Restore the collector through GitOps, or roll the collector deployment forward to a Ready revision. Keep payment service digests unchanged.
2. Confirm Prometheus is receiving fresh samples and Tempo accepts a new trace from a low-risk read, such as a health read the commander approves.
3. Accept the gap. Spans from the outage window are gone. The ledger, payment rows, and audit archive are the record for those minutes.
4. Leave sampling configuration as deployed. Raising tail sampling to 100 percent during the recovery can overload Tempo and extend the outage.
5. Return log level and any temporary diagnostic flag to the committed values before closing.

Exit criteria: fresh metrics for 15 minutes, a new trace is retrievable, Loki shows new lines, and no payment digest was changed as part of this recovery.

### Postmortem checklist

| Item | Done |
| --- | --- |
| Shared postmortem questions answered | |
| Payment impact stated as none, or the parallel incident linked | |
| Gap window in UTC recorded so investigators do not treat missing traces as missing payments | |
| Collector capacity and exporter errors captured | |
| Recovery did not include a payment-service change | |
| Paging path tested if metrics blindness lasted long enough to hide a burn alert | |

## RB-7. Agent service failure

Investigation Agent and the LLM gateway are off the payment hot path. Fraud reviews, breaks, and escalations stay in the analyst queue. Payments already `AUTHORIZED` continue to settle. The kill switch `investigation.agent.enabled` stops new sessions. Intake still opens cases.

### Severity

SEV-3 while analysts can read cases and record decisions. SEV-2 when the case API itself is down and analysts cannot decide, because fraud reviews then wait with no hold and the queue ages. It does not become a payment-decision SEV-1. An open LLM circuit is an investigation incident.

### Symptoms

| Signal | Meaning |
| --- | --- |
| Assist returns an error, or the workbench shows assistance paused | Runner or LLM gateway is unavailable |
| `apop.agent.session` status `FAILED` climbs | Sessions are dying. Cases should still be `AWAITING_HUMAN` or `OPEN`. |
| Fraud review age grows | Humans are not releasing or declining. That is a queue problem, not an authorization outage. |
| `AUTHORIZED` and `SETTLED` continue | The hot path is independent |
| Agreement-rate ticket | Model quality, not an outage. Handle outside this recovery unless a bad model is actively drafting. |

### Metrics

`resilience4j.circuit.state` for client `llm`. `apop.agent.session` by role and status. Tool refusal count and policy-guard failures. Investigation queue depth and age on the investigation dashboard. `http.server.request.duration` on the case API. Confirm `apop.payment.decision` is unaffected before paging the payments primary.

### Logs

Investigation service errors that include `case_id` and `session_id`. LLM client timeouts. Policy-guard rejection reason without the rejected narrative. Workbench audit events for assist denied by the kill switch. Feature snapshots and remittance stay out of the incident channel.

### Dashboards

Investigation dashboard for queue age, session outcomes, and the LLM circuit. Resilience filtered to `llm`. Payment funnel as a negative check: it should look ordinary. If it does not, this is not the primary incident.

### Investigation process

1. Confirm payment creates and settlement on the funnel. State that customers can still pay when that is true.
2. See whether the case API is up. If analysts can decide, severity stays SEV-3 and the agent is an aid that is paused.
3. If the case API is down, raise to SEV-2 and restore the investigation deployable first. Analyst decisions are the control. The model is not.
4. Check the kill switch and the LLM circuit. A switch that was turned off on purpose is a change to record, not a mystery outage.
5. Sample one `FRAUD_REVIEW` payment and show it has no hold.

### Recovery procedure

1. Restore Investigation Agent replicas in `operations` if the process is down. Roll back the digest if a deploy broke the case API.
2. When the LLM gateway is the failure, leave the circuit open and leave `investigation.agent.enabled` off until the gateway is healthy. Analysts decide from the assessment and the timeline.
3. Do not auto-accept recommendations that were in flight. A session that `FAILED` shows no advice. A session that completed still waits for a human.
4. Discard partial sessions. A new assist after recovery is a new session. The runner does not retry a half-finished tool list automatically.
5. Re-enable the agent only when a probe session completes and the policy guard is enforcing the tool allow-list. The payments primary does not need to be in that check unless the case API was also down.

Exit criteria: analysts can open a case and record a decision, `FRAUD_REVIEW` payments still have no hold until that decision, and either assists complete or the kill switch is intentionally off and communicated.

### Postmortem checklist

| Item | Done |
| --- | --- |
| Shared postmortem questions answered | |
| Payment path confirmed independent, with the funnel evidence linked | |
| Any human decision during the outage attributed to a person, not a session | |
| Kill-switch state over time recorded | |
| No recommendation was executed without `human_decision` | |
| Queue age at start and end recorded for fraud review and breaks | |

## Handover and close

| Step | Action |
| --- | --- |
| Active incident | Commander posts severity, impact, current owner, and next update time |
| Shift change | The new commander restates the impact in their own words and accepts it |
| Close | Exit criteria checked, customer statement updated, follow-up cases listed by id |
| After close | Postmortem owner and date set for SEV-1 and SEV-2 |

Failure cases and reconciliation breaks opened by the incident stay in their queues. Closing the incident does not close those cases. They finish through the workbench, with a human on any money movement.
