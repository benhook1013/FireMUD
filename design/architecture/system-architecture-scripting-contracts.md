# FireMUD Scripting & Automation: Cross-Service Contracts

This document defines the **non-negotiable contracts** that make the scripting DSL, Automation & Scripting Service, Game Session tick system, and operator tooling work as a single end-to-end system.

When other docs conflict on these points, treat this document as the tie-breaker.

## Contracts

### 1) Tick Queue Ownership (`tick:*`)

- The Game Session Service is the **only** service that writes to `tick:{tenantRegionTag}:*` coordination keys and per-entity tick queues.
- The Automation & Scripting Service never mutates `tick:*` keys directly (including via Redis Lua). It hands off script-generated commands to Game Session via internal gRPC so Game Session can enqueue them using its own Redis Lua registry and tick invariants.

### 2) Script Work Item vs Tick Command Boundary

- A **script work item** is the post-DSL output staged by Automation & Scripting (domain commands + metadata such as `scriptId`, `scriptPatchVersion`, and `scriptEventId`).
- A **tick command** is a unit of work that has been accepted into an entity’s tick queue by Game Session and will be executed under tick locks and replay semantics.

Audit and outcomes must distinguish between:

- “DSL evaluated successfully” vs
- “commands were accepted into the tick system”.

By default, `script_event_audit.finalOutcome=success` must mean “commands were accepted into the tick system”, not merely that the DSL evaluated. The audit record must also be stage-aware (for example `finalStage=TICK_HANDOFF`) as specified in `design/architecture/system-architecture-scripting-observability-contract.md`.

### 3) Version Fencing (Rollback Safety)

To make script patch rollback meaningful:

- Every script work item and tick command must carry the effective `scriptPatchVersion` used to produce it.
- On execution, Game Session must enforce a **version fence**:
  - If a command’s `scriptPatchVersion` does not match the game instance’s currently pinned `scriptPatchVersion`, Game Session must not execute it.
  - Rejection must be recorded with enough identifiers (`tenantId`, `gameInstanceId`/`regionId`, `entityId`, `scriptId`, `scriptEventId`, `scriptPatchVersion`) for operators to diagnose why work was dropped.
- Operational rollback must include a drain/purge step for any queued automation work items and staging entries that cannot satisfy the version fence.

### 4) `scriptEventId` Identity and At-Most-Once Dedupe

`scriptEventId` is the primary idempotency token for “run a handler for this trigger”.

- **Entity-scoped events** (`onSpawn`, `onEnterRegion`, `onCommand`, custom events) must treat the unique trigger identity as including at least `tenantId`, `regionId`, `entityId`, `scriptId`, `eventType`, `scriptPatchVersion`, and `scriptEventId`. If the event is tied to the tick timeline, `regionEpoch` must be included as well.
- **Scheduler events** (`onInterval`, `onTimerExpire`) must use deterministic identities that include the due point (for example `dueTickId` or a due timestamp) plus `regionEpoch` and `scriptPatchVersion` so leader catch-up and retries do not double-fire.

Callers must reuse the same `scriptEventId` on retries.

### 5) Metrics Cardinality Guardrails

- `scriptEventId` is for logs, traces, and `script_event_audit` queries.
- `scriptEventId` must not be used as a Prometheus metric label (or any other high-cardinality metric dimension).

### 6) Dry-Run / Test Traffic Safety

Dry-run executions are privileged and must not destabilize production:

- Dry-runs share the same sandbox and loop-safety guards as live traffic.
- Dry-runs do not consume live ScriptQuotaService windows or tenant automation budgets.
- Dry-runs must not contribute to failure-rate circuit breakers that can disable live scripts by default; if dry-run failures are used for safety gating, they must be isolated (separate breaker or explicitly opt-in per environment/tenant).

### 7) Reload Backpressure Contract

- During `reloadState=RELOADING`, the Automation & Scripting Service must return an explicit application-level backpressure outcome (not a silent drop).
- For low-rate external events, callers may retry with the same `scriptEventId` using a bounded exponential backoff and jitter.
- For timer-derived scheduler events, best-effort timer semantics apply; triggers not admitted during reload are not backfilled unless explicitly covered by a bounded catch-up rule.

## Related Documents

- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`
- `design/architecture/system-architecture-scripting-control-plane-api.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/microservices/game-session-service/README.md`
