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

Live `script_event_audit.finalOutcome=handoff_accepted` means every required child dispatch was durably accepted by Game Session, not that the DSL merely evaluated or gameplay applied. A valid handler emitting no commands uses `finalStage=DSL_EVAL`, `finalOutcome=completed_no_commands`. Dry-run/test success remains `finalStage=DRY_RUN_RESULT`, `finalOutcome=dry_run_success`. Per-command gameplay outcomes remain authoritative in Game Session and are linked through each `automationDispatchId` as specified in [ADR 0064](./decisions/adr-0064-stage-qualified-script-outcomes.md).

### 3) Version Fencing (Rollback Safety)

To make script patch rollback meaningful:

- Every script work item and tick command must carry the effective `scriptPatchVersion` used to produce it.
- On execution, Game Session must enforce a **version fence**:
  - If a command’s `scriptPatchVersion` does not match the game instance’s currently pinned `scriptPatchVersion`, Game Session must not execute it.
  - Rejection must be recorded with enough identifiers (`tenantId`, `gameInstanceId`/`regionId`, `entityId`, `scriptId`, `scriptEventId`, `scriptPatchVersion`) for operators to diagnose why work was dropped.
  - Rejected entries must be removed or moved to a bounded dead-letter store with explicit `maxAge`/`maxRows` and alert-backed cleanup cadence.
- Operational rollback must include a drain/purge step for any queued automation work items and staging entries that cannot satisfy the version fence.

### 4) `scriptEventId` Identity and At-Most-Once Dedupe

`scriptEventId` is the primary idempotency token for “run a handler for this trigger”.

- **Entity-scoped events** (`onSpawn`, `onEnterRegion`, `onCommand`, custom events) must treat the unique trigger identity as including at least `tenantId`, `gameInstanceId`, `regionId`, `entityId`, `scriptId`, `eventType`, `scriptPatchVersion`, `scriptEventId`, and `isDryRun`. For gameplay/runtime triggers, `regionEpoch` is required to fence across scoped coordination resets as defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md`.
- **Scheduler events** (`onInterval`, `onTimerExpire`) must use deterministic identities that include the due point plus `regionEpoch`, `scriptPatchVersion`, and `isDryRun` so leader catch-up and retries do not double-fire. For tick-cadence scheduling (for example `onInterval` configured in ticks), the due point must be expressed as `dueTickId` in the canonical tick timeline. For wall-clock timers, the due point must be expressed as absolute `dueAt` (and still include `regionEpoch` to fence across coordination resets).
- Scheduler identities and durable timer state must also include `gameInstanceId`. For plugin timers, `pluginId` and `pluginVersionId` are additionally required so multi-instance and plugin-version rollbacks cannot alias timer state.
- **Tenant-readiness `onLoad` events** are keyed by `<tenantId, scriptId, scriptPatchVersion, eventType=onLoad, scriptEventId, isDryRun=false>` and intentionally omit `gameInstanceId`, `regionId`, `regionEpoch`, and `entityId` because they run before any instance pins the patch. Automation & Scripting owns deterministic `scriptEventId` generation for this lifecycle path.

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
- Dry-run/test ingress must enforce explicit authorization scopes/roles (for example `automation.dryrun.execute`) and must record principal identity in audit fields for privileged use.
- Dry-run/test ingress must apply deterministic principal and tenant rate-limit outcomes (for example `DRY_RUN_RATE_LIMITED`) as application-level errors rather than transport failures.
- If dry-run authorization context is missing or invalid, the call must fail with deterministic application error (for example `DRY_RUN_UNAUTHORIZED`) and must not execute DSL evaluation.
- Dry-run/test execution capacity must be isolated from live traffic with separate worker pools, reserved worker shares, or equivalent scheduling partitions so privileged tooling cannot consume the last available live automation capacity.

### 7) Reload Backpressure Contract

- During `reloadState=RELOADING`, the Automation & Scripting Service must return an explicit application-level backpressure outcome (not a silent drop).
- For low-rate external events, callers may retry with the same `scriptEventId` using bounded exponential backoff and jitter:
  - `maxAttempts` must be finite and documented per client.
  - `maxElapsedMs` must be finite and documented per client.
  - Jitter must be non-zero to avoid synchronized retry storms.
- Backpressure responses must include a server hint (`retryAfterMs`) so callers can align retries with expected reload/rollback progress.
- For timer-derived scheduler events, best-effort timer semantics apply; triggers not admitted during reload are not backfilled unless explicitly covered by a bounded catch-up rule.

### 7a) Runtime Scope Isolation

- Tenant-scoped patch readiness (`READY` / `FAILED`) is an eligibility signal, not a runtime execution scope.
- Admission pause, reload backpressure, timer ownership, rollback convergence, and plugin lifecycle actions must preserve instance isolation at minimum scope `(tenantId, gameInstanceId)`.
- A deployment may intentionally couple all instances in a tenant only if that invariant is explicitly documented and enforced end-to-end; otherwise one instance's patch transition must not stall unrelated instances.

### 8) Plugin Version Fencing and Control-Plane Scope

Plugins are executed by the same runtime engine as scripts and must not rely on weaker rollback semantics:

- Plugin enablement and active `pluginVersionId` selection are explicit per `(tenantId, gameInstanceId, pluginId)` and are controlled by operator control-plane APIs (typically via Logging & Admin driving Automation & Scripting registry APIs).
- Plugin triggers must follow the same Trigger Identity rules, including `gameInstanceId` and (for gameplay/runtime triggers) `regionEpoch`. For plugin triggers, `pluginId` and `pluginVersionId` are required identity fields as defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md`.
- Script work items and tick commands produced by plugins must carry `pluginId` and `pluginVersionId` in addition to `scriptPatchVersion`.
  - On execution, Game Session must enforce a **plugin version fence** analogous to the script patch fence:
  - If a command’s embedded `pluginVersionId` does not match the instance’s currently active plugin version for that `pluginId`, Game Session must not execute it.
    - Rejection must be recorded with enough identifiers for diagnosis, and the rejected queue entry must be removed or moved to a bounded dead-letter store with explicit `maxAge`/`maxRows` and alert-backed cleanup cadence.

