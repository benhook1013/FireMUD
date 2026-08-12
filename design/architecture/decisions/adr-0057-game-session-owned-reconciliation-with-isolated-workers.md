# ADR 0057: Game Session-Owned Reconciliation with Isolated Workers

## Status

Accepted

## Implementation Status

Durable Game Session command, effect, and remote-follow-up recovery seams are partial. The complete one-row-per-logical-effect authority with a frozen participant set, isolated reconciliation workers, operator dispositions, retention, fairness, and focused crash/outage proof remains to be implemented and proven.

## Canonical Design

- [Transaction Strategies](../system-architecture-transactions.md)
- [Tick System and Runtime Design](../system-architecture-ticks.md)

## Decision Record

- Decision date: 2026-07-19
- Decision key: `RECON-01`
- Primary capability: `GR-1.4` Runtime recovery, replay, and reconciliation
- Affected capabilities: `SF-2.3`, `GR-2.3`, `GR-3.2`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `RECON-01`, including central-owner validation and domain/dedicated-service alternatives
- Human review status: Completed
- Human review date: 2026-07-19
- Human review disposition: Revised
- Review source: `RECON-01`

## Context

World and Entity know whether their own writes committed, but neither knows the complete expected participant set or player-visible outcome of a cross-service effect. Independent retry queues would create competing clocks, fragmented dead letters, and service-local mini-ledgers. A separate reconciliation service would either duplicate Game Session's tick/effect authority or continuously depend on it.

Central logical ownership must not make retry scans compete with latency-sensitive gameplay capacity.

## Decision

Game Session owns one durable reconciliation row per logical effect, including the original root and participant effect identities, immutable expected-participant set, per-participant durable outcome, player-command correlation, retry state, and operator disposition. The participant set is frozen when the effect is admitted.

World, Entity, and other domains retain exclusive authority for their writes and durable idempotency guards. A participant acknowledgement means its guard and effect-visible rows committed and can be served through its authoritative read contract; accepted, buffered, or dispatched is not applied.

Game Session alone derives cross-owner convergence and the command outcome under ADR 0053. Replay preserves the original identities and request digests. It never mints a replacement effect merely to retry and never applies destructive compensation by default.

Reconciliation execution runs in independently scalable and resource-isolated workers rather than latency-sensitive tick threads. Required controls include separate thread/connection/capacity budgets, bounded scans, tenant/region fairness, exponential backoff with jitter, owner-service circuit breakers, retry pressure metrics, and admission shedding that cannot starve normal tick work.

Status and retention rules:

- active `PENDING` rows are never garbage-collected;
- `DEAD_LETTER` pauses automatic retry but is not success, compensation, or permission to discard evidence;
- operator retry or acknowledgement records actor, reason, time, prior state, and explicit player-outcome mapping;
- `CONVERGED` operational rows may expire after 24 hours only when durable command and audit evidence exists elsewhere; and
- dead-letter operational rows remain at least 30 days after terminal disposition, while longer legal/support retention belongs in the owning audit policy.

A dedicated service becomes valid only as a complete future transfer of ledger/backlog authority, not as a second competing queue.

## Consequences

- One authority can explain whether the logical effect and player command converged.
- Domain services remain focused on owner-local idempotent writes.
- Isolated workers can scale and fail independently of hot-path tick execution while remaining part of the Game Session authority boundary.
- Durable backlog storage, scans, retention, dashboards, and operator APIs add operational cost.
- A long owner outage preserves pending work and may delay player outcomes rather than inventing false success.

## Alternatives Considered

### Domain-Owned Retry Queues

Rejected because each service sees only its own leg and cannot safely derive whole-effect success or one operator disposition.

### Dedicated Reconciliation Service

Deferred unless the complete authority is transferred. A partial overlay would duplicate intent and lifecycle truth.

### Workflow Engine Per Effect

Rejected for the current high-cardinality gameplay path because it adds another runtime dependency and still cannot replace owner guards or Game Session's semantic result authority.

## Implementation and Proof Obligations

Current recovery proof is partial and does not establish the complete spatial/ambient backlog. Proof must cover coordinator crash, immutable participant set, duplicate acknowledgement, owner outage, retry storms, tenant fairness, worker isolation, pending retention, dead-letter operator actions, original-identity replay, command-outcome mapping, and safe GC only after independent command/audit evidence.

## Reversibility and Revisit Triggers

Workers and storage can move to a dedicated deployable while preserving the schema and authority direction. Revisit when measured backlog scale or Game Session operational coupling justifies transferring the entire reconciliation authority.
