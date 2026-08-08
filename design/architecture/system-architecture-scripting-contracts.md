# FireMUD Scripting & Automation: Cross-Service Contracts

This document defines the **non-negotiable contracts** that make the scripting DSL, Automation & Scripting Service, Game Session tick system, and operator tooling work as a single end-to-end system.

Document conflict resolution order is defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#document-precedence-normative`. This document defines the cross-service invariants layer in that precedence model.

## Contracts

### 1) Tick Queue Ownership (`tick:*`)

- The Game Session Service is the **only** service that writes to `tick:{tenantRegionTag}:*` coordination keys and per-entity tick queues.
- The Automation & Scripting Service never mutates `tick:*` keys directly (including via Redis Lua). It hands off script-generated commands to Game Session via internal gRPC so Game Session can enqueue them using its own Redis Lua registry and tick invariants.

### 2) Script Work Item vs Tick Command Boundary

- A **script work item** is the post-DSL output staged by Automation & Scripting (domain commands + metadata such as `scriptId`, `scriptPatchVersion`, and `scriptEventId`). It preserves the immutable source Trigger Identity, including the source `regionId` and `regionEpoch` when applicable; any enqueue or current-routing identity is carried separately and must not overwrite the source trigger fields.
- A **tick command** is a unit of work that has been accepted into an entity’s tick queue by Game Session and will be executed under tick locks and replay semantics.
- **Target-state command handoff:** Every script work item that emits gameplay commands receives one stable `automationDispatchId`, and each emitted command carries a deterministic `commandOrdinal` under that dispatch. The pair is distinct from Trigger Identity and `scriptEventId`: one handler may fan out into multiple commands, and each command is identified by `(automationDispatchId, commandOrdinal)`.
- **Target-state command handoff:** `automationDispatchId` is the single scripting command discriminator used for handoff deduplication, execution-fence reporting, and effect identity. `scriptEventId` alone must never identify a fan-out command.

The current implementation is narrower than this target. Automation persists `automationDispatchId` and command ordinals in its durable handoff records, but the live Game Session enqueue/fence contract does not yet carry the full Trigger Identity or `commandOrdinal` end to end. Until `AS-1.5` and its Game Session handoff dependency are complete in the [automation and scheduler runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status), current live diagnostics and retries use the available `outboxWorkItemId`, `gameSessionCommandId`, and any persisted `automationDispatchId`, together with `script_event_audit`; this fallback does not establish target command-level deduplication or complete fence proof.

Audit and outcomes must distinguish between:

- “DSL evaluated successfully” vs
- “commands were accepted into the tick system”.

By default, live `script_event_audit.finalOutcome=success` must mean “commands were accepted into the tick system”, not merely that the DSL evaluated. The audit record must also be stage-aware (for example `finalStage=TICK_HANDOFF`) as specified in `design/architecture/system-architecture-scripting-observability-contract.md`. Dry-run/test success uses the separate non-committing outcome `finalStage=DRY_RUN_RESULT`, `finalOutcome=dry_run_success`.

### 3) Version Fencing (Rollback Safety)

To make script patch rollback meaningful:

