# FireMUD System Architecture: Scripting Scheduler and Timer Lifecycle

This document defines the runtime scheduler, timer, and reload lifecycle for scripting and automation. It complements [Scripting DSL Reference & Event Lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md), which remains the canonical owner for DSL semantics, trigger identity, event fan-out, and determinism rules.

## Implementation Notes

The current Automation & Scripting implementation now persists a durable patch-scoped schedule-definition catalog in PostgreSQL (`script_schedule_definitions`) when `NotifyScriptVersionUpdate` reloads a script patch, and it materializes the currently pinned patch for each observed `(tenantId, gameInstanceId)` scope into durable `script_schedule_instances` rows. Those rows now preserve `scheduleDefinitionId`, owner patch/plugin metadata, cadence/unit, scheduler priority tag, binding target identity (`targetScopeType`, `targetScopeId`, binding priority, exclusivity), schedule semantics hash, observed pin request id, and the first instance-scoped due-point substrate. Wall-clock timers compute `nextDueAt`; tick-aligned schedules start as `PENDING_RUNTIME_PROGRESS` and the internal `ObserveRuntimeTickProgress` feed advances them to `READY` only after Game Session reports `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`. The same heartbeat feed now also stamps runtime scope onto wall-clock timers so `onTimerExpire` work uses the same fenced `(regionId, regionEpoch)` trigger identity before it is admitted. Game Session now advances a durable `lastCommittedTickId` on the same runtime ownership row used for `regionId`, `regionEpoch`, and `executorFence`, exposes it through runtime ownership status, and publishes it to Automation after successful tick cycles. Because true region partitioning is still target-state, the current Game Session owner row stores a transitional non-empty `regionId` derived from `gameInstanceId` rather than a later split region key. Automation stores `runtimeRegionId`, `runtimeRegionEpoch`, `lastObservedTickId`, `lastRuntimeProgressObservedAt`, and the next future `nextDueTickId`. When an observed tick reaches a schedule's due point, Automation now mints deterministic timer-derived `ScriptWorkItem` rows, queues them through the normal `automation:queue:*` projection, writes handler audit rows, and advances the schedule to the next future due tick. When a wall-clock `nextDueAt` is already due once runtime scope is known, Automation now emits the corresponding `onTimerExpire` work item from the same observation path and clears the consumed due point instead of leaving the durable schedule inert. If a newer observation proves the runtime scope changed first, Automation now fences the stale due point instead of re-emitting it under the new region epoch, records a bounded `runtime_scope_changed` audit row against the old trigger identity, and increments `automation_script_timer_runtime_fence_dropped_total`. Due candidates are selected in round-robin passes across schedule identities and capped by `script.scheduler.max-catch-up-firings-per-observation`; this does not yet prove the accepted one-per-schedule durable resume-window cap across repeated observations. The `ObserveRuntimeTickProgress` response reports updated rows, fired work items, and truncated firing candidates, while `automation_script_timer_fired_total`, `automation_script_timer_catchup_truncated_total`, and `automation_script_timer_runtime_fence_dropped_total` expose aggregate runtime metrics. Control-plane timer audit reads now expose the same scheduler-owned `script_event_audit` rows, including plugin owner metadata, admitted routing bundle, due-point coordinates, and skipped-versus-persisted outcomes, so operators can inspect concrete truncated/fenced due points without reconstructing them from aggregate counters. The richer Redis leader/checkpoint model below remains target-state. Plugin-owned schedule definitions now also retain `pluginId` / `pluginVersionId` from compiled script payloads, and instance materialization filters them through Automation's enabled plugin runtime registry so displaced or disabled plugin versions stop owning durable schedule rows instead of lingering as inert duplicates.

## Script Timers vs Tick Timers

Every script timer declares a clock and recovery class under [ADR 0091](decisions/adr-0091-class-specific-script-timer-clocks-and-recovery.md) and [ADR 0072](decisions/adr-0072-class-specific-timer-durability-and-recovery.md):

