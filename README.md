# Agentic Payment Operations Platform

Reference architecture for a simulated, production-shaped payment operations platform.

The platform shows how a modern bank accepts a payment instruction, validates it, screens it, places a funds hold, settles it, and then reconciles, investigates, and explains the outcome. Money movement is modeled with a double-entry ledger. Agentic AI investigates exceptions. Agents do not authorize payments or post the ledger.

This repository currently contains architecture only. Implementation starts from the contracts and service boundaries in [docs/README.md](docs/README.md).

| Document | Subject |
| --- | --- |
| [Architecture index](docs/README.md) | How to read the pack |
| [1. High-level architecture](docs/01-high-level-architecture.md) | Context, lifecycle, decisions |
| [2. Service boundaries](docs/02-service-boundaries.md) | What each service owns |
| [3. Kafka event design](docs/03-kafka-event-design.md) | Topics, envelopes, consumers |
| [4. PostgreSQL schema design](docs/04-postgresql-schema-design.md) | Database per service |
| [5. Agentic AI architecture](docs/05-agentic-ai-architecture.md) | Investigation agents and controls |
| [6. API architecture](docs/06-api-architecture.md) | External and internal APIs |
| [7. Security architecture](docs/07-security-architecture.md) | Identity, data, and audit |
| [8. Monitoring architecture](docs/08-monitoring-architecture.md) | SLOs, traces, and alerts |
| [9. Deployment architecture](docs/09-deployment-architecture.md) | Kubernetes, data plane, delivery |
| [10. Repository structure](docs/10-repository-structure.md) | Target monorepo layout |
| [11. Payment domain guide](docs/11-payment-domain-guide.md) | Lifecycle, holds, ledger, reversals, and failures for engineers new to banking |
| [12. Failure scenarios](docs/12-failure-scenarios.md) | Dependency failures, duplicates, and reconciliation mismatches |
| [13. Investigation workbench](docs/13-investigation-workbench.md) | Operator console, agent roles, and human approval |
| [14. Event storming](docs/14-event-storming.md) | Commands, domain events, aggregates, policies, and read models |
| [15. Sequence diagrams](docs/15-sequence-diagrams.md) | Success, fraud decline, settlement failure, duplicates, breaks, and investigation |
| [16. SRE runbook](docs/16-sre-runbook.md) | Severity, detection, and recovery for production incidents |
| [17. Fraud detection](docs/17-fraud-detection.md) | Rules, velocity, lists, geo and device risk, and fraud review |
| [18. Product requirements](docs/18-product-requirements-document.md) | Goals, MVP scope, user stories, and launch acceptance |
| [19. Implementation roadmap](docs/19-implementation-roadmap.md) | Single-engineer build order from contracts and ledger through agents |
| [20. Interview guide](docs/20-interview-guide.md) | How to explain the design in a payments interview |
