# 6. API architecture

| | |
| --- | --- |
| Status | Proposed |
| Audience | Channel integrators and service owners |
| Related | [Service boundaries](02-service-boundaries.md), [Security](07-security-architecture.md), [Events](03-kafka-event-design.md) |

External APIs are versioned HTTP resources for channels, analysts, and auditors. Internal APIs are cluster-only HTTP resources used on the synchronous path and for bounded reads. Kafka commands cover settlement and compensation, which must survive restarts and are specified in the [event design](03-kafka-event-design.md).

Contracts will be OpenAPI 3.1 for HTTP and AsyncAPI for topics. This document is the source those contracts will be written from.

## Principles

| Principle | Rule |
| --- | --- |
| Resource orientation | Clients address payments, accounts, cases, and breaks. They do not address services. |
| One writer | Payment status changes only through Payment Initiation. |
| Idempotent writes | `POST /v1/payments` requires `Idempotency-Key`. Retries of the same key and body return the original response. |
| Bounded wait | The create call waits up to the decision budget and then returns the last committed status. |
| Problem details | Errors use `application/problem+json` (RFC 9457). |
| Cursor pages | List endpoints use `cursor` and `limit`. `limit` defaults to 50 and caps at 100. |
| Minor units | Amounts are integers plus an ISO 4217 currency. |
| Correlation | `X-Correlation-Id` is accepted or minted at the gateway and returned on every response. |

## Identity on the call

External callers present a bearer token and mutual TLS. The gateway validates both and forwards a service call that carries:

| Header | Source |
| --- | --- |
| `Authorization` | Internal service token for the hop from gateway to service, audience-restricted |
| `X-Actor-Id` | Subject from the external token |
| `X-Actor-Type` | `USER` or `CLIENT` |
| `X-Legal-Entity-Id` | From the token |
| `X-Correlation-Id` | From the caller or minted |
| `traceparent` | Injected at the gateway |
| `Idempotency-Key` | Required on external creates |

Downstream services on the synchronous path trust the mesh identity of Payment Initiation. They do not accept an end-user token forwarded from the channel. The original actor is already stored on the payment row. This keeps ledger and fraud calls independent of channel token lifetime.

## External surface

Base path `/v1`. JSON. UTF-8.

| Method and path | Caller role | Owner | Purpose |
| --- | --- | --- | --- |
| `POST /v1/payments` | `payments.write` | Initiation | Submit an outbound payment |
| `GET /v1/payments/{paymentId}` | `payments.read` | Initiation | Read status and identifiers |
| `GET /v1/payments` | `payments.read` | Initiation | List recent payments for an account the caller may see |
| `POST /v1/payments/{paymentId}/cancellations` | `payments.write` | Initiation | Cancel before settlement lock |
| `GET /v1/accounts/{accountId}/activity` | `payments.read` | History | Posted and hold activity |
| `GET /v1/payments/{paymentId}/timeline` | `payments.read` | History | Projection timeline |
| `GET /v1/reconciliation/breaks` | `payments.recon.read` | Reconciliation | Open breaks |
| `GET /v1/cases` | `payments.case.read` | Investigation | Queue |
| `GET /v1/cases/{caseId}` | `payments.case.read` | Investigation | Case, recommendation, evidence index |
| `POST /v1/cases/{caseId}/assist` | `payments.case.read` | Investigation | Request an agent session |
| `POST /v1/cases/{caseId}/decisions` | `payments.case.decide` | Investigation | Record the human disposition |
| `GET /v1/failure-cases` | `payments.ops.read` | Failure Management | Technical cases |
| `GET /v1/audit/payments/{paymentId}` | `payments.audit.read` | Audit archive API | Joined audit index for one payment |

The audit read is a dedicated read API over the archive index, served by a small audit-query process in the operations namespace. Domain services do not expose their audit tables directly.

### Create payment

`POST /v1/payments`

```json
{
  "endToEndId": "E2E-20261002-0001",
  "rail": "BOOK",
  "amount": { "minor": 125000, "currency": "USD" },
  "debtorAccountId": "018f1b2c-7c3a-7a21-8c11-1a2b3c4d5e6f",
  "creditor": { "accountId": "018f1b2c-7c3a-7a21-8c11-1a2b3c4d5e70" },
  "remittance": "Invoice 4401",
  "requestedValueDate": "2026-10-02"
}
```

External creditor, used for `SIMULATED_ACH` and `SIMULATED_WIRE`:

