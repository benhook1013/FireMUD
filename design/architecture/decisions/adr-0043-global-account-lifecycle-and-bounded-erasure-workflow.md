# ADR 0043: Global Account Lifecycle and Bounded Erasure Workflow

## Status

Accepted

## Implementation Status

The bounded erasure workflow is target state and is not yet fully implemented or proved. The current deletion implementation and its `deleteAccountRemovesAccountOwnedRowsAfterTerminalSubscriptions` proof still model immediate deletion after the billing precondition, including account-owned rows that the target workflow must retain or process asynchronously. They must be treated as known drift until `deactivated_pending_delete`, cross-service progress, retention-safe handling, and terminal completion gating are implemented together.

## Decision Record

- Decision date: 2026-07-19
- Primary capability: `AA-1.3` Account security, recovery, and lifecycle
- Affected capabilities: `AA-1.1`, `AA-1.2`, `AA-1.5`, `SF-2.1`, `PO-1.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `MS-AA-LIFECYCLE-ERASURE`
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `MS-AA-LIFECYCLE-ERASURE`

## Context

Account deletion affects every joined game and may intersect active tenant billing ownership, portable player data, payment settlement, refunds, tax, fraud investigation, and audit obligations. Immediate row deletion is simple but can orphan hosted games, destroy necessary financial evidence, and report completion before other domain services have erased their data. Indefinite soft deletion has the opposite failure: personal data never reaches a terminal minimized state.

The current implementation checks nonterminal billing ownership and then immediately hard-deletes the account, subscriptions, and payment transactions. That is implementation drift, not accepted lifecycle behavior.

## Decision

- Account owns the global states `active`, `security_locked`, `deactivated_pending_delete`, and terminal `deleted`. Tenant suspension, cancellation, membership removal, or leaving a game never substitutes for global account deletion.
- A self-service deletion request requires recent account authentication, explicit confirmation, and a clear full-account export option. A security-locked account uses the account-recovery or support-reviewed path rather than ordinary login.
- Every nonterminal tenant subscription owned by the account blocks deletion. The response identifies all affected tenants when safe, and ownership must be transferred or each subscription terminally canceled before the request can proceed.
- An eligible confirmed request enters `deactivated_pending_delete`, immediately advances account auth generation, revokes ordinary account/bootstrap authority, and hides the normal account/profile surfaces. Only explicit deletion cancellation, export, and necessary billing-settlement access remain during the published cancellation and retention window.
- Erasure is an asynchronous, retryable cross-service workflow. Each owning domain deletes, anonymizes, tombstones, or schedules retention of its records under the canonical data policy. The account cannot become terminal `deleted` until every required domain step is completed or durably recorded under an allowed retention schedule.
- Billing, payment, refund, Stripe, tax, fraud, and audit evidence is never blindly hard-deleted. Retained records keep only the minimum identity reference and access necessary for reconciliation. Exact categories, legal bases, and durations are owned by the `DATA-01` retention decision rather than invented by the deletion endpoint.
- A failed or incomplete domain step leaves the account visibly pending with retry and operator diagnostics. The system must not report terminal deletion early.
- Before the published cancellation window expires, the exact `pending_deletion_scoped` cancellation route may return a pending account to `active` after the dedicated recovery challenge or already-authenticated deletion workflow establishes the Account-owned pending-deletion credential. Terminal `deleted` has no recovery transition. Support may help complete the recovery challenge while an account is `security_locked`, but there is no implicit support bypass once it enters pending deletion; any future operator cancellation route requires its own explicit classification and credential contract.

### Pending-Deletion Access

Pending deletion has a dedicated Account-owned access credential; it does not preserve ordinary login authority. Account issues one opaque `pending-deletion-access` credential only after a dedicated recovery challenge or an already authenticated deletion workflow proves the account subject. The credential is not a JWT, is not stored in the ordinary `session:auth:token:<tokenHash>` registry, and does not use or restore normal account-generation authority.

Account stores a server-side pending-deletion access record bound to the account, deletion workflow identifier and workflow generation, allowed action family, issue time, expiry, and credential hash. The exact route allowlist is `GET /accounts/{accountId}/deletion` (`pending_deletion_scoped` status), `POST /accounts/{accountId}/deletion/cancel` (`pending_deletion_scoped` cancellation), the full-subject export lifecycle `POST /accounts/{accountId}/exports`, `GET /accounts/{accountId}/exports/{exportId}`, and `GET /accounts/{accountId}/exports/{exportId}/content` defined by ADR 0050 (`pending_deletion_scoped` export only), and `POST /accounts/{accountId}/deletion/billing-settlement` (`pending_deletion_scoped` necessary settlement only). No normal profile, tenant, purchase, login, bootstrap, connect-token, or gameplay route accepts it.

Cancellation, terminal completion, and expiry revoke the pending-deletion record. Cancellation then establishes a fresh normal account generation; it does not make the pending credential valid for normal access. Re-establishment after credential loss or expiry requires a dedicated pending-deletion recovery challenge and can issue only a newly scoped pending-deletion credential. It cannot issue a normal login or gameplay credential, and the normal account remains denied until the cancellation transition completes.

## Consequences

- Users receive one understandable global deletion request without conflating it with leaving an individual game.
- Active hosting responsibility cannot be orphaned, and necessary settlement evidence survives in minimized form.
- Ordinary access ends immediately while physical erasure and required retention proceed safely.
- Deletion is no longer one database transaction; it requires durable orchestration, idempotent domain handlers, progress visibility, and failure recovery.
- Terminal deletion may be delayed by the published cancellation window and legally required retention. FireMUD must disclose that distinction rather than promising instantaneous physical erasure.

## Alternatives Considered

### Immediate Hard Deletion

Delete all Account rows after the billing precondition. This minimizes workflow code but destroys settlement evidence, cannot erase other services atomically, and may create irreversible partial deletion.

### Tenant-Local Deletion

Delete data for one game while retaining the account. That is a leave or tenant-data lifecycle operation, not fulfillment of a global account-erasure request.

### Indefinite Soft Deletion

Disable login forever while retaining all records. This simplifies recovery and support but provides no terminal minimization boundary and is therefore not accepted.

## Implementation and Proof Obligations

- Replace the immediate hard-delete path with explicit state transitions, recent-auth confirmation, audit, auth-generation advancement, and a durable idempotent erasure workflow.
- Return every safely disclosable blocking billing ownership and prove transfer or terminal cancellation is required before pending deletion begins.
- Define owner-specific erase, anonymize, tombstone, and allowed-retention handlers, with durable progress and retry state; align their exact data categories and durations through `DATA-01`.
- Prove pending accounts cannot use ordinary login, bootstrap, tenant, purchase, or gameplay surfaces while cancellation, export, and settlement routes remain narrowly available.
- Implement an Account-owned server-side pending-deletion credential registry separate from the ordinary issued-token registry, with workflow binding, exact allowlisted `pending_deletion_scoped` routes, and revocation on cancellation, terminal completion, and expiry.
- Prove the dedicated recovery challenge can re-establish only pending-deletion access and cannot restore normal login, bootstrap, connect-token, tenant, purchase, or gameplay authority.
- Prove cancellation before the published cutoff, irreversibility after terminal deletion, safe retry after partial failure, and no early success response.
- Complete full-account export across portable owning domains rather than treating Account rows and profiles as the whole export.

## Reversibility and Revisit Triggers

The state machine can add domain steps and adjust policy-owned retention windows without redefining global account identity. Revisit if applicable law or payment-provider obligations require a materially different cancellation, erasure, or retention boundary, or if an external identity provider becomes the lifecycle authority.
