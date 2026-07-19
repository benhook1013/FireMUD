# ADR 0056: One Hot-Path Fan-Out Owner

## Status

Accepted

## Decision Record

- Decision date: 2026-07-19
- Decision key: `HOTPATH-01`
- Primary capability: `GR-1.2` Tick timelines, action fairness, queues, and scheduling
- Affected capabilities: `GR-2.2`, `GR-3.2`, `SF-2.3`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `HOTPATH-01`, including independent validation and strongest-alternative passes

## Context

Service count is an imperfect latency proxy: repeated serial calls to one service can be worse than two parallel calls. Latency budgets alone are also insufficient because each feature can justify incremental fan-out until recovery states and joint availability become unmanageable. The previous rule was ambiguous about transitive calls and needed a special three-service movement exception.

## Decision

Each synchronous gameplay operation has one ingress/dispatch step and exactly one designated fan-out owner. The dispatch hop to that owner is not an authoritative participant. The fan-out owner may synchronously call at most two authoritative downstream domain participants across the complete transitive critical path. Those participants must not recursively create additional synchronous fan-out.

Examples:

- `LOOK`: Game Session dispatches to Game Logic; Game Logic composes World and Entity.
- `MOVE`: Game Session dispatches the fenced operation; Game Logic coordinates World and Entity.
- inventory commands: Game Logic coordinates Entity, while unrelated projections and supplemental audit delivery are asynchronous.
- combat: Game Logic plus the required authoritative state owner; achievements, analytics, transcripts, and remote consequences are asynchronous.
- chat: Social executes delivery using its local sufficiently fresh moderation snapshot; Logging and Admin is not called per message.

Movement and LOOK therefore fit the ordinary rule and need no special numeric exception.

Service count is only the structural ceiling. Every operation also documents and measures every RPC stage, repeated call, retry, timeout, p95/p99 latency budget, fallback, and fail-closed behavior. Independent reads should be parallel where safe. A third authoritative participant requires a new architecture decision with measured evidence that a read model, coarser API, projection, or asynchronous effect cannot satisfy the invariant.

Read models and caches may improve steady state but do not exempt authoritative recomputation from the same hot-path budget. Correctness participants unavailable within budget cause an explicit failure/pending outcome and the canonical reconciliation path, never partial success.

## Consequences

- Nested calls cannot conceal a deep dependency tree.
- LOOK and movement share one comprehensible orchestration shape.
- The hard participant ceiling limits failure combinations while latency measurement catches repeated or serial calls.
- Some immediate enrichments move to projections or durable asynchronous delivery.
- The fan-out owner needs coarse APIs and explicit ownership of timeout/result aggregation.

## Alternatives Considered

### Latency Budgets Without a Numeric Ceiling

Rejected because local performance claims permit incremental graph growth and combinatorial recovery states.

### One Downstream Participant Only

Rejected because it would hide the same fan-out behind oversized services or prevent legitimate World-plus-Entity composition.

### Broad Synchronous Orchestration

Rejected because tick progress would inherit the latency and availability of every participating domain.

## Implementation and Proof Obligations

Every current command family must identify its dispatch step, fan-out owner, authoritative participants, call stages, timeout/fallback, and asynchronous consequences. Proof must cover transitive fan-out, nested-participant rejection, repeated calls and retries, latency budgets, participant outage, fail-closed movement, LOOK recomputation, and no fourth authority hidden below a participant.

## Reversibility and Revisit Triggers

Individual operations can change fan-out owner or replace participants with projections. Revisit the ceiling only with measured latency, availability, and recovery evidence for a command class that cannot preserve its invariant under two participants.
