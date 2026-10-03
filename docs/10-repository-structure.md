# 10. Repository structure

| | |
| --- | --- |
| Status | Proposed |
| Audience | Engineers starting implementation |
| Related | [Service boundaries](02-service-boundaries.md), [Deployment](09-deployment-architecture.md), [APIs](06-api-architecture.md) |

The first delivery is a Maven multi-module monorepo. Service boundaries are module boundaries. A service may depend on `platform-*` libraries and on published contract artifacts. It may not depend on another service's internal packages. That rule is what makes a later split into separate repositories a mechanical move rather than a redesign.

The tree below is the target. Creating it is part of implementation. This document does not add those directories yet.

## Why a monorepo first

Contracts, the ledger idempotency rules, and the lifecycle status enum must change together during the first releases. One repository gives one review and one compatibility check. The module fence keeps the option to extract a service when its release cadence diverges. Ledger and Fraud are the likely first extractions because their review and access rules are stricter.

## Tree

```text
agentic-payment-operations-platform/
├── README.md
├── pom.xml                          # reactor and dependency management
├── docs/                            # this architecture pack
│   └── README.md
├── contracts/
│   ├── openapi/                     # external and internal HTTP
│   ├── asyncapi/                    # topics and channels
│   ├── avro/                        # payloads referenced by the envelope
│   └── fixtures/                    # golden requests, events, and agent cases
├── platform/
│   ├── platform-bom/                # aligned Spring Boot, OTel, Resilience4j
│   ├── platform-observability/      # logging, tracing helpers, metric names
│   ├── platform-messaging/          # outbox relay, envelope, Kafka headers
│   ├── platform-security/           # resource-server and internal JWT checks
│   ├── platform-web/                # problem details and idempotency filter
│   └── platform-testkit/            # contract-test helpers and synthetic ids
├── services/
│   ├── api-gateway/
│   ├── payment-initiation/
│   ├── payment-validation/
│   ├── fraud-detection/
│   ├── payment-authorization/
│   ├── ledger/
│   ├── payment-settlement/
│   ├── transaction-history/
│   ├── reconciliation/
│   ├── failure-management/
│   └── investigation-agent/
├── simulation/
│   └── rail-simulator/
├── deploy/
│   ├── helm/
│   │   ├── charts/<service>/
│   │   └── environments/<env>/values.yaml
│   └── gitops/                      # environment manifests Argo CD watches
├── ops/
│   └── runbooks/                    # source for the approved runbook index
└── tests/
    ├── contract/                    # consumer-driven checks
    └── acceptance/                  # end-to-end scenarios on the local profile
```

## One service module

```text
services/ledger/
├── pom.xml
├── src/main/java/com/apop/ledger/
│   ├── api/             # HTTP adapters
│   ├── domain/          # accounts, holds, journal, invariants
│   ├── persistence/     # PostgreSQL mappings
│   └── messaging/       # outbox writer and movement publisher
├── src/main/resources/
│   ├── db/migration/    # forward-only migrations
│   └── application.yaml
└── src/test/java/
```

Package root is `com.apop.<service>`. The domain package does not import Spring web or the Kafka client. Adapters depend inward. This is the structure every service follows, with extra packages only when that service has a genuine extra port. Investigation Agent adds `agent` for the runner and `tools` for the gateway. It still does not import Ledger's domain classes. It calls Ledger's HTTP contract.

## Contract-first workflow

1. Change the OpenAPI, AsyncAPI, or Avro file in `contracts` when the external behavior changes.
2. Review the compatibility rule. Backward-compatible Avro stays on the same topic. Breaking changes get a new major.
3. Generate or hand-write the fixtures in the same change.
4. Implement the producer and the consumer against the fixture.
5. Update the architecture pack when the change alters a decision in these documents, not when it only fills in code.

Generated sources are build output. They are not edited by hand and they are not the design. The design remains this `docs` pack plus the contract files.

## Dependency rules