```json
{
  "creditor": {
    "name": "Northwind Supplies",
    "accountNumber": "000123456789",
    "routingNumber": "021000021",
    "country": "US"
  }
}
```

Response `201` when a terminal or stable decision was reached inside the budget (`AUTHORIZED`, `SETTLEMENT_PENDING`, `VALIDATION_FAILED`, `FRAUD_DECLINED`, `AUTHORIZATION_DECLINED`, `FRAUD_REVIEW`). Response `202` when the budget expired and processing continues. Both responses use the same body so clients handle one schema.

```json
{
  "paymentId": "018f1c40-1111-7a21-8c11-1a2b3c4d5e6f",
  "endToEndId": "E2E-20261002-0001",
  "status": "AUTHORIZED",
  "rail": "BOOK",
  "amount": { "minor": 125000, "currency": "USD" },
  "valueDate": "2026-10-02",
  "reasonCode": null,
  "correlationId": "018f1c40-2222-7a21-8c11-1a2b3c4d5e6f"
}
```

`Location` is `/v1/payments/{paymentId}`.

Idempotency behavior:

| Situation | Result |
| --- | --- |
| New key | Create the payment and store the request hash |
| Same key, same hash, still in progress | Return the current body with the current status |
| Same key, same hash, completed | Return the original decision body |
| Same key, different hash | `409` problem `idempotency-conflict` |
| Missing key | `400` problem `idempotency-key-required` |

Keys expire after 24 hours. After expiry the same key may be reused and is treated as new. Clients keep keys at least as long as they retry.

### Read and list

`GET /v1/payments/{paymentId}` returns the same resource plus `holdId` and `journalEntryId` when they exist, and `updatedAt`.

`GET /v1/payments?debtorAccountId={id}&cursor={c}&limit={n}` returns payments for one debtor account, newest first. The caller must be allowed to see that account. The response includes `nextCursor` when more rows exist.

### Cancel

`POST /v1/payments/{paymentId}/cancellations` with body `{ "reason": "CUSTOMER_REQUEST" }`.

Accepted only from `AUTHORIZED` or `SETTLEMENT_PENDING` while the settlement item is not in a locked batch. Initiation calls Authorization, which releases the hold through Ledger. Success returns the payment in `CANCELLED`. If that call is uncertain, the status stays unchanged and Failure Management publishes `RELEASE_HOLD`. Initiation moves the payment to `CANCELLED` when it observes the release. If the batch is already locked, the response is `409` problem `settlement-locked`. Settled payments are reversed through a case decision, not through cancellation.

### Activity and timeline

Account activity items contain `activityId`, `movementType`, `direction`, `amount`, `occurredAt`, and `paymentId`. They are a projection. The ledger balance shown to an operator for a decision is read from Ledger through an internal API at decision time, not from this projection.

Timeline items contain `eventId`, `eventType`, `summaryCode`, `status`, and `occurredAt`.

### Cases

`GET /v1/cases/{caseId}` returns source, status, linked ids, the latest recommendation when one exists, and the human decision when one exists. The recommendation includes disposition, confidence, evidence ids, and narrative.

`POST /v1/cases/{caseId}/decisions`

```json
{
  "decision": "CONFIRM_DECLINE",
  "rationale": "Beneficiary velocity and a deny-list hit agree."
}
```

The response is the case resource in `DECIDED`. A second decision on a decided case returns `409` unless the decision type is the four-eyes counter-approval for `PROPOSE_REVERSAL`, which uses `POST /v1/cases/{caseId}/approvals` with the same body shape and a different actor.

### Breaks and failures

Break list items contain `breakId`, `breakClass`, `severity`, `paymentId`, `amount`, `valueDate`, and `status`. Failure list items contain `caseId`, `caseType`, `reasonCode`, `attemptCount`, `nextAttemptAt`, and `caseStatus`.

## Internal surface

Listeners are cluster IP only. Network policy allows the callers named below and the mesh control plane. Ingress does not route these paths.

