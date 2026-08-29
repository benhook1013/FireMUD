# ADR 0146: Owner-Local Moderation Enforcement

## Status

Accepted

## Implementation Status

This decision is not implemented. Owner-local moderation state, acknowledged typed enforcement commands, monotonic transitions, and focused cross-service proof remain gaps.

## Decision Record

- Human review status: Completed
- Human review date: 2026-07-20
- Human review disposition: Revised
- Review source: `MOD-01`
- Decision date: 2026-07-20
- Decision key: `MOD-01`
- Primary capability: `PO-1.2` moderation and safety tooling
- Affected capabilities: `EA-2.4`, `AA-1.3`, `PO-1.3`, `PO-4.1`, `SF-2.3`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of moderation authority, hot-path latency, propagation, outage behavior, idempotency, and restriction semantics

## Context

FireMUD needs gameplay bans and communication restrictions to become effective reliably and remain auditable without making the operator-facing service a dependency of every routine player action. The previous target distributed versioned policy snapshots from Logging & Admin, required bounded-staleness caches in each enforcement service, and refreshed those snapshots on miss. The current implementation instead makes synchronous Logging & Admin calls on routine `PLAY` and chat paths. The former creates a second freshness and cache-invalidation protocol; the latter adds latency and couples gameplay availability to a remote control-plane service.

Neither model follows the established owner-authority direction. Logging & Admin is the external operator ingress and owns moderation cases, policy intent, and audit. Game Session already owns gameplay admission, while Social & Groups owns communication admission and history. The restriction records used by those decisions therefore belong with those enforcement owners.

## Decision

### Policy Intent and Enforcement State Are Separate

Logging & Admin owns operator ingress, moderation cases, policy definitions and intent, and audit history. It does not own the runtime enforcement truth for gameplay or communication.

At its receiving boundary, Logging & Admin must validate and redeem the separate Account-issued intent-persistence reference exactly once before persisting policy intent or audit. It retains the bounded redemption outcome alongside the existing actor, request, digest, and authorization-reference fingerprint identities; the opaque reference is never durably persisted, forwarded, or reused.

After authorization and policy evaluation, Logging & Admin durably records the actor, reason, exact subject and scope, typed action, local policy-intent payload digest, Account-returned `authorizationReferenceFingerprint`, bounded local intent-reference redemption outcome, and one `policyIntentRequestId`. The original opaque authorization reference is never durably persisted. That identity scopes only Logging & Admin's policy-input and audit mutation. Under [ADR 0048](./adr-0048-durable-idempotent-operator-write-execution.md), it creates a separate owner-enforcement command with its own `ownerEnforcementRequestId`, payload digest, and bounded owner-command authorization reference handoff. The opaque Account-issued owner reference is forwarded only through that bounded handoff to the selected enforcement owner, which redeems it and records the bounded redemption outcome. Its `authorizationReferenceFingerprint` is non-secret audit and comparison evidence only. The owner command may reference the policy intent for audit correlation, but it must not forward or reuse the local policy-intent mutation identity, digest, or authorization fingerprint; neither the owner reference nor its fingerprint is reused for the local policy-intent mutation:

- Game Session owns and enforces `gameplay_ban` records.
- Social & Groups owns and enforces `chat_mute` and `chat_ban` records.
- Account owns and enforces `platform_access_ban`, including its authentication-generation/account-wide revocation consequence, and retains owner-local restriction/security state and credential state. The protective `account_security_lock` remains separate Account-owned policy/recovery state.

The owner validates current authority and scope, then atomically commits the subject-scoped enforcement record and its idempotent command result. Owner-command idempotency and reconciliation use ADR 0048's exact canonical complete `postAuthorizationExecutionTuple`: `ownerEnforcementRequestId` is the owner-action request identity, while the owner payload digest and authorization-reference fingerprint are tuple members, not substitutes. Every tuple member—including the typed scope, target, immutable `targetOwner`, expected-version/mutation/audit-reason fields, schema pair, authority/workload/actor evidence, membership and issuance/reservation fences, and schema-bound presence/absence values—must match. An exact tuple replay returns the prior result; any mismatch is rejected as `IDEMPOTENCY_CONFLICT` without mutation. Logging & Admin reports success only after the owner acknowledges that durable commit. Its local policy-intent/audit persistence remains independently idempotent under `policyIntentRequestId`; a missing final audit-outcome update reconciles the local mutation against the recorded owner result and does not roll back or replay committed owner state.

