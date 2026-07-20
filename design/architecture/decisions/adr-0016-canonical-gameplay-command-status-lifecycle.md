# ADR 0016: Canonical Gameplay Command Status Lifecycle

## Status

Accepted

## Decision Record

- Decision date: 2026-07-18
- Primary capability: `GR-1.2` Tick scheduling, execution, and deterministic command resolution
- Affected capabilities: `SF-2.3`, `GR-1.4`, `AA-2.2`, `PO-4.2`
- Decision owner: FireMUD human product and architecture owner
- Consultation: human-led adversarial review of `CMD-STATUS-01`

## Context

FireMUD already ships `GetGameplayCommandStatus`, durable command records, and recovery of some commands lost before Redis staging. The live status model also carries extensive routing, automation, script, plugin, and remote-execution metadata. However, it overloads progress, execution outcome, and gameplay result in a narrower state vocabulary and does not expose all of the target acknowledgement, ingress, and tick-binding fields.

The previous target described a separate, fuller `GetCommandStatus` API. Introducing that name alongside the existing API would create two apparent authorities and an avoidable migration surface. The decision must instead make command acceptance, retry identity, progress, and terminal results unambiguous while preserving the useful live metadata.

## Decision

FireMUD evolves `GetGameplayCommandStatus` in place as the single canonical authoritative gameplay-command status API. It does not introduce a parallel `GetCommandStatus` authority.

### Stable Identity and Acceptance

- Every logical command has a stable `commandId` before the first backend retry boundary.
- A capable client may generate the identity. For line-oriented or Telnet sessions, the first trusted Game Session/session-front-end ingress assigns and retains it before forwarding or retrying. A human player does not type or manage it.
- Reuse of the same identity returns or advances the same logical command record; a new identity represents a new logical command.
- Ordinary interactive gameplay commands default to `ACCEPTED_VOLATILE`. The durable status record guarantees deduplication and explicit outcome convergence, but execution may still be lost before durable staging.
- `ACCEPTED_DURABLE` is available only to a feature with an explicit durable-intake and safe-replay contract. Stale movement, combat, or similar interactive intent is not automatically replayed merely because it was accepted.

### Orthogonal Status Dimensions

The canonical durable response separates:

- `ackLevel`: `ACCEPTED_VOLATILE` or `ACCEPTED_DURABLE`;
- `ingressStatus`: `RECEIVED`, `ENQUEUED`, `BOUND_TO_BATCH`, or `TERMINAL`;
- `executionOutcome`: nullable until terminal, then `APPLIED`, `ABANDONED`, or `LOST_BEFORE_STAGING`; and
- `gameplayResult`: nullable until known, then the shared minimum vocabulary `SUCCESS`, `PARTIAL`, `FAILED`, `TIMEOUT`, or `NOT_APPLIED`.

The durable status also exposes `tickBatchId`, bound `regionId`, `regionEpoch`, and `tickId` when present, plus `updatedAt`. Rich routing, automation, script, plugin, remote-leg, and diagnostic metadata remains supported as extensions around this lifecycle rather than as competing status authority.

Existing values such as `STAGED`, `DRAINED`, and `RETRY_QUEUED` map to lifecycle progress. Values such as `PURGED` map to a terminal outcome plus a structured reason. Migration must not preserve multiple ambiguous state machines as coequal contracts.

### Authority and Convergence

- `GetGameplayCommandStatus(tenantId, gameInstanceId, commandId)` reads the authoritative durable record. Redis coordination state is not part of the lookup authority.
- Optional outcome events or streams may reduce observation latency, but they are advisory projections of the same lifecycle.
- Every accepted command converges to an explicit terminal result. An `ACCEPTED_VOLATILE` command lost before durable batch binding becomes `LOST_BEFORE_STAGING` with `NOT_APPLIED` rather than remaining indefinitely pending.
- `executionOutcome` and `gameplayResult` remain distinct. For example, a multi-leg command may be `APPLIED` with a `PARTIAL` gameplay result.

## Consequences

- FireMUD gains one query authority and one lifecycle vocabulary without duplicating the already rich live API.
- Players and upstream services can distinguish acknowledgement, processing progress, execution convergence, and gameplay result.
- Volatile acceptance remains honest and avoids unsafe replay of stale interactive intent, while feature-specific durable replay remains possible.
- The proto, SQL shape, recovery logic, producers, consumers, tests, and operational views require coordinated migration to the orthogonal fields.
- Existing lifecycle values need an explicit migration and compatibility plan during direct pre-v1 convergence; they cannot simply be renamed where their semantics differ.
- Operators must monitor and test terminal convergence, particularly reset and tail-loss paths, rather than treating successful ingress as execution success.

## Alternatives Considered

### Add a Parallel `GetCommandStatus` API

Keep `GetGameplayCommandStatus` and introduce the target API separately. This makes migration superficially explicit but duplicates authority, invites semantic drift, and forces callers to choose between overlapping status surfaces. It is rejected.

### Canonicalize the Current Single-State Vocabulary

Keep the live status values as the complete contract. This reduces immediate schema and API work, but continues to conflate queue progress, execution terminality, and player-facing result. It cannot represent volatile loss, partial multi-leg results, and tick binding cleanly.

### Make Every Accepted Command Durable and Replayable

Persist and re-drive all accepted intent. This provides stronger delivery semantics but can execute stale movement or combat after the player context has changed, and it adds durable intake and replay cost to every interactive command. Durable acceptance remains opt-in by feature instead.

### Use Redis as the Status Authority

Store and query the status lifecycle from Redis coordination state. This reduces the apparent durable-write surface, but Redis is reset and loss-prone coordination state and cannot provide the authoritative recovery or audit boundary. PostgreSQL remains the durable command-status authority.

## Implementation and Proof Obligations

- Evolve the existing proto and durable persistence to expose the canonical fields and migrate current state values without creating a second authority.
- Assign and retain stable idempotency identity before any retrying hop and prove same-ID retry deduplication across failover.
- Bind staged commands durably to their tick batch and tick coordinates.
- Prove reset, tail-loss, and startup recovery drive every accepted record to a valid terminal outcome, including `LOST_BEFORE_STAGING`.
- Prove status lookup remains authoritative when optional events are delayed, duplicated, or absent.
- Test representative local success, local failure, cross-region partial success, timeout, volatile pre-staging loss, and explicitly durable replay behavior.

## Reversibility and Revisit Triggers

The lifecycle fields can be extended without replacing the API. Revisit the default acknowledgement level only if measured product requirements justify durable replay for a defined command class and that class has safe stale-intent semantics. Revisit the API boundary only if gameplay-command status moves to a new service authority; a naming preference alone is not sufficient reason to add a parallel surface.

## Required Documentation Alignment

The following sources must remain aligned with this decision:

- `design/architecture/system-architecture-tick-execution-flows.md`
- `design/architecture/system-architecture-ticks.md`
- `design/architecture/system-architecture-tick-failures-and-operations.md`
- `design/architecture/microservices/game-session-service/api-contracts.md`
