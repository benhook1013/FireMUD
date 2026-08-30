# ADR 0009: Coordination Redis Ownership and Participation Boundary

## Status

Accepted

## Current inventory clarification

The former `automation:tick:*` staging path is retired and not live. The canonical Redis inventory and responsibility matrix list Automation & Scripting's active coordination families as `automation:timer:*` and `script-scheduler:*`, with `automation:queue:*` and the other automation buffers/counters on Cache/Rate-Limit Redis; Game Session owns the gameplay `tick:*` mutation boundary. This records current implementation/catalogue state and does not introduce a new consequential decision or change this ADR's accepted ownership authority.

## Context

Coordination Redis contains correctness-sensitive runtime state (ticks, leases, timers, session bindings, and related coordination metadata). Allowing broad ad hoc writes from many services increases coupling, makes incident recovery harder, and weakens enforceability of key schema and reset semantics.

The architecture already distinguishes ownership from participation, but the boundary was not consolidated into one canonical decision document.

## Decision

Coordination Redis ownership is explicit and narrow:

- Game Session Service owns gameplay coordination keyspace and schema (for example `tick:*`, `timer:*`, `retry:*`, `session:game:*`, and lease-related prefixes).
- Account Service owns authentication allowlist/session-auth prefixes (`session:auth:*`) and their lifecycle semantics.
- Automation & Scripting Service owns automation coordination and cache families with an explicit split:
  - Coordination Redis: active `automation:timer:*` and `script-scheduler:*` keyspaces for automation scheduling/execution. The former `automation:tick:*` staging path is retired, not live, and is not an active owned prefix.
  - Cache/Rate-Limit Redis: `automation:queue:*`, `automation:quota:*`, `automation:tenant-budget:*`, and `automation:test:capacity:*` best-effort buffers/counters.
- Non-owner services may participate only through approved shared helpers and documented prefixes/contracts.

Owner-managed bridge contracts are the only approved exception to “write only your own keys”:

- Game Session owns the session-to-region bridge contract:
  - `session:game:*` is session-authoritative.
  - `tick:{tenantRegionTag}:session-binding:*` is region-authoritative.
  - Non-owner services do not mutate either family directly; Game Session exposes approved helpers/APIs for the two-phase binding flow.
- Game Session owns the gameplay-equivalent automation enqueue contract:
  - Automation & Scripting may declare due work and call the enqueue API with a durable `automationDispatchId`.
  - Only Game Session may translate that request into `tick:{tenantRegionTag}:queue:*` mutations.
  - Durable dedupe for the automation handoff is part of the contract, not an implementation detail left to Redis queue contents.

Non-owner services must not:

- Introduce new coordination prefixes.
- Change TTL or key-shape semantics for owner-managed prefixes.
- Perform ad hoc direct writes to owner-managed coordination keys outside documented helper contracts.

Any new participation pattern requires a documented contract update and owner review before implementation.

## Consequences

- Service docs and reviews should treat direct cross-service coordination writes as design violations unless explicitly approved by owner contracts.
- Coordination behavior remains reviewable and operable because ownership and mutation authority are centralized.
- Incident/reset workflows remain tractable because key ownership is deterministic.

## References

- `design/architecture/system-architecture-redis.md`
- `design/architecture/service-responsibility-matrix.md`
- `design/architecture/microservices/game-session-service/README.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/microservices/entity-management-service/README.md`