### 9) Rollback Convergence Timeout Contract

- Routine rollback prepares the target, pauses Automation admission for the instance, atomically advances the authoritative `scriptPinEpoch`, and lets ordinary gameplay ticks continue.
- Every script-derived trigger, work item, command, timer, remote follow-up, staged effect, retry, and final gameplay mutation carries and checks the exact patch and pin epoch. Displaced work cannot affect gameplay after repin.
- Cancel, purge, convergence, and drain remain bounded asynchronous operational cleanup; they are not the mutation-correctness barrier and do not delay unrelated gameplay.
- If Automation cannot converge before the bounded timeout, Automation remains fail-closed for the instance while gameplay continues. The terminal condition must emit:
  - Control-plane event `ScriptRollbackConvergenceTimedOut` produced by Game Session as producer-of-record.
  - Metric `automation_rollback_convergence_timeout_total{scope, operation, reason}`.
  - Audit-visible admission outcome `rollback_convergence_timeout` for affected scope while pause remains active.
- A full gameplay tick pause is exceptional and requires a declared effect family or migration that cannot enforce the final `scriptPinEpoch` fence. It pauses the smallest complete scope and proves active-effect quiescence rather than serving as the routine rollback mechanism.

### 10) Instance Rollout Read Model Ownership

- The authoritative writer for `<tenantId, gameInstanceId, scriptPatchVersion>` rollout history is Game Session control-plane writes (`SetPinnedScriptPatchVersion`, `RollbackScriptPatchVersion`) and their committed events.
- Automation & Scripting may project this history for query/read APIs, but projections must be idempotent and replayable from durable control-plane events.
- Read-model records must be keyed by `(tenantId, gameInstanceId, scriptPatchVersion, controlPlaneRequestId)` so retries do not fork history.
- The projection contract must define:
  - Producer of record (`ScriptPatchPinChanged` events).
  - Replay source and retention window.
  - Eventual-consistency SLO for `GetScriptPatchInstanceRolloutStatus` / `ListScriptPatchInstanceRollouts`.

### 11) Pin-State Degraded Override Governance

- Admission decisions must fail closed with `pin_state_unavailable` when bounded-staleness pin refresh cannot reach an authoritative source.
- Any degraded override that allows admission while authoritative pin state is unavailable must be:
  - explicit and time-bounded,
  - scoped at least to `(tenantId, gameInstanceId)`,
  - authorized and audited with `controlPlaneRequestId`, `actor`, and `reason`,
  - automatically reverted to fail-closed behavior at TTL expiry.

### 12) Dead-Letter Replay Version-Fence Safety

- Dead-letter recovery is stage-aware. Evaluation-stage retry reuses the original Trigger Identity, frozen input manifest, and exact immutable graph; post-evaluation recovery resumes the stored output and unfinished child-dispatch ledger without invoking the DSL.
- Recovery requires exact current matches for `scriptPatchVersion`, `scriptPinEpoch`, plugin identity/version when applicable, runtime region and `regionEpoch`, and the admitted routing bundle. Matching patch text under a later epoch is ineligible.
- The initial mutation accepts bounded explicit work-item IDs and returns one deterministic outcome per ID. Aggregate counts or broad filters do not replace per-row evidence.
- Ineligible or incomplete rows remain dead-lettered. Purge is a separate idempotent, authorized, audited operation, and direct SQL repair is unsupported.

### 13) Output Budget Safety

- A successfully admitted trigger must still be prevented from emitting unbounded work.
- The Automation & Scripting Service must enforce explicit ceilings including `maxCommandsPerRun`, `maxCommandsPerEntityPerTrigger`, and `maxSerializedWorkItemBytes` before durable persistence/handoff.
- Runtime enforcement is incremental and stops before constructing or serializing the next over-limit element. Output-budget violations are handler-scoped `DSL_EVAL` outcomes and must not partially persist any generated commands for that handler.
- Pre-handler envelope violations remain event-ingress outcomes; they do not summarize later generated output.
- Publish-time validation in Game Design conservatively rejects graphs whose bounded worst-case fan-out exceeds runtime ceilings, using the versioned/digested shared component-cost contract in [ADR 0088](decisions/adr-0088-static-and-incremental-script-output-bounds.md).

## Related Documents

- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`
- `design/architecture/system-architecture-scripting-control-plane-api.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/microservices/game-session-service/README.md`
