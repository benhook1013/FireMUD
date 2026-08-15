# FireMUD Scripting & Automation: Cross-Service Contracts

This document defines the **non-negotiable contracts** that make the scripting DSL, Automation & Scripting Service, Game Session tick system, and operator tooling work as a single end-to-end system.

Document conflict resolution order is defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#document-precedence-normative`. This document defines the cross-service invariants layer in that precedence model.

The accepted script-transition decisions applied by this contract are [ADR 0103](./decisions/adr-0103-single-authority-script-pins-with-exact-version-execution.md), [ADR 0106](./decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md), [ADR 0107](./decisions/adr-0107-stage-aware-script-dead-letter-recovery.md), [ADR 0108](./decisions/adr-0108-no-degraded-script-admission-without-authoritative-pin.md), [ADR 0109](./decisions/adr-0109-game-session-owned-script-rollout-history.md), [ADR 0110](./decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md), and [ADR 0111](./decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md). Their implementation and proof gaps remain explicit below.

## Implementation Status

The current evaluator accepts exactly one emitted command per work item. A result with more than one command is rejected before persistence or handoff with `finalStage=DSL_EVAL`, `finalOutcome=sandbox_error`, and bounded `finalReason=command_count_exceeded`. Multi-command handoff using the complete Command-Handoff Identity remains target-only; `(automationDispatchId, commandOrdinal)` is only its final pair and is not a standalone identity. The contracts below remain the normative target boundary; this status does not narrow that target.

## Contracts

### 1) Tick Queue Ownership (`tick:*`)

- The Game Session Service is the **only** service that writes to `tick:{tenantRegionTag}:*` coordination keys and per-entity tick queues.
- The Automation & Scripting Service never mutates `tick:*` keys directly (including via Redis Lua). It hands off script-generated commands to Game Session via internal gRPC so Game Session can enqueue them using its own Redis Lua registry and tick invariants.

### 2) Script Work Item vs Tick Command Boundary