Both mutation stages are durable recoverable operations under ADR 0048's request-identity and phase/recovery rules. The policy-intent record binds `policyIntentRequestId` to its canonical digest, reference-redemption outcome, and local commit state; a crash, timeout, or lost acknowledgement is recovered through a status or replay lookup of that same tuple and never by issuing or redeeming a replacement reference. The owner command binds `ownerEnforcementRequestId` to the exact canonical complete `postAuthorizationExecutionTuple`, its authorization-reference redemption outcome, and the durable owner request/result/effect record; crash recovery reconciles read-only against that record or leaves the operation in a phase-qualified pending state until commit or no-commit proof exists. Neither stage may return success from an ambiguous dispatch or apply a second effect during recovery.

### Monotonic Restriction Lifecycle

Each owner assigns every create, extension, expiry, removal, and correction command an authoritative owner-local monotonic ordering token (sequence) for the exact subject, restriction category, and scope. That sequence is durably allocated before transport forwarding, or comes from a durable owner-side ordering log established before application; transport arrival order must never allocate a newer sequence to an older command. The command carries its allocated sequence, and the owner applies, replays, or conflict-checks it against that sequence. The sequence, not wall-clock time or effective/expiry timestamps, orders transitions. An equal-sequence replay with an identical command payload is idempotent; an equal-sequence conflict is rejected and surfaced for reconciliation; and a delayed lower-sequence command is rejected or ignored and cannot supersede newer state. Every lifecycle action is a new owner command and ordered state transition, not an in-place rewrite of an ingress-owned snapshot.

The durable record includes enough state to enforce and audit the restriction without a Logging & Admin read, including the typed restriction, subject and scope, current status, ordering token, effective and optional expiry times, source request identity and digest, and timestamps. Logging & Admin remains the case and policy-intent authority; disagreement with owner state is surfaced as reconciliation lag rather than resolved by consulting an ingress-side runtime snapshot.

### Owner-Local Runtime Decisions

Account reads its owner-local security state at authentication and account-access boundaries. A committed `platform_access_ban` applies its authentication-generation/account-wide revocation consequence under the Account contract; the separate `account_security_lock` remains protective policy/recovery state.

Game Session reads its own indexed durable state at `PLAY` and command admission. A committed `gameplay_ban` stops new command admission and closes an active gameplay binding. Work already durably admitted may finish idempotently so enforcing a ban does not create partial domain writes or duplicate effects.

Social & Groups reads its own indexed durable state at communication boundaries. `chat_mute` blocks sending while allowing ordinary receipt. `chat_ban` blocks ordinary participation, sending, and history access. Essential system and moderation notices remain deliverable so the restriction and operator instructions can still reach the subject.

Routine `PLAY`, command admission, chat send, participation, and history decisions do not call Logging & Admin. Owners begin with indexed local database reads. An owner-local cache may be introduced only when measurements justify it; it is a rebuildable performance hint and never a separate policy authority or required distributed freshness protocol.

If the owner cannot read required local enforcement state, the protected action fails closed and emits operator-visible diagnostics. A Logging & Admin, remote audit/reporting, analytics, or observability outage does not block enforcement while the owner can read its own state.

## Consequences

- Routine gameplay and communication avoid a remote moderation RPC and do not inherit Logging & Admin latency or availability.
- Each enforcement owner requires a durable indexed restriction table and atomic idempotent command-result handling.
- Enforcement can continue through control-plane and observability outages after the owner has committed the restriction.
- Operator-visible success is slightly slower because it waits for owner durability, which is appropriate for a low-volume consequential control-plane action.
- Policy intent and enforcement state can temporarily disagree after a failed delivery or acknowledgement. Each mutation reconciles under its own exact canonical complete `postAuthorizationExecutionTuple`, including its request identity, digest, and authorization evidence, without weakening runtime checks or reusing the other mutation's identity.
- Applying a gameplay ban does not cancel or partially unwind work that was already durably admitted; it fences new admission and closes the binding.
- Muted players may still receive communication, while chat-banned players retain only essential system and moderation notice delivery.

## Alternatives Considered

