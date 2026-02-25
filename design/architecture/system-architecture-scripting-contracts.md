# FireMUD Scripting & Automation: Cross-Service Contracts

This document defines the **non-negotiable contracts** that make the scripting DSL, Automation & Scripting Service, Game Session tick system, and operator tooling work as a single end-to-end system.

Document conflict resolution order is defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#document-precedence-normative`. This document defines the cross-service invariants layer in that precedence model.

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

- **Entity-scoped events** (`onSpawn`, `onEnterRegion`, `onCommand`, custom events) must treat the unique trigger identity as including at least `tenantId`, `gameInstanceId`, `regionId`, `entityId`, `scriptId`, `eventType`, `scriptPatchVersion`, `scriptEventId`, and `isDryRun`. For gameplay/runtime triggers, `regionEpoch` is required to fence across scoped coordination resets as defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md`.
- **Scheduler events** (`onInterval`, `onTimerExpire`) must use deterministic identities that include the due point plus `regionEpoch`, `scriptPatchVersion`, and `isDryRun` so leader catch-up and retries do not double-fire. For tick-cadence scheduling (for example `onInterval` configured in ticks), the due point must be expressed as `dueTickId` in the canonical tick timeline. For wall-clock timers, the due point must be expressed as absolute `dueAt` (and still include `regionEpoch` to fence across coordination resets).

Callers must reuse the same `scriptEventId` on retries for live ingress. For dry-run/test ingress, server-generated IDs are preferred by default; if caller-supplied IDs are accepted, they must be collision-validated in the dry-run namespace.

Ingress ownership is endpoint-specific and must follow the matrix in `design/architecture/system-architecture-scripting-normative-contract-tables.md#table-1a-event-ingress-scripteventid-ownership-matrix`.

### 5) Metrics Cardinality Guardrails

- `scriptEventId` is for logs, traces, and `script_event_audit` queries.
- `scriptEventId` must not be used as a Prometheus metric label (or any other high-cardinality metric dimension).

### 6) Dry-Run / Test Traffic Safety

Dry-run executions are privileged and must not destabilize production:

- Dry-runs share the same sandbox and loop-safety guards as live traffic.
- Dry-runs do not consume live ScriptQuotaService windows or tenant automation budgets.
- Dry-runs must not contribute to failure-rate circuit breakers that can disable live scripts by default; if dry-run failures are used for safety gating, they must be isolated (separate breaker or explicitly opt-in per environment/tenant).
- Dry-runs must use an idempotency/audit namespace that is distinct from live traffic (for example, include `isDryRun=true` in Trigger Identity) so test calls cannot dedupe, suppress, or overwrite live trigger records.
- Dry-run/test APIs should default to server-generated `scriptEventId` values to avoid cross-client collision and namespace drift. If a caller-supplied `scriptEventId` is accepted, the service must validate namespace rules and reject collisions with a deterministic application error.

### 7) Reload Backpressure Contract

- During `reloadState=RELOADING`, the Automation & Scripting Service must return an explicit application-level backpressure outcome (not a silent drop).
- For low-rate external events, callers may retry with the same `scriptEventId` using a bounded exponential backoff and jitter.
- For timer-derived scheduler events, best-effort timer semantics apply; triggers not admitted during reload are not backfilled unless explicitly covered by a bounded catch-up rule.

### 8) Plugin Version Fencing and Control-Plane Scope

Plugins are executed by the same runtime engine as scripts and must not rely on weaker rollback semantics:

- Plugin enablement and active `pluginVersionId` selection are explicit per `(tenantId, gameInstanceId, pluginId)` and are controlled by operator control-plane APIs (typically via Logging & Admin driving Automation & Scripting registry APIs).
- Plugin triggers must follow the same Trigger Identity rules, including `gameInstanceId` and (for gameplay/runtime triggers) `regionEpoch`. For plugin triggers, `pluginId` and `pluginVersionId` are required identity fields as defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md`.
- Script work items and tick commands produced by plugins must carry `pluginId` and `pluginVersionId` in addition to `scriptPatchVersion`.
  - On execution, Game Session must enforce a **plugin version fence** analogous to the script patch fence:
  - If a command’s embedded `pluginVersionId` does not match the instance’s currently active plugin version for that `pluginId`, Game Session must not execute it.
    - Rejection must be recorded with enough identifiers for diagnosis, and the rejected queue entry must be removed or moved to a bounded dead-letter store so mismatches cannot accumulate unboundedly.

### 9) Rollback Convergence Timeout Contract

- Rollback orchestration must enforce bounded convergence waiting across Automation and Game Session pin-convergence APIs.
- If convergence is not observed before timeout, rollback enters terminal state `ROLLBACK_CONVERGENCE_TIMEOUT`.
- In that state, admission and ticks remain paused until an explicit operator action resumes or aborts rollback.
- The terminal condition must emit:
  - Control-plane event `ScriptRollbackConvergenceTimedOut` produced by Game Session as producer-of-record.
  - Metric `automation_rollback_convergence_timeout_total{tenantId, gameInstanceId, reason}`.
  - Audit-visible admission outcome `rollback_convergence_timeout` for affected scope while pause remains active.

### 10) Instance Rollout Read Model Ownership

- The authoritative writer for `<tenantId, gameInstanceId, scriptPatchVersion>` rollout history is Game Session control-plane writes (`SetPinnedScriptPatchVersion`, `RollbackScriptPatchVersion`) and their committed events.
- Automation & Scripting may project this history for query/read APIs, but projections must be idempotent and replayable from durable control-plane events.
- Read-model records must be keyed by `(tenantId, gameInstanceId, scriptPatchVersion, controlPlaneRequestId)` so retries do not fork history.
- The projection contract must define:
  - Producer of record (`ScriptPatchPinChanged` events).
  - Replay source and retention window.
  - Eventual-consistency SLO for `GetScriptPatchInstanceRolloutStatus` / `ListScriptPatchInstanceRollouts`.

## Related Documents

- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`
- `design/architecture/system-architecture-scripting-control-plane-api.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/microservices/game-session-service/README.md`