- Every script work item and tick command must carry the effective `scriptPatchVersion` used to produce it.
- On execution, Game Session must enforce a **version fence**:
  - If a command’s `scriptPatchVersion` does not match the game instance’s currently pinned `scriptPatchVersion`, Game Session must not execute it.
  - **Target state:** rejection must retain the complete applicable Trigger Identity (`tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `entityId`, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `scriptEventId`, `isDryRun`, plus plugin/binding and scheduler fields when applicable), together with the immutable `outboxWorkItemId`, `automationDispatchId`, and `commandOrdinal`, so operators can diagnose exactly which fan-out command was dropped. A child rejection must not be reduced to one handler-level disposition. The live fallback records the narrower work-item, command, and available dispatch fields in the current handoff/audit surfaces until `AS-1.5` is complete.
  - Rejected entries must be removed or moved to a bounded dead-letter store with explicit `maxAge`/`maxRows` and alert-backed cleanup cadence.
- Operational rollback must include a drain/purge step for any queued automation work items and staging entries that cannot satisfy the version fence.

### 4) `scriptEventId` Identity and At-Most-Once Dedupe

`scriptEventId` identifies the handler trigger, but it is not the complete idempotency token for “run a handler for this trigger”. Scripting idempotency must use every applicable field in the full Trigger Identity defined in the normative contract tables; `scriptEventId` alone is insufficient.

- **Entity-scoped events** (`onSpawn`, `onEnterRegion`, `onCommand`, custom events) must use every applicable field in the full Trigger Identity, including `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch` when gameplay/runtime-scoped, `entityId`, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `scriptEventId`, and `isDryRun`. Plugin handlers additionally require `pluginId`, `pluginVersionId`, and the signed-bundle `bindingId`. These fields, not `scriptEventId` alone, define the unique trigger identity.
- **Scheduler events** (`onInterval`, `onTimerExpire`) must use every applicable Trigger Identity field plus the due point and `triggerMode` so leader catch-up and retries do not double-fire. For tick-cadence scheduling (for example `onInterval` configured in ticks), the due point must be expressed as `dueTickId` in the canonical tick timeline. For wall-clock timers, the due point must be expressed as absolute `dueAt` and still include `regionEpoch` to fence across coordination resets.
- Scheduler identities and durable timer state must also include `gameInstanceId` and `playableStateScope` for gameplay/runtime schedules. For plugin timers, `pluginId`, `pluginVersionId`, and the binding-scoped `bindingId` are additionally required so multiple bindings, instances, and plugin-version rollbacks cannot alias timer state.
- After event fan-out, each resolved handler is a separate dedupe and audit unit. A plugin handler is uniquely identified by the full applicable Trigger Identity plus `(pluginId, pluginVersionId, bindingId)`; `pluginId` or `pluginVersionId` alone is never sufficient when one plugin version contributes multiple bindings. The same handler-scoped identity governs `script_event_audit`, quota attribution, timer ownership, and binding-scoped cleanup.
- **Tenant-readiness `onLoad` events** intentionally omit `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, and `entityId` because they run before any instance pins the patch. Automation & Scripting generates `scriptEventId` deterministically from the preimage `<tenantId, scriptId, eventSchemaVersion, scriptPatchVersion, eventType=onLoad, isDryRun=false>`. The persisted dedupe/audit Trigger Identity is then `<tenantId, scriptId, eventSchemaVersion, scriptPatchVersion, eventType=onLoad, scriptEventId, isDryRun=false>`.

Callers must reuse the same full applicable Trigger Identity on retries for live ingress, including the same `scriptEventId`. For downstream command-handoff retries, reuse of the complete command identity including `automationDispatchId` and `commandOrdinal` is target-state; the current live fallback reuses the available work-item/command identity and does not claim complete fan-out deduplication. For dry-run/test ingress, server-generated IDs are preferred by default; if caller-supplied IDs are accepted, they must be collision-validated in the dry-run namespace.

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
- For low-rate external events, callers may retry with the same full applicable Trigger Identity, including the same `scriptEventId`, using bounded exponential backoff and jitter:
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
- Plugin triggers must follow the same Trigger Identity rules, including `gameInstanceId` and (for gameplay/runtime triggers) `regionEpoch`. For plugin triggers, `pluginId`, `pluginVersionId`, and the contributing `bindingId` are required identity fields; the binding identity is stable within the signed plugin version and is resolved before handler-scoped audit/dedupe.
- Script work items and tick commands produced by plugins must carry `pluginId`, `pluginVersionId`, and `bindingId` in addition to `scriptPatchVersion` whenever the command came from a binding-scoped plugin handler.
  - On execution, Game Session must enforce a **plugin version fence** analogous to the script patch fence:
  - If a command’s embedded `pluginVersionId` does not match the instance’s currently active plugin version for that `pluginId`, Game Session must not execute it.
    - **Target state:** rejection must be recorded with `automationDispatchId` plus the applicable Trigger Identity, including `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `pluginId`, `pluginVersionId`, `bindingId`, patch, and target fields for diagnosis, and the rejected queue entry must be removed or moved to a bounded dead-letter store with explicit `maxAge`/`maxRows` and alert-backed cleanup cadence. The live fallback uses the narrower plugin/work-item/command fields currently retained by Automation and Game Session; it must not be described as complete Trigger Identity proof. The remaining target gap is tracked under `AS-1.5` in the [automation and scheduler runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

### 9) Rollback Convergence Timeout Contract

- Rollback orchestration must enforce bounded convergence waiting across Automation and Game Session pin-convergence APIs.
- If convergence is not observed before timeout, rollback enters terminal state `ROLLBACK_CONVERGENCE_TIMEOUT`.
- In that state, admission and ticks remain paused until an explicit operator action resumes or aborts rollback.
- The terminal condition must emit:
  - Control-plane event `ScriptRollbackConvergenceTimedOut` produced by Game Session as producer-of-record.
  - Metric `automation_rollback_convergence_timeout_total{scope, operation, reason}`.
  - Event-scope ingress outcome `admitted=false`, `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_ROLLBACK`, and `admissionReason=rollback_convergence_timeout` for the affected scope while pause remains active, recorded in `script_event_ingress_audit` without inventing a handler-scoped `finalOutcome`.
- Rollback-safe pause scope is instance-level: control-plane pause, admission pause, convergence checks, and resume operations must all target the same `(tenantId, gameInstanceId)` scope. Region-only pause operations are operational tools and must not be used as the only barrier for rollback orchestration.

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

- `ReplayDeadLetteredWorkItems` must validate work-item versions against current control-plane state before transition from dead-letter to replayable:
  - `scriptPatchVersion` must match currently pinned patch for the scoped instance.
  - Plugin work items must match currently active `(pluginId, pluginVersionId)` for the scoped instance.
- Ineligible work items must remain dead-lettered and return deterministic application-level mismatch errors; replay must not be best-effort for version-fenced mismatches.

### 13) Output Budget Safety

- A successfully admitted trigger must still be prevented from emitting unbounded work.
- The Automation & Scripting Service must enforce explicit ceilings including `maxCommandsPerRun`, `maxCommandsPerEntityPerTrigger`, and `maxSerializedWorkItemBytes` before durable persistence/handoff.
- Output-budget violations must be recorded as non-success stage-aware outcomes and must not partially persist oversized work items.
- Publish-time validation in Game Design must conservatively reject graphs whose bounded worst-case fan-out exceeds runtime ceilings, using the shared static output cost contract in `design/architecture/system-architecture-scripting-runtime-execution.md#static-output-cost-contract`.

## Related Documents

- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`
- `design/architecture/system-architecture-scripting-control-plane-api.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/microservices/game-session-service/README.md`
