# 15. Sequence diagrams

| | |
| --- | --- |
| Status | Proposed |
| Audience | Engineers tracing one payment through the services |
| Related | [Event storming](14-event-storming.md), [Domain guide](11-payment-domain-guide.md), [API architecture](06-api-architecture.md), [Failure scenarios](12-failure-scenarios.md) |

These sequences use Maya's $1,250.00 invoice (`125000` USD, key `maya-invoice-4401`). Names in the diagrams match the services engineers call. The architecture names are the ones in the rest of this pack.

| Diagram name | Architecture service |
| --- | --- |
| Actor | Channel, or the analyst named on that diagram |
| API Gateway | API Gateway |
| Payment Service | Payment Initiation, the only writer of payment status |
| Validation Service | Payment Validation |
| Fraud Service | Fraud Detection |
| Ledger | Ledger |
| Kafka | Topics in the [event design](03-kafka-event-design.md) |
| Settlement Service | Payment Settlement |
| Investigation Service | Investigation Agent |

Authorization is included wherever a hold is placed or released. Payment Service does not change balances. Reconciliation is included on the break diagram because that service opens the break. A service that is idle is listed under the diagram.

## 1. Successful payment

Book transfer. Validation passes, fraud approves, the ledger places one hold, and settlement posts one balanced entry. The HTTP response returns at authorization. `PaymentSettled` follows on Kafka.

```mermaid
sequenceDiagram
  actor Actor
  participant Gw as API Gateway
  participant Pay as Payment Service
  participant Val as Validation Service
  participant Fraud as Fraud Service
  participant Auth as Authorization
  participant Led as Ledger
  participant K as Kafka
  participant Setl as Settlement Service

  Actor->>Gw: POST /v1/payments key maya-invoice-4401
  Gw->>Gw: Authenticate and rate-limit
  Gw->>Pay: SubmitPayment
  Pay->>Pay: Commit PaymentReceived and idempotency hash
  Pay->>Val: ValidatePayment
  Val-->>Pay: Pass, value date confirmed
  Pay->>Pay: Commit PaymentValidated
  Pay->>Fraud: AssessFraud
  Fraud-->>Pay: APPROVE
  Fraud->>K: FraudAssessmentCompleted
  Pay->>Auth: AuthorizePayment
  Auth->>Led: PlaceHold 125000 USD
  Led->>Led: Commit FundsHeld
  Led-->>Auth: Hold id
  Auth-->>Pay: Authorized with hold id
  Pay->>Pay: Commit PaymentAuthorized and PaymentSettlementPending
  Pay->>K: Lifecycle PaymentAuthorized
  Pay->>K: RequestSettlement
  Pay-->>Gw: 201 SETTLEMENT_PENDING
  Gw-->>Actor: Payment id and status
  K->>Setl: RequestSettlement
  Setl->>Led: PostSettlement same payment, ledger key
  Led->>Led: Commit JournalPosted and consume hold
  Led->>K: JournalPosted for Maya and for Northwind
  Led-->>Setl: Journal entry id
  Setl->>K: SettlementItemPosted
  K->>Pay: SettlementItemPosted
  Pay->>Pay: Commit PaymentSettled
  Pay->>K: Lifecycle PaymentSettled
```

Investigation Service is not called. After `PaymentSettled`, Maya's ledger balance is $750 and Northwind's is $1,750 when Northwind started at $500. The actor's `201` arrived before that post. A later `GET` shows `SETTLED`.

## 2. Fraud decline

Fraud returns `DECLINE`. The payment ends as `FRAUD_DECLINED`. No hold is placed, so Authorization, Ledger, and Settlement do not run.

```mermaid
sequenceDiagram
  actor Actor
  participant Gw as API Gateway
  participant Pay as Payment Service
  participant Val as Validation Service
  participant Fraud as Fraud Service
  participant K as Kafka

  Actor->>Gw: POST /v1/payments key maya-invoice-4401
  Gw->>Pay: SubmitPayment
  Pay->>Pay: Commit PaymentReceived
  Pay->>Val: ValidatePayment
  Val-->>Pay: Pass
  Pay->>Fraud: AssessFraud
  Fraud->>Fraud: Commit assessment DECLINE
  Fraud-->>Pay: DECLINE
  Fraud->>K: FraudAssessmentCompleted
  Pay->>Pay: Commit PaymentFraudDeclined
  Pay->>K: Lifecycle PaymentFraudDeclined reason FRAUD_DECLINE
  Pay-->>Gw: 201 FRAUD_DECLINED
  Gw-->>Actor: Payment id, status FRAUD_DECLINED
```