| Method and path | Caller | Owner | Idempotency |
| --- | --- | --- | --- |
| `POST /internal/v1/validations` | Initiation | Validation | Key per payment |
| `POST /internal/v1/fraud-assessments` | Initiation | Fraud | Key per payment |
| `POST /internal/v1/authorizations` | Initiation | Authorization | Key per payment |
| `POST /internal/v1/authorizations/{paymentId}/releases` | Initiation | Authorization | Ledger key |
| `GET /internal/v1/accounts/{accountId}` | Validation, Authorization | Ledger | Read |
| `POST /internal/v1/holds` | Authorization | Ledger | Ledger key |
| `POST /internal/v1/hold-releases` | Authorization | Ledger | Ledger key |
| `POST /internal/v1/postings` | Settlement | Ledger | Ledger key |
| `POST /internal/v1/settlement-items/{paymentId}/retries` | Failure Management | Settlement | Original ledger key |
| `GET /internal/v1/payments/{paymentId}` | Investigation tools | Initiation | Read |
| `GET /internal/v1/payments/{paymentId}/ledger` | Investigation tools | Ledger | Read |
| `POST /internal/v1/payments/{paymentId}/review-results` | Investigation | Initiation | Decision id |
| `POST /internal/v1/failure-cases/{caseId}/retries` | Investigation | Failure Management | Decision id |

Internal write bodies carry `paymentId`, `idempotencyKey`, `amount`, `currency`, and the fields that step needs. Responses carry the callee's durable id (`validationId`, `assessmentId`, `authorizationId`, `holdId`, `journalEntryId`) and a status of `COMPLETED` or `REJECTED` plus `reasonCode`. A technical failure is an HTTP `503` or `504` with a problem type, and the caller records `UNCERTAIN` when it did not receive a completed body.

Ledger returns `200` with the original resource when the same idempotency key is replayed, including when the first call timed out after commit.

## Status codes

| Code | Use |
| --- | --- |
| `200` | Read, idempotent replay, accepted decision |
| `201` | Payment created and decided inside the budget |
| `202` | Payment accepted and still running after the budget |
| `400` | Schema or missing idempotency key |
| `401` | Missing or invalid credential |
| `403` | Authenticated caller lacks the role or the account scope |
| `404` | Resource is outside the caller's scope, including unknown ids |
| `409` | Idempotency conflict, illegal state transition, settlement locked |
| `422` | Well-formed JSON that fails a business rule expressed as a problem, used when a synchronous internal validation prefers it over a `201` decline body. External create uses `201` plus a decline status for business rejects so the client always receives a payment id. |
| `429` | Rate limit, with `Retry-After` |
| `503` | Dependency unavailable, fail closed |
| `504` | Time limiter expired |

Unknown payment ids and payment ids the caller may not see both return `404`, so the API does not reveal whether an id exists.

## Problem type catalog

Type URIs are `https://apop.internal/problems/<slug>`.

| Slug | Typical status |
| --- | --- |
| `schema-invalid` | 400 |
| `idempotency-key-required` | 400 |
| `idempotency-conflict` | 409 |
| `account-forbidden` | 403 |
| `settlement-locked` | 409 |
| `illegal-state` | 409 |
| `rate-limited` | 429 |
| `risk-control-unavailable` | 503 |
| `ledger-unavailable` | 503 |
| `decision-timeout` | 504 |

The problem `detail` is a stable sentence for developers. Customer-facing copy is the channel's job. `reasonCode` from the business catalog is included as an extension field when a payment exists.

## Versioning and compatibility

`/v1` remains backward compatible. New response fields are optional. New required request fields, removed fields, and changed meanings are `/v2` on a parallel listener for one deprecation window. The deprecation window is 90 days after the replacement is generally available, announced through the API changelog in the contract module.

Internal APIs follow the same rule and may move faster because both sides ship in the monorepo. They still version the path, because database idempotency and outbox payloads refer to the contract version in release notes.

## Pagination, caching, and concurrency

List cursors are opaque. Clients treat them as unreadable tokens. History reads may send `ETag` based on the projection watermark. Payment status reads send `ETag` based on `version`. Clients that cache must revalidate. Status responses send `Cache-Control: no-store` because a stale authorization state is worse than a round trip.

Writes do not use `ETag` preconditions in version 1. State conflicts surface as `409 illegal-state` from the single writer.

## Rate limits

Default design limits, tunable per client id:

| Endpoint class | Limit |
| --- | --- |
| `POST /v1/payments` | 20 requests per second per client, burst 40 |
| Reads | 100 requests per second per client, burst 200 |
| Case decisions | 5 requests per second per user |

The gateway enforces these with Redis. Exceeding the limit returns `429` and does not reach Initiation, so it cannot create partial payments.

## Documentation and contract tests

Each external operation will have an OpenAPI description and at least one golden request and response. Contract tests will run against the producer on every build. A consumer-driven check covers the internal ledger hold and post operations, because a silent change there breaks idempotent recovery.

The channel sandbox uses the same OpenAPI and the rail simulator. It does not use a second, looser error format.