- `onInterval` and other gameplay-cadence schedules use authoritative tick/game time by default. Their cadence freezes or stretches with that timeline.
- An explicitly real-time timer may use a wall-clock deadline when elapsed real time is its declared product semantics. Reaching the deadline makes work eligible, but gameplay work enters only at the next eligible canonical tick and never mutates on a parallel wall-clock path.
- Missed firings are handled in a bounded, deterministic way:
  - Every schedule declares `SKIP_MISSED` or `COALESCE_ONE`. When leaders change, `COALESCE_ONE` may enqueue at most one synthetic firing for that logical schedule in the durable resume window; `SKIP_MISSED` enqueues none.
  - The durable resume-window identity spans repeated scans and progress observations, so they cannot mint another catch-up firing for the same logical schedule/window.
  - Before enqueueing any such firing, the scheduler must first claim or insert a durable trigger-instance row keyed by an instance-aware uniqueness projection so failover or duplicate consumers cannot create duplicate logical triggers.
  - If `scheduleId` is not globally unique across game instances, this projection must include `gameInstanceId`.
  - Missed firings due to quotas, budgets, disabled scripts, or failed or unknown versions are not replayed later; they are recorded in `script_event_audit` and associated metrics as dropped or skipped triggers.
- A gameplay consequence that must occur once is not a best-effort interval. It uses the durable correctness-bearing one-shot timer path with stable identity, authoritative due state, idempotent dispatch, and an explicit terminal outcome.

Within that model:

- The Game Session Service owns authoritative tick progression and tick timers, as described in [Tick System and Runtime Design](./system-architecture-ticks.md).
- The Automation & Scripting Service owns script timers and intervals, which are scheduled against tick heartbeat information but do not own ticks themselves.
- Scheduler data structures such as `automation:timer:{tenantRegionTag}` are used to track when script timers should fire relative to tick progression; durable script schedules and quotas live in the Automation & Scripting Service’s PostgreSQL schema, while Redis indexes are coordination structures that can be rebuilt or reset without changing which scripts should eventually run.

From the tick system’s perspective, script timers are just another source of work that ultimately enqueues commands into tick queues. The determinism rules in the DSL reference apply equally to timer-driven triggers.

### Timer Resume Rule (Normative)

When reload, rollback, or schedule preservation keeps a logical timer alive across a version transition, the scheduler must recalculate its next due point using the same explicit rule:

- Inputs:
  - `resumeTickId` = the latest committed tick known for the timer's region when scheduling resumes for the runtime scope.
  - `previousDueTickId` = the durable due point stored before pause or reconciliation.
  - `intervalTicks` = the preserved schedule cadence in ticks.
- Rule:
  - if `previousDueTickId >= resumeTickId`, keep `nextTick = previousDueTickId`;
  - otherwise compute `remainder = (resumeTickId - previousDueTickId) % intervalTicks` and set `nextTick = resumeTickId` when `remainder == 0`, else `nextTick = resumeTickId + intervalTicks - remainder`.
- Consequences:
  - the scheduler resumes on the next future cadence boundary at or after resume, never by replaying every missed firing from the paused window;
  - a preserved schedule may fire immediately after resume only when its next valid cadence boundary is exactly `resumeTickId`;
  - reload and rollback preservation and leader-failover catch-up remain distinct behaviors. The resume rule governs preserved timers after a version transition; bounded catch-up rules govern missed firings after scheduler downtime within the same logical schedule and version.
- Explicit wall-clock schedules define the analogous formula over `nextRunAt` and `resumeAt`; their due point still creates gameplay work only at the next eligible canonical tick.

## End-to-End `onInterval` Timer Lifecycle

This section summarizes how a single `onInterval` timer behaves across normal operation, leader changes, and script reloads, and which Redis keys are authoritative at each step.

All durable timer identity and scheduler checkpoints must be instance-aware even when Redis keys are region-scoped for slotting and locality:

- `gameInstanceId` is a required part of timer identity, deterministic scheduler `scriptEventId` derivation, catch-up deduplication, and checkpoint projection state.
- If a Redis key name omits `gameInstanceId`, the stored timer or checkpoint payload must still include it, and rebuild logic must treat instance mismatches as corruption rather than as reusable timer state.
- Plugin timers must additionally carry `pluginId` and `pluginVersionId` in their durable identity so plugin rollback, disablement, and revocation cannot cross-contaminate interval state.

### Normal Operation