Authorization, Ledger, Settlement Service, and Investigation Service are not called. Available balance stays $2,000. A retry of the same key and body returns this same decline and does not score the payment again.

`FRAUD_REVIEW` is a different outcome. The payment waits with no hold, and diagram 6 starts from that fact.

## 3. Settlement failure

Authorization has already completed as in diagram 1. The actor holds a `201` of `SETTLEMENT_PENDING` and an open hold of $1,250. Settlement then fails against the ledger. The `alt` is the two failures operations actually see: a business refusal, and a timeout after the post may have committed.

```mermaid
sequenceDiagram
  participant Pay as Payment Service
  participant K as Kafka
  participant Setl as Settlement Service
  participant Led as Ledger
  participant Fail as Failure Management
  actor Actor

  Pay->>K: RequestSettlement with hold id and ledger key
  K->>Setl: RequestSettlement
  alt Ledger refuses the post
    Setl->>Led: PostSettlement
    Led-->>Setl: Refuse, hold still open
    Setl->>K: Settlement item POST_REFUSED
    K->>Pay: SettlementItemPosted refusal
    Pay->>Pay: Commit PaymentSettlementFailed
    Pay->>K: Lifecycle PaymentSettlementFailed
    Actor->>Pay: GET /v1/payments/{id}
    Pay-->>Actor: SETTLEMENT_FAILED, hold still open
  else Timeout after the ledger may have committed
    Setl->>Led: PostSettlement
    Led->>Led: Commit JournalPosted
    Led--xSetl: Response lost
    Setl->>Setl: Item POST_UNCERTAIN
    Setl->>K: Failure signal for the item
    K->>Fail: OpenFailureCase POST_UNCERTAIN
    Fail->>Setl: RetryStep same ledger key
    Setl->>Led: PostSettlement same key
    Led-->>Setl: Original journal entry id
    Setl->>K: SettlementItemPosted
    K->>Pay: SettlementItemPosted
    Pay->>K: Lifecycle PaymentSettled
  end
```

API Gateway, Validation Service, Fraud Service, and Investigation Service are not called on this stretch. They already ran, or they are not part of posting.

On the refusal branch the journal has no new entry and the hold stays open until cancel, expiry, or a later successful post. On the timeout branch there is one journal entry. The retry is safe because the ledger key is the same. Payment Service emits `PaymentSettled` only after `SettlementItemPosted`.

## 4. Duplicate request

The first call commits the payment and its idempotency hash. The response is lost. The actor sends the same key and the same body. A later bug sends the same key with a different amount.

```mermaid
sequenceDiagram
  actor Actor
  participant Gw as API Gateway
  participant Pay as Payment Service
  participant Val as Validation Service
  participant Fraud as Fraud Service
  participant Led as Ledger
  participant K as Kafka

  Actor->>Gw: POST key maya-invoice-4401 body 125000
  Gw->>Pay: SubmitPayment
  Pay->>Pay: Store key and request hash
  Pay->>Val: ValidatePayment
  Val-->>Pay: Pass
  Pay->>Fraud: AssessFraud
  Fraud-->>Pay: APPROVE
  Pay->>Led: PlaceHold through Authorization
  Led-->>Pay: One hold id
  Pay->>K: Lifecycle PaymentAuthorized
  Pay-->>Actor: Response lost
  Actor->>Gw: POST same key and same body
  Gw->>Pay: SubmitPayment
  Pay->>Pay: Hash matches the stored payment
  Pay-->>Gw: 200 same payment id and current status
  Gw-->>Actor: Original receipt
  Actor->>Gw: POST same key body 130000
  Gw->>Pay: SubmitPayment
  Pay-->>Gw: 409 idempotency-conflict
  Gw-->>Actor: No new payment
```

Settlement Service and Investigation Service are not called by the replay or the conflict. Validation, Fraud, and Ledger run on the first call only. The `409` does not emit a lifecycle event and does not place a second hold. Kafka may redeliver `PaymentAuthorized` to consumers. Those consumers deduplicate on event id and do not post again.

## 5. Reconciliation break

The book, or the ACH item, is already `SETTLED` for `125000` USD. Monday's statement line for end-to-end id `E2E-20261002-0001` says `125100`. Reconciliation opens a break. The journal is not changed. Investigation opens a case and waits for a person.