### Versioned Policy Snapshots with Bounded-Staleness Caches

Logging & Admin could publish policy snapshots and invalidations, while Game Session and Social & Groups cache and refresh them. This supports broad policy evaluation at runtime but creates a distributed snapshot schema, invalidation protocol, shared staleness configuration, refresh dependency, and outage matrix for three low-cardinality enforcement actions. It also leaves ambiguous whether the snapshot or the owner's last applied state proves enforcement. FireMUD instead sends durable typed state-transition commands to the owner that already makes the protected decision.

### Synchronous Logging & Admin Check on Every Action

Game Session and Social & Groups could ask Logging & Admin during every `PLAY` or chat send. This centralizes the query but places control-plane latency and availability on routine player paths and creates avoidable synchronous fan-out. It is rejected.

### Centralize Enforcement in Logging & Admin

Logging & Admin could own both restriction state and enforcement. This would require gameplay and communication owners to delegate their admission decisions or permit Logging & Admin to mutate foreign runtime state. It conflicts with domain authority, expands the control-plane service into the hot path, and weakens local failure handling.

### Best-Effort Notification After Audit Commit

Logging & Admin could record a successful action before asynchronously notifying the owner. This can tell an operator that a ban succeeded while the runtime still permits the subject, and retries may apply ambiguous duplicate transitions. Success therefore waits for the durable owner acknowledgement under ADR 0048.

## Implementation and Proof Obligations

The current implementation is not aligned. It contains synchronous Logging & Admin checks on `PLAY` and chat-send paths rather than complete owner-local enforcement records. Implementation must introduce typed owner command contracts, payload-digest binding, monotonic subject-scoped persistence, indexed owner-local reads, and acknowledgement/reconciliation under ADR 0048 before claiming this decision complete.

Proof must cover authorization and exact scope; durable intent before forwarding; same-request/same-digest replay; request-identifier reuse with a different digest; duplicate and delayed commands plus reordered create/remove/expiry/correction sequence allocation and application; owner crash before and after commit; lost acknowledgement; and reconciliation without a second effect. It must also cover `PLAY`, live command admission, active-binding closure, already-admitted idempotent work, mute receipt versus send, chat-ban participation/send/history denial, essential notices, expiry, cross-tenant isolation, and owner-local database read failure.

Availability and performance proof must demonstrate no Logging & Admin call on routine enforcement paths, continued enforcement during Logging & Admin and observability outages, indexed local-read behavior, and truthful fail-closed outcomes when the owner's required local state cannot be read. Any cache requires separate evidence that owner durability remains authoritative and cache loss or invalidation lag cannot permit a forbidden action.

Select validation and runtime evidence according to [`validation and runtime proof`](../../developer-workflows/validation-and-runtime-proof.md); record actual execution results in PR/CI evidence or implementation-tracking documents, not in this ADR.

## Reversibility and Revisit Triggers

Record schemas, indexes, command transport, and optional owner-local caches may evolve while preserving owner-local durable authority, monotonic transitions, ADR 0048 idempotency, and the no-routine-remote-check rule. Revisit snapshot distribution only if moderation grows into high-cardinality policies that genuinely require continuous runtime evaluation and measurements show typed state-transition commands are insufficient. Revisit cancellation of already admitted work only with a domain-wide compensating-transaction design that can avoid partial writes.

## Required Documentation Alignment

- [`design/architecture/system-architecture-overview.md`](../system-architecture-overview.md)
- [`design/architecture/microservices/logging-admin-service/moderation-policies.md`](../microservices/logging-admin-service/moderation-policies.md)
- [`design/architecture/microservices/logging-admin-service/runtime-and-data.md`](../microservices/logging-admin-service/runtime-and-data.md)
- [`design/architecture/microservices/account-service/api-contracts.md#account-owned-safety-restriction-contract`](../microservices/account-service/api-contracts.md#account-owned-safety-restriction-contract)
- [`design/architecture/microservices/game-session-service/runtime-and-data.md#owner-local-gameplay-restrictions`](../microservices/game-session-service/runtime-and-data.md#owner-local-gameplay-restrictions)
- [`design/architecture/microservices/social-groups-service/runtime-and-data.md#owner-local-communication-restrictions`](../microservices/social-groups-service/runtime-and-data.md#owner-local-communication-restrictions)