| Allowed | Forbidden |
| --- | --- |
| Service to `platform-*` | Service to another service's `src` |
| Service to a contract artifact | A platform library depending on a service |
| Tests to testkit and fixtures | `simulation` depending on Ledger internals |
| Helm chart to its own service image | A shared "common domain" module of payment entities |

A shared enum of reason codes may live under `contracts` as a published schema. Duplicating the string in two services is acceptable. Sharing a mutable Java enum jar that both domains "own" is how boundaries dissolve, so the schema file is the shared form.

## Build and quality gates

The reactor build, when implementation exists, will gate on:

| Gate | What it protects |
| --- | --- |
| Unit tests | Domain invariants, especially balanced entries and idempotent replay |
| Contract tests | Ledger hold and post, payment create, lifecycle schema |
| Architecture fitness | No cross-service package imports, no Kafka client inside `domain` |
| Dependency and image scan | Delivery controls in the security architecture |
| Migration lint | Expand/contract compatibility of the schema change |
| Agent eval | Golden cases for investigation changes, from [the agent design](05-agentic-ai-architecture.md) |

Acceptance scenarios that must exist before the first production-shaped release:

| Scenario | Asserts |
| --- | --- |
| Book payment happy path | `SETTLED`, balanced journal, history timeline, trace linked |
| Duplicate submit | One hold and one payment |
| Idempotency key reused with a different body | Conflict, one payment |
| Insufficient funds | Decline, no hold left open |
| Fraud decline | No hold |
| Fraud service down | Fail closed, no hold |
| Fraud review then human release | Hold placed only after the decision |
| Cancel before lock | Hold released, status `CANCELLED` |
| ACH window | Item waits for the window and then posts |
| Statement mismatch | Break opens, agent may recommend, human decides |
| Poison history record | Dead letter, partition continues, failure case opens |
| Ledger retry after timeout | One journal entry |

## Configuration layout

Environment-specific values live in `deploy/helm/environments`. Service defaults live next to the chart. Secrets do not live in the repository. A `deploy/helm/environments/*/secrets.example.yaml` may list the Vault paths and never the values.

Local overrides use `application-local.yaml`, gitignored when it contains secrets. Committed local defaults use only local dummy credentials that are obviously synthetic.

## Documentation placement

| Kind | Location |
| --- | --- |
| Architecture decisions and designs | `docs/` |
| HTTP and event contracts | `contracts/` |
| Operator procedures | `ops/runbooks/` |
| Service-level notes that only help a contributor run that module | `services/<name>/README.md`, added when that module exists |

A service README, when written, points back to the boundary in `docs/02-service-boundaries.md` and does not restate the platform design.

## First implementation sequence

The repository can grow in this order without violating the boundaries:

1. Reactor, bill of materials, and `platform-observability`, `platform-web`, and `platform-messaging`.
2. `contracts` for the payment create API, the lifecycle event, and the ledger hold and post API.
3. Ledger, because every money path depends on its idempotency and invariants.
4. Validation, fraud, and authorization as deterministic services with fixtures.
5. Payment Initiation and the gateway, completing the synchronous path.
6. Settlement and the history projector.
7. Failure management and reconciliation, with the rail simulator in `test` and `uat`.
8. Investigation agent against the read APIs, with the eval gate in place before the runner is enabled in `uat`.

Observability hooks from `platform-observability` land in step 1 so later services do not bolt tracing on afterward. Helm charts land with the service that needs them, starting with Kafka, PostgreSQL, and Ledger.

## Names frozen by this pack

Implementers use these identifiers so the documents, contracts, and runtime stay aligned.

| Kind | Identifier |
| --- | --- |
| System | `apop` |
| Package root | `com.apop` |
| Payment statuses | The canonical set in the high-level architecture |
| Topics | The catalog in the Kafka design |
| Databases | `initiation`, `validation`, `fraud`, `authorization`, `ledger`, `settlement`, `history`, `reconciliation`, `failure`, `investigation` |
| External base path | `/v1` |
| Internal base path | `/internal/v1` |
| Kubernetes namespaces | The deployment design |

A new service, topic, or status is an architecture change first and a module second.