```mermaid
sequenceDiagram
  participant Setl as Settlement Service
  participant Led as Ledger
  participant K as Kafka
  participant Recon as Reconciliation
  participant Inv as Investigation Service
  actor Actor as Operations analyst
  participant Gw as API Gateway

  Note over Setl,Led: Earlier successful post
  Setl->>Led: PostSettlement 125000 USD
  Led->>K: JournalPosted
  Setl->>K: SettlementItemPosted
  K->>Recon: Settlement item and ledger movements
  Recon->>Recon: Import statement line 125100 USD
  Recon->>Recon: Amount mismatch, no match row
  Recon->>K: ReconciliationBreakOpened AMOUNT_MISMATCH
  K->>Inv: OpenInvestigation source RECON_BREAK
  Inv->>Inv: Commit InvestigationCaseOpened
  Inv->>K: InvestigationCaseOpened
  Actor->>Gw: GET /v1/reconciliation/breaks
  Gw->>Recon: List open breaks
  Recon-->>Actor: Break, payment still Settled
  Actor->>Gw: GET /v1/cases/{caseId}
  Gw->>Inv: Case, no decision yet
```

API Gateway is on the analyst read, not on a new payment create. Payment Service, Validation Service, and Fraud Service are not called. Payment Service does not emit a new lifecycle status. The channel still reads `SETTLED` for $1,250.00. A correcting entry happens only after diagram 6's decision path, and only when the human disposition is an approved reversal or adjustment.

## 6. Agent investigation

The payment is `FRAUD_REVIEW`. Intake has opened a case. The fraud analyst asks for assistance. The Fraud Agent, inside Investigation Service, reads the assessment and recommends a decline. The analyst records the decision. The `alt` shows the release path, which is the first moment Ledger is allowed to place a hold.

```mermaid
sequenceDiagram
  actor Actor as Fraud analyst
  participant Gw as API Gateway
  participant Inv as Investigation Service
  participant Fraud as Fraud Service
  participant Pay as Payment Service
  participant Auth as Authorization
  participant Led as Ledger
  participant K as Kafka

  K->>Inv: Lifecycle PaymentHeldForFraudReview
  Inv->>Inv: InvestigationCaseOpened
  Inv->>K: InvestigationCaseOpened
  Actor->>Gw: POST /v1/cases/{caseId}/assist
  Gw->>Inv: RequestAssistance
  Inv->>Inv: Supervisor routes to Fraud Agent
  Inv->>Fraud: Read assessment for this payment only
  Fraud-->>Inv: Score, policy version, rule ids
  Inv->>Inv: Policy guard accepts the recommendation
  Inv->>K: InvestigationRecommendationIssued CONFIRM_DECLINE
  Inv-->>Actor: Case AWAITING_HUMAN
  alt Analyst confirms the decline
    Actor->>Gw: POST decision CONFIRM_DECLINE and rationale
    Gw->>Inv: RecordDecision
    Inv->>K: InvestigationDecisionRecorded
    Inv->>Pay: ApplyReviewResult decline
    Pay->>Pay: Commit PaymentFraudDeclined
    Pay->>K: Lifecycle PaymentFraudDeclined
    Pay-->>Actor: FRAUD_DECLINED, no hold
  else Analyst releases the payment
    Actor->>Gw: POST decision RELEASE_TO_AUTHORIZE
    Gw->>Inv: RecordDecision
    Inv->>Pay: ApplyReviewResult release
    Pay->>Auth: AuthorizePayment
    Auth->>Led: PlaceHold
    Led-->>Auth: FundsHeld
    Auth-->>Pay: Authorized
    Pay->>K: Lifecycle PaymentAuthorized
    Pay->>K: RequestSettlement
  end
```

Validation Service and Settlement Service are not called during the review. Settlement runs later only on the release branch, as in diagram 1, after `RequestSettlement`. The agent never calls Ledger. The analyst who submitted the original payment cannot record this decision. A recommendation without `InvestigationDecisionRecorded` leaves the payment in `FRAUD_REVIEW`.

## What the six diagrams share

| Rule | Where it shows up |
| --- | --- |
| The actor's create waits only until authorization, review, or a decline | Diagrams 1 and 2 return before settlement |
| One idempotency key produces one payment | Diagram 4 |
| A hold exists only after authorization | Diagrams 1, 3, and the release branch of 6 |
| Payment Service is the only service that emits lifecycle status | Every status change on Kafka |
| Ledger emits balance facts. Settlement emits the item fact. | Diagrams 1, 3, and 5 |
| A break or a fraud review waits for a human | Diagrams 5 and 6 |
| The agent recommendation is not the decision | Diagram 6 |