- A **script work item** is the post-DSL output staged by Automation & Scripting (domain commands + metadata such as `scriptId`, `scriptPatchVersion`, and `scriptEventId`). It preserves the immutable source Trigger Identity, including the source `regionId` and `regionEpoch` when applicable; any enqueue or current-routing identity is carried separately and must not overwrite the source trigger fields.
- A **tick command** is a unit of work that has been accepted into an entity’s tick queue by Game Session and will be executed under tick locks and replay semantics.
- **Target-state command handoff:** Every script work item that emits gameplay commands receives one stable `automationDispatchId`, and each emitted command carries a deterministic `commandOrdinal` under that dispatch. The pair is only the final discriminator within the complete Command-Handoff Identity; one handler may fan out into multiple commands, and a pair without its parent/scope context is not a command identity.
- **Target-state command handoff:** The complete Command-Handoff Identity is the canonical identity for evaluated child-dispatch handoff deduplication, execution-fence reporting, replay selection, and correlation. It is not the downstream effect guard: authoritative effect idempotency uses the root `EffectId`, typed operation, exact target aggregate, and stored/compared immutable request digest, with conflicts failing closed. The `(automationDispatchId, commandOrdinal)` pair is only a display suffix and is insufficient by itself; `automationDispatchId` identifies a dispatch group, and `scriptEventId` alone must never identify a fan-out command. See [Implementation Status](#implementation-status) for the current evaluator boundary.

Target-state handoff linkage uses the complete Command-Handoff Identity: complete source/target runtime scope, `automationDispatchId`, and `commandOrdinal`. The current implementation is narrower than this target: because Trigger Identity and `commandOrdinal` are not carried end to end, current live linkage and retry diagnostics use `outboxWorkItemId`, `gameSessionCommandId`, available `automationDispatchId`, and `script_event_audit`. This fallback is not complete command-level deduplication or replay evidence. The remaining gap is tracked under `AS-1.5` and its Game Session handoff dependency in the [automation and scheduler runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

Audit and outcomes must distinguish between:

- “DSL evaluated successfully” vs
- “commands were accepted into the tick system”.

Live `script_event_audit.finalOutcome=handoff_accepted` means every required child dispatch was durably accepted by Game Session, not that the DSL merely evaluated or gameplay applied. A valid handler emitting no commands uses `finalStage=DSL_EVAL`, `finalOutcome=completed_no_commands`. Dry-run/test success remains `finalStage=DRY_RUN_RESULT`, `finalOutcome=dry_run_success`. Per-command gameplay outcomes remain authoritative in Game Session and are linked through the complete Command-Handoff Identity, including source/target scope, `automationDispatchId`, and `commandOrdinal`, as specified in [ADR 0064](./decisions/adr-0064-stage-qualified-script-outcomes.md).

### 3) Version Fencing (Rollback Safety)

To make script patch rollback meaningful:

- Every script work item and tick command must carry the effective `scriptPatchVersion` used to produce it.
- Every gameplay/runtime-derived trigger, durable work item, schedule/timer firing, emitted command, remote follow-up, staged tick effect, retry, replay, and audit record must also carry the exact `scriptPinEpoch` captured from Game Session for the instance. Tenant-readiness `onLoad` is the explicit exception because it runs before an instance pin exists. The authoritative selection is the tuple `(scriptPatchVersion, scriptPinEpoch)`, not the version string alone.
- Game Session is the sole durable authority for `(scriptPatchVersion, scriptPinEpoch)` per `(tenantId, gameInstanceId)`. It advances `scriptPinEpoch` for every selection change, including rollback or repinning to a previously used version. Automation readiness, caches, projections, and local active-version observations are non-authoritative.
- On execution, Game Session must enforce a **version fence**:
  - If a command’s `scriptPatchVersion` does not match the game instance’s currently pinned `scriptPatchVersion`, Game Session must not execute it.
  - If a command’s `scriptPinEpoch` does not match the game instance’s current epoch, Game Session must not execute it, even when the patch version is identical.
  - **Target state:** rejection must retain the complete applicable Trigger Identity (`tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `entityId`, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `scriptPinEpoch`, `scriptEventId`, `isDryRun`, plus plugin/binding and scheduler fields when applicable), together with the complete Command-Handoff Identity, including `outboxWorkItemId` as parent correlation and optional distinct target fields (`targetGameInstanceId`, `targetPlayableStateScope`, `targetRegionId`, `targetRegionEpoch`), so operators can diagnose exactly which fan-out command was dropped. A child rejection must not be reduced to one handler-level disposition. The live fallback records the narrower work-item, command, and available dispatch fields in the current handoff/audit surfaces until `AS-1.5` is complete.
  - Rejected entries must be removed or moved to a bounded dead-letter store with explicit `maxAge`/`maxRows` and alert-backed cleanup cadence.
- Rollback may cancel or purge displaced work asynchronously for bounded resource use, but cleanup is not the correctness barrier. The epoch fence at each persistence, handoff, staging, replay, and final effect boundary is authoritative; ordinary player commands and ticks continue unless an explicitly declared unfenced effect requires an exceptional pause.
- For instance-scoped gameplay/runtime ingress, Automation must obtain a bounded-fresh observation of the exact Game Session `(scriptPatchVersion, scriptPinEpoch)` tuple, refreshing its local projection from the authoritative read when required. It must persist both fields with the normalized Trigger Identity and event-scope claim in one Automation-owned local durable transaction before evaluation; a same-version different-epoch claim is distinct and an older epoch cannot be treated as current. This is the ADR 0108 bounded-fresh projection model, not a new globally atomic refresh-and-admit RPC. Tenant-readiness `onLoad` remains the explicit pre-pin exception.

### 4) `scriptEventId` Identity and At-Most-Once Dedupe

`scriptEventId` identifies the handler trigger, but it is not the complete idempotency token for “run a handler for this trigger”. Scripting idempotency must use every applicable field in the full Trigger Identity defined in the normative contract tables; `scriptEventId` alone is insufficient.

- **Entity-scoped events** (`onSpawn`, `onEnterRegion`, `onCommand`, custom events) must use every applicable field in the full Trigger Identity, including `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch` when gameplay/runtime-scoped, `entityId`, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `scriptEventId`, and `isDryRun`. Plugin handlers additionally require `pluginId`, `pluginVersionId`, and the signed-bundle `bindingId`. These fields, not `scriptEventId` alone, define the unique trigger identity.
- For custom or service-specific events, `sourceService` is derived from the authenticated producer/workload identity, participates in the event-scope dedupe identity, and is persisted unchanged in the ingress and handler audit records. It is not a caller-selected identity field; the canonical derivation and applicability rules are owned by the normative contract tables.
- **Scheduler events** (`onInterval`, `onTimerExpire`) must use every applicable Trigger Identity field plus the due point and `triggerMode` so leader catch-up and retries do not double-fire. For tick-cadence scheduling (for example `onInterval` configured in ticks), the due point must be expressed as `dueTickId` in the canonical tick timeline. For wall-clock timers, the due point must be expressed as absolute `dueAt` and still include `regionEpoch` to fence across coordination resets.
- Scheduler identities and durable timer state must also include `gameInstanceId` and `playableStateScope` for gameplay/runtime schedules. For plugin timers, `pluginId`, `pluginVersionId`, and the binding-scoped `bindingId` are additionally required so multiple bindings, instances, and plugin-version rollbacks cannot alias timer state.
- After event fan-out, each resolved handler is a separate dedupe and audit unit. A plugin handler is uniquely identified by the full applicable Trigger Identity plus `(pluginId, pluginVersionId, bindingId)`; `pluginId` or `pluginVersionId` alone is never sufficient when one plugin version contributes multiple bindings. The same handler-scoped identity governs `script_event_audit`, quota attribution, timer ownership, and binding-scoped cleanup.
- **Tenant-readiness `onLoad` events** intentionally omit `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, and `entityId` because they run before any instance pins the patch. Automation & Scripting generates `scriptEventId` deterministically from the preimage `<tenantId, scriptId, eventSchemaVersion, scriptPatchVersion, eventType=onLoad, isDryRun=false>`. The persisted dedupe/audit Trigger Identity is then `<tenantId, scriptId, eventSchemaVersion, scriptPatchVersion, eventType=onLoad, scriptEventId, isDryRun=false>`.
- `onLoad` handler code may not create durable or semi-durable shared effects, including gameplay/tick commands or handler-owned records in shared stores. Platform-owned execution and readiness state, including required `script_event_audit` metadata, is permitted and required to claim, fence, record, and complete readiness; it is not a handler-created shared effect.
- A stale `onLoad` execution is terminalized under the DSL lifecycle's fencing rule and cannot transition its patch to `READY`. Readiness/publication generations are fences, not execution identity; retry after durable stale terminalization requires republishing a new immutable `scriptPatchVersion`, not minting another execution identity for the stale patch.

Callers must reuse the same full applicable Trigger Identity on retries for live ingress, including the same `scriptEventId`. For downstream command-handoff retries, reuse of the complete Command-Handoff Identity, including its complete source scope and any distinct target scope plus `automationDispatchId` and `commandOrdinal`, is target-state; the current live fallback reuses the available work-item/command identity and does not claim complete fan-out deduplication. For dry-run/test ingress, server-generated IDs are preferred by default; if caller-supplied IDs are accepted, they must be collision-validated in the dry-run namespace.

Ingress ownership is endpoint-specific and must follow the matrix in `design/architecture/system-architecture-scripting-normative-contract-tables.md#table-1a-event-ingress-scripteventid-ownership-matrix`.

### 4a) Handler Input Manifest Boundary

The [DSL lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md#read-consistency-contract) owns deterministic evaluation/read consistency. Automation seals one bounded input manifest per resolved handler, including `onLoad`, with the complete applicable owner/runtime scope, tenant/script identity, exact `(scriptPatchVersion, scriptPinEpoch)` tuple, plugin provenance `(pluginId, pluginVersionId, bindingId)` when applicable, trigger facts, pinned artifacts, owner-versioned values, causal floor, and seed version. Tenant-readiness `onLoad` retains its declared pre-instance-pin identity exception. A `readSnapshotToken` is retained only when an individual owner contract uses it, as one manifest input or correlation value. Evaluation-stage recovery may invoke the DSL evaluator again under the original work-item/`scriptEventId`, frozen manifest evidence, and exact admitted immutable graph, outside normal admission and without a new dispatch identity. Post-evaluation recovery, downstream handoff, and tick replay must carry the manifest digest/reference and reuse the sealed inputs; they must not fetch newer state or re-enter the DSL. The current cross-service payloads may still carry the narrower token field, which remains an implementation gap rather than universal read authority. Missing or stale required manifest evidence fails closed.

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
- Dry-run/test budget ceilings use the bounded pre-handler admission pair defined in the [normative contract tables](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields). After handler work is materialized, capacity denial uses handler-scoped `finalOutcome=quota_denied` with a bounded reason; no separate `DRY_RUN_*` outcome is introduced.
- If dry-run authorization context is missing or invalid, the call must fail with deterministic application error (for example `DRY_RUN_UNAUTHORIZED`) and must not execute DSL evaluation.
- Dry-run/test execution capacity must be isolated from live traffic with separate worker pools, reserved worker shares, or equivalent scheduling partitions so privileged tooling cannot consume the last available live automation capacity.

### 7) Reload Backpressure Contract

- During `reloadState=RELOADING`, the Automation & Scripting Service must return an explicit application-level backpressure outcome (not a silent drop).
- For low-rate external events, callers must retry with the same full applicable Trigger Identity, including the same `scriptEventId`, using bounded exponential backoff and jitter:
  - `maxAttempts` must be finite and documented per client.
  - `maxElapsedMs` must be finite and documented per client.
  - Jitter must be non-zero to avoid synchronized retry storms.
- Backpressure responses must include a server hint (`retryAfterMs`) so callers can align retries with expected reload/rollback progress.
- For timer-derived scheduler events, best-effort timer semantics apply; triggers not admitted during reload are not backfilled unless explicitly covered by a bounded catch-up rule.

### 7a) Runtime Scope Isolation

- Tenant-scoped patch readiness (`READY` / `FAILED`) is an eligibility signal, not a runtime execution scope.
- Admission pause, reload backpressure, timer ownership, rollback convergence, and plugin lifecycle actions must preserve instance isolation at minimum scope `(tenantId, gameInstanceId)`.
- A deployment may intentionally couple all instances in a tenant only if that invariant is explicitly documented and enforced end-to-end; otherwise one instance's patch transition must not stall unrelated instances.

### 7b) Reload Failure Contract

- When a runtime scope is `reloadState=FAILED`, event ingress is rejected with `admitted=false`, `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE`, and bounded `admissionReason=reload_failed`.
- `reload_failed` is not automatic-retry backpressure: the response has no `retryAfterMs`, the event-scope decision is recorded in `script_event_ingress_audit`, and no handler row, firing claim, or handler Trigger Identity is created. Timer due candidates use their event-scope `scheduleCandidateId` audit instead and create no `scriptEventId`.
- Recovery must durably reconcile the current pin, schedule ownership, runtime scope/epoch, and due-point evidence, then transition `FAILED -> RELOADING` under one idempotent recovery identity. Only a successful atomic reconciliation may transition `RELOADING -> IDLE`; the prior observed patch is diagnostic evidence only and is never a last-known-good execution fallback.

### 8) Plugin Version Fencing and Control-Plane Scope

Plugins are executed by the same runtime engine as scripts and must not rely on weaker rollback semantics:

- Plugin enablement and active `pluginVersionId` selection are explicit per `(tenantId, gameInstanceId, pluginId)` and are controlled by operator control-plane APIs (typically via Logging & Admin driving Automation & Scripting registry APIs).
- Plugin triggers must follow the same Trigger Identity rules, including `gameInstanceId` and (for gameplay/runtime triggers) `regionEpoch`. For plugin triggers, `pluginId`, `pluginVersionId`, and the contributing `bindingId` are required identity fields; the binding identity is stable within the signed plugin version and is resolved before handler-scoped audit/dedupe.
- Script work items and tick commands produced by plugins must carry `pluginId`, `pluginVersionId`, and `bindingId` in addition to `scriptPatchVersion` whenever the command came from a binding-scoped plugin handler.
  - On execution, Game Session must enforce a **plugin version fence** analogous to the script patch fence:
  - If a command’s embedded `pluginVersionId` does not match the instance’s currently active plugin version for that `pluginId`, Game Session must not execute it.
    - **Target state:** rejection must be recorded with `outboxWorkItemId` as explicit parent correlation, the complete applicable parent Trigger Identity (including plugin fields `pluginId`, `pluginVersionId`, and `bindingId`), and the complete child Command-Handoff Identity (source/target runtime scope, `automationDispatchId`, and deterministic `commandOrdinal`, including optional distinct target fields) so operators can diagnose exactly which fan-out command was dropped. `outboxWorkItemId` and the complete parent Trigger Identity are correlation/diagnostic context only; they are excluded from child uniqueness, deduplication, and replay selection. The rejected queue entry must be removed or moved to a bounded dead-letter store with explicit `maxAge`/`maxRows` and alert-backed cleanup cadence. The live fallback uses the narrower plugin/work-item/command fields currently retained by Automation and Game Session; it must not be described as complete Trigger Identity proof. The remaining target gap is tracked under `AS-1.5` in the [automation and scheduler runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

### 9) Rollback Convergence Timeout Contract

- Rollback orchestration must enforce bounded convergence waiting across Automation and Game Session pin-convergence APIs.
- If convergence is not observed before timeout, rollback enters terminal state `ROLLBACK_CONVERGENCE_TIMEOUT`.
- In that state, new Automation admission remains fail-closed for the affected instance until explicit repair or another repin. Ordinary player-command admission and gameplay ticks continue when their own dependencies and fences are healthy; a full tick pause is exceptional and requires a named unfenced effect or migration boundary.
- When rollback enters the terminal state, emit once:
  - Control-plane event `ScriptRollbackConvergenceTimedOut` produced by Game Session as producer-of-record.
  - Metric `automation_rollback_convergence_timeout_total{scope, operation, reason}`.
- While that terminal state remains active, each rejected ingress must record event-scope outcome `admitted=false`, `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE`, `admissionReason=rollback_convergence_timeout`, and no `retryAfterMs` in `script_event_ingress_audit` and increment the existing `automation_script_triggers_dropped_total{scope, script_category, reason="rollback_convergence_timeout"}` family. This is terminal fail-closed state, not retryable backpressure. These rejected ingresses must not increment `automation_rollback_convergence_timeout_total` and must not create a handler-scoped `finalOutcome`.
- Rollback-safe Automation pause scope is instance-level: admission pause, convergence checks, and resume operations target `(tenantId, gameInstanceId)`. Region-only pause operations are operational tools and must not substitute for the script epoch fence. `PauseTicks`/`ResumeTicks` are reserved for the exceptional unfenced boundary documented by the rollout owner, not the routine script rollback path.

### 10) Instance Rollout Read Model Ownership

- Game Session atomically owns the current `(scriptPatchVersion, scriptPinEpoch)` and append-only history of committed pin, rollback, and repin attempts for `(tenantId, gameInstanceId)`. Each history record includes `controlPlaneRequestId`, operation kind, previous/resulting exact tuples, actor/reason when operator-driven, outcome, and commit time. A same-request retry returns the stored result and appends no duplicate logical entry.
- Game Session exposes bounded authoritative current-pin and paginated rollout-history reads. Automation & Scripting may retain only observed-pin, convergence, and freshness projections for local admission and diagnostics; it must not author `PINNED`, `ROLLED_BACK`, or `REPINNED` history from work-item presence or projection refresh.
- Logging & Admin composes Game Session history with Automation readiness/convergence state and presents disagreement as projection or convergence lag. A transactional outbox notification may accelerate refresh, but loss or duplication of that notification cannot alter or erase Game Session history. A separate Automation rollout event family is not required for this contract.

### 11) Pin-State Degraded Override Governance

- Admission decisions must fail closed with `pin_state_unavailable` when bounded-staleness pin refresh cannot reach an authoritative source.
- There is no operator stale-pin override. An observed local pin, TTL, audit record, or operator reason cannot authorize admission without a fresh authoritative Game Session tuple. Recovery restores Game Session reads/projection delivery or performs an explicit repin after authority returns; ordinary non-script gameplay may continue when healthy.
- A future exception would require a new accepted contract for a short-lived Game Session-issued capability bound to the exact tenant, instance, version, and epoch; no generic flag or stale-cache grace path exists today.

### 12) Dead-Letter Replay Version-Fence Safety

- Every recoverable dead letter records an immutable failure stage and the evidence required by that stage. Missing, contradictory, or unreadable stage evidence remains `DEAD_LETTERED`.
- Evaluation-stage recovery may re-run the DSL only from the original persisted trigger identity and payload, frozen manifest/input evidence, and exact immutable compiled graph identified by the admitted `(scriptPatchVersion, scriptPinEpoch)`; it retains the original work-item and `scriptEventId` identity and never resolves `latest` or mutable defaults.
- Post-evaluation recovery never invokes the DSL. It resumes the stored output and child-dispatch ledger, preserving child identities, payload digests, ordinals, and acknowledgements; accepted or terminal children no-op and only unfinished children dispatch.
- Both paths require an exact current match for the admitted patch/epoch, applicable plugin identity/version/binding, runtime region/`regionEpoch`, and admitted routing bundle. Matching patch text under a different pin epoch is ineligible.
- `ReplayDeadLetteredWorkItems` accepts bounded explicit parent `workItemIds[]` only; descriptor references and filters remain deferred mutation selectors. Each row returns exactly one `outcome`: `retried_evaluation`, `resumed_dispatch`, `already_recovered`, or `rejected`. Only a `rejected` row carries `rejectionReason`, using bounded values such as `not_found_or_not_owned`, `stage_evidence_unavailable`, `script_pin_epoch_mismatch`, `plugin_binding_mismatch`, or `runtime_scope_mismatch`. Eligible rows progress independently; ineligible rows remain dead-lettered. A stable `controlPlaneRequestId`, actor, and reason make duplicate recovery requests idempotent.
- Purge is a separate bounded, authorized, audited operation with its own request identity and per-row outcomes. Operators and automation never repair or delete dead-letter rows through direct SQL.

### 13) Output Budget Safety

- A successfully admitted trigger must still be prevented from emitting unbounded work. The canonical versioned/digested component-cost metadata, conservative publish analysis, artifact-pinned caps, and incremental metering contract are owned by [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#static-output-cost-contract).
- At this cross-service boundary, Automation must reject a missing/stale/contradictory artifact-cost digest at the correct ingress or readiness stage, enforce the owner-defined conservative prospective serialized-byte and data-dependent bounds before constructing each output descriptor, and check exact bounded serialized size before atomic persistence.
- Output-budget violations are non-success stage-aware outcomes. Generated output is atomic per resolved handler: no partial descriptor set may be persisted or handed off, and Game Session must never receive a partial handoff presented as successful work.
- Publish-time validation in Game Design must conservatively reject graphs whose bounded worst-case fan-out exceeds the artifact-pinned runtime ceilings. The owner document defines metadata shape and exact failure mapping; this contract only protects the persistence/handoff boundary.

### 14) Handler Charge and Capacity Boundary

The durable charge lifecycle is owned by [Scripting Quotas & Operations](./system-architecture-scripting-quotas-and-operations.md#budget-accounting-rules). Cross-service consumers must preserve its consequences:

- One full-Trigger-Identity handler charge record, with separate exactly-once admission and execution-start markers, is the sole charge authority. Immediately before evaluation, Automation acquires the fenced lease and atomically revalidates its fence, durably accepts/claims the run for the executor, persists the execution-start marker, and transitions the work item to `EXECUTING` in one executor-acceptance transaction; evaluation begins only after commit. If executor acceptance fails, the transition does not commit and no execution charge is recorded. Duplicate and recovery attempts reuse the durable executor claim, and recovery may reacquire a lease without creating another marker.
- Queued work holds no execution capacity. The capacity lease is separately fenced/reclaimable, and reclaiming an expired lease is not a refund or a new charge.
- `PUBLISH_READINESS`/`onLoad` uses isolated readiness capacity and must not consume ordinary live quota or capacity. Game Session handoff occurs only after Automation's handler charge and capacity checks succeed; Game Session must not charge the handler again.

## Related Documents

- `design/architecture/system-architecture-scripting.md`
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/system-architecture-scripting-quotas-and-operations.md`
- `design/architecture/system-architecture-scripting-observability-contract.md`
- `design/architecture/system-architecture-scripting-control-plane-api.md`
- `design/architecture/microservices/automation-scripting-service/README.md`
- `design/architecture/microservices/game-session-service/README.md`
