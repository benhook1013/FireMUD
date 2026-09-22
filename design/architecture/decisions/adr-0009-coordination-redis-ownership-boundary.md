# ADR 0009: Coordination Redis Ownership and Participation Boundary

## Status

Accepted

## Current inventory clarification

The former `automation:tick:*` staging path is retired and is not a current Redis family. The current Automation implementation persists schedule, timer, and trigger-instance authority in PostgreSQL and implements only the documented queue, quota, budget, and readiness Redis projections; `AutomationRedisKeys` does not define the target scheduler/checkpoint families. The canonical target inventory reserves `automation:timer:*` and `script-scheduler:*` as Automation-owned, reset-tolerant Coordination Redis projections rebuilt from durable state, but those families are not implemented/current runtime guarantees. Game Session owns the gameplay `tick:*` mutation boundary. This records implementation and catalogue status; it preserves this ADR's accepted ownership boundary and does not silently accept a consequential replacement of its former staging-path wording.

The Account-owned `session:connect-token:*` issuance-result projection/cache is an authority reconciliation, not a new participation decision. Accepted [ADR 0029](./adr-0029-single-use-gameplay-connect-token-carriage.md) explicitly names this Account-owned cache in its reviewed connect-token contract (human review completed 2026-07-19, revised, review source `EDGE-04`). The Account ownership list below therefore makes that already accepted boundary explicit alongside `session:auth:*`; it does not broaden non-owner participation or supersede ADR 0029.

## Context

Coordination Redis contains correctness-sensitive runtime state (ticks, leases, timers, session bindings, and related coordination metadata). Allowing broad ad hoc writes from many services increases coupling, makes incident recovery harder, and weakens enforceability of key schema and reset semantics.

The architecture already distinguishes ownership from participation, but the boundary was not consolidated into one canonical decision document.

## Decision

Coordination Redis ownership is explicit and narrow:

- Game Session Service owns gameplay coordination keyspace and schema (for example `tick:*`, `timer:*`, `retry:*`, `session:game:*`, and lease-related prefixes).
- Account Service owns the exact authentication/session-auth families recorded by the Redis hub: `session:auth:account:*`, `session:auth:tenant:*`, `session:auth:token:*`, and `session:auth:generation:*`, together with their lifecycle semantics. It also owns the separate exact-input `session:connect-token:tenant:<tenantId>:account:<accountId>:scope:<scopeHash>:request:<requestHash>` issuance-result/idempotency family defined by [ADR 0029](./adr-0029-single-use-gameplay-connect-token-carriage.md). No wildcard `session:auth:*` ACL or authority grant is implied.
- Automation & Scripting Service owns automation coordination and cache families with an explicit current-versus-target split:
  - Coordination Redis: it owns the target scheduler/checkpoint boundary represented by `automation:timer:*` and `script-scheduler:*` once implemented. These are reset-tolerant projections rebuilt from durable schedules, trigger-instance rows, and heartbeat progress; durable schedules and trigger-instance de-duplication remain PostgreSQL authority. They are target-state names, not current runtime guarantees. The former `automation:tick:*` staging path is retired, not live, and is not an active owned prefix.
  - Cache/Rate-Limit Redis: `automation:queue:*` discovery pointers, `automation:quota:*`, `automation:tenant-budget:*`, `automation:test:quota:*`, `automation:test:capacity:*`, and `automation:readiness:capacity:*` best-effort buffers/counters and owner-token leases; queue pointers are disposable indexes, not work truth. The test and readiness families remain unsupported for target adoption until each is centrally registered and has slot-safe keying. These remain distinct from the target Coordination Redis `automation:timer:*` and `script-scheduler:*` projections.
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