- When an instance first observes a pinned patch, Automation materializes the patch-owned schedules for that `(tenantId, gameInstanceId)` scope into durable `script_schedule_instances` rows keyed by stable `scheduleDefinitionId`, plugin owner metadata, and binding target identity. Each row stores cadence, unit, scheduler priority tag, normalized schedule metadata hash, observed pin request id, and the current due-point substrate. Wall-clock timers may compute `nextRunAt` immediately from the observed pin timestamp; tick-aligned schedules remain persisted but `PENDING_RUNTIME_PROGRESS` until Game Session's `ObserveRuntimeTickProgress` input supplies the current runtime region epoch and tick id from Game Session's durable runtime ownership row. At that point Automation records the runtime region fields and derives the first future `nextTick` as `observedTickId + cadenceValue`; later observations that reach or pass `nextTick` create deterministic timer-derived work items using the same durable work-item, audit, and queue projection path as other admitted script events. Plugin-owned schedules are materialized only for the plugin version currently enabled in Automation's runtime registry for that instance; other published or previously active plugin versions must not keep durable schedule ownership. Region-scoped Redis indexes such as `automation:timer:{tenantRegionTag}` remain a derived coordination layer above those durable rows rather than the first source of schedule identity.
- Leaders track these interval entries alongside other automation timers, using bounded scans and the automation tick budget (for example, `AUTOMATION_TICK_DURATION_MS`, `AUTOMATION_TICK_MAX_EVENTS`, `AUTOMATION_TICK_BUDGET_MS`) to decide which `onInterval` triggers should fire in each automation tick.
- When an interval becomes due, the scheduler creates a `scriptEventId` for the `onInterval` trigger and evaluates it using the same quota, cadence, and budgeting layers described in [Scripting Quotas and Operations](./system-architecture-scripting-quotas-and-operations.md). If the script is outside its budgets or disabled, the trigger is skipped and recorded in both metrics and the audit feed; otherwise it is enqueued for sandbox execution and the interval entry’s next due point is advanced.

### Leader Changes

- Leaders advance a per-region notion of time by consuming the tick heartbeat stream and tracking how far they have progressed. In addition to the current heartbeat `(regionEpoch, tickId)`, schedulers maintain a region-scoped checkpoint such as `script-scheduler:{tenantRegionTag}:lastTickId`. Any checkpoint payload that also carries instance-level projection state must include `gameInstanceId`.
- When leadership changes, the new leader:
  - reads `script-scheduler:{tenantRegionTag}:lastTickId` for each region it owns, interpreted in the context of the current `regionEpoch`;
  - walks forward from `lastTickId` to the current `tickId` using the heartbeat stream for that epoch; and
  - for each timer entry in the region index `automation:timer:{tenantRegionTag}`, determines which "every N ticks" boundaries were crossed during the gap. Before enqueueing any `onInterval` trigger, it first claims or inserts a durable trigger-instance row keyed by `(scheduleId, gameInstanceId, regionEpoch, dueTickId, triggerKind)` or an equivalent uniqueness projection.
- Each `COALESCE_ONE` schedule may enqueue at most one missed `onInterval` trigger before normal scheduling resumes; `SKIP_MISSED` schedules enqueue none. `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME` is the global cap across logical schedules for one durable resume-window identity. Candidates beyond this cap are dropped, not deferred, and surfaced through audit and `automation_script_timer_catchup_truncated_total`.
- That durable resume-window identity is reused across repeated observations and worker takeover until the recovery window closes; rescanning cannot grant another coalesced firing to the same schedule.
- Catch-up truncation must use one stable fairness algorithm when due candidates exceed `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME`:
  - Build the candidate set ordered by `dueTickId ASC`, then `priorityTag` (`high`, `normal`, `background`), then stable schedule identity ASC.
  - Admit catch-up firings in round-robin passes across distinct `scheduleDefinitionId` values so one noisy schedule cannot consume the entire resume window before other overdue schedules get one chance to fire.
  - Admit at most one missed firing for any logical schedule in the complete resume window, using its earliest missed `dueTickId` as the coalesced identity.
  - Once the resume cap is reached, remaining overdue firings are truncated rather than deferred into an unbounded backlog; emit audit/metrics that distinguish how many were coalesced or dropped.
- If a per-script index is used, it is reconciled against the region index as needed; discrepancies are treated as projection bugs and corrected, not as new timers.
- Because the authoritative schedule configuration lives in PostgreSQL and Redis holds only coordination state (timer indexes and checkpoints), leader changes do not reset cadences; they only introduce a bounded delay before the new leader catches up.

### Script Reload

- During reload, leaders set `reloadState=RELOADING` for the affected runtime scope after observing that Game Session has pinned a tenant-`READY` patch for that instance. `onLoad` is not part of this instance reload path; it has already completed as part of tenant patch readiness. Existing timer entries in the region index `automation:timer:{tenantRegionTag}` and any derived per-script projections remain in Redis but are treated as pending until reconciliation completes.
- Once reload succeeds and `activePatchVersion` is switched, the leader:
  - re-reads its heartbeat checkpoint and the current `tickId`;
  - reconciles durable schedule definitions for the newly pinned patch before any timer is allowed to fire again, updating the instance-scoped `script_schedule_instances` rows first;
  - updates each surviving interval entry’s next due point (`nextTick` or `nextRunAt`) using the normative timer resume rule so the cadence resumes from the latest tick and time rather than replaying the paused window; and
  - resumes normal scheduling for `onInterval` using the updated `activePatchVersion`. No interval runs against a partially loaded script definition.
- If reload fails, `activePatchVersion` remains unchanged, `pendingPatchVersion` is marked failed, and the leader resumes using the existing region-index timer entries as-is. Any `onInterval` triggers that fire after a failed reload are still scheduled according to the stored cadence, but always execute under the last known good patch version.

Timer reconciliation on patch or plugin change is a required part of reload and rollback safety:

- Durable timer identities must be version-scoped by the schedule definition that created them (for example `scriptPatchVersion` for core scripts and `pluginVersionId` for plugins), not just by entity and cadence.
- Every compiled timer or interval definition must carry a stable `scheduleDefinitionId` generated by the Game Design compilation pipeline and persisted with the schedule metadata. This identifier represents one logical schedule definition within a patch or plugin version.
- A patch or plugin version must not contain duplicate `scheduleDefinitionId` values within the same owning scope; compilation and publish must fail deterministically if duplicates are present.
- Implementations may additionally persist a `scheduleSemanticsHash` or equivalent normalized fingerprint for debugging and migration visibility, but reconciliation authority is the stable `scheduleDefinitionId`.
- When an instance observes a newly pinned `scriptPatchVersion`, the scheduler must compare the durable schedules for the previously observed patch with those for the newly pinned patch before resuming timer admission.
- Schedules that do not exist in the newly pinned patch must be removed or tombstoned so they can no longer generate triggers.
- Schedules may be preserved across patch or plugin changes only when the old and new definitions share the same `scheduleDefinitionId`. Matching by cadence, node shape, entity binding, or other inferred semantic similarity is not sufficient.
- When a preserved schedule keeps the same `scheduleDefinitionId`, reconciliation must rewrite the durable owner and version metadata to the newly pinned `scriptPatchVersion` or `pluginVersionId` before scheduling resumes, and must recalculate `nextTick` or `nextRunAt` from the normative timer resume rule rather than reusing stale due points blindly.
- The same rule applies to plugin activation, disable, rollback, and signer-revocation flows: any schedule owned by a displaced `pluginVersionId` must be removed or tombstoned before normal scheduling resumes for that plugin.
- Canceling outbox work items alone is insufficient for rollback safety; old-version timer schedules must also be reconciled so they cannot mint new `scriptEventId` values after the version has been displaced.

Plugin example:

- If plugin version `combat-helper@v4` and `combat-helper@v5` both compile a "reapply guard aura every 20 ticks" timer with `scheduleDefinitionId=combat-helper.guard-aura.v1`, reconciliation preserves that durable timer row across the version switch, rewrites ownership from `pluginVersionId=v4` to `pluginVersionId=v5`, and recalculates the next due point from the resume rule before scheduling resumes.
- If `combat-helper@v5` replaces that timer with a different logical schedule such as "pulse only while threat > 0" compiled to a different `scheduleDefinitionId`, the old timer owned by `v4` must be tombstoned and a new timer created for `v5`.

Under this model, durable script schedules, quotas, and trigger-instance de-duplication live in PostgreSQL, while `automation:timer:{tenantRegionTag}`, `script-scheduler:{tenantRegionTag}:lastTickId`, and related coordination keys form a reset-tolerant coordination layer for interval state. Stored entries and reconciliation logic remain instance-aware even though the Redis keys are region-scoped. The combination of tick heartbeat, durable trigger-instance claims, checkpoints, and script patch versioning preserves both correctness and determinism across failures and leader changes: losing or resetting these Redis keys may delay or slightly reshuffle timer firings within the tail-loss envelope but must not change which scripts are eventually scheduled according to their stored configurations or cause duplicate logical trigger creation.

## Scheduler Leadership & Coordination

Scheduler leadership and coordination ensure that script timers and Automation-owned scheduling windows are processed safely in a distributed environment:

- Leadership must remain explicit and bounded, but the canonical design does not currently require a separate first-class `script-leader:*` Redis prefix. Scheduler ownership should instead be documented through the same runtime and coordination surfaces used by the Redis and automation docs unless a later design update introduces a dedicated lease family.
- Only the current leader for a runtime scope processes that scope’s script timers and automation queues. Implementations may lease by tenant for operational simplicity only if the leased worker still preserves per-instance isolation in its timer, queue, and reload state.
- Automation scheduling coordinates with tick heartbeat streams to ensure that:
  - `onInterval` triggers fire on the correct tick boundaries;
  - per-tenant and per-script quotas are respected; and
  - work is sharded in a way that keeps multi-key operations hash-tag-local.

See [Tick System and Runtime Design](./system-architecture-ticks.md) and [`automation-scripting-service/README.md`](./microservices/automation-scripting-service/README.md) for the current leadership and sharding model.

## Hot Reload & Resume Behavior

- Scripts are versioned and published via the Game Design Service; the Game Session Service pins an active `scriptPatchVersion` per game.
- When a new script patch is published, the Automation & Scripting Service:
  - ingests the new definitions and validates them for tenant readiness;
  - runs tenant-scoped `onLoad` handlers while the patch is still in `PENDING_VALIDATION` / `ONLOAD_RUNNING`;
  - marks the patch `READY` or `FAILED` for the tenant; and only then
  - uses instance-scoped reload state (for example, `reloadState=RELOADING`) when a running game pins that already-`READY` patch.
- During reloads, triggers are paused or skipped while in-flight runs drain under existing concurrency settings:
  - New triggers for the affected runtime scope are not admitted; attempts to schedule additional runs receive explicit backpressure outcomes (`skipped_reloading` during reload, `rollback_paused` during rollback pause). For low-rate external events, callers may retry with the same `scriptEventId` using bounded backoff (`maxAttempts`, `maxElapsedMs`, jitter) and should honor server retry hints such as `retryAfterMs`; audit records must remain keyed by Trigger Identity and must not multiply rows per retry.
  - In-flight runs remain bounded by each script’s configured `maxConcurrent` and `concurrencyPolicy` (for example, `queue_until_free`); any short per-script waiting queues are allowed to drain, but no new entries are added while `reloadState=RELOADING`.
  - Pending timer-based triggers that became due during reload remain in the scheduler’s timer indexes until version reconciliation completes, then resume only for schedules that still exist under the newly observed patch or plugin version with recalculated `nextTick` or `nextRunAt` so cadences remain coherent.
- On success, the new `scriptPatchVersion` becomes active for future triggers; on failure:
  - `activePatchVersion` remains unchanged and continues to govern live execution;
  - `pendingPatchVersion` is marked failed along with an error reason, and leaders discard any partially loaded state for that patch and resume scheduling using the existing `activePatchVersion`; and
  - triggers referencing a failed or unknown patch are rejected explicitly with `finalOutcome=version_unavailable` (with specific cause in `finalReason`) and metrics like `automation_script_triggers_dropped_total{reason="version_unavailable"}` rather than silently falling back to an older patch.

- Timer-based triggers such as `onInterval` and `onTimerExpire` always execute against the currently pinned `scriptPatchVersion` for the game at the moment they are evaluated; they do not continue running older definitions after a patch is promoted.
- Older script versions remain in the Automation & Scripting Service database for auditing and potential rollback, but only the pinned active version is used for live execution.
