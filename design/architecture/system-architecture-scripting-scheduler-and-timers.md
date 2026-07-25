# FireMUD System Architecture: Scripting Scheduler and Timer Lifecycle

This document defines the runtime scheduler, timer, and reload lifecycle for scripting and automation. It complements [Scripting DSL Reference & Event Lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md), which remains the canonical owner for DSL semantics, trigger identity, event fan-out, and determinism rules.

## Implementation Status

The current implementation provides the following substrate:

- `NotifyScriptVersionUpdate` persists the durable patch-scoped schedule-definition catalog in PostgreSQL (`script_schedule_definitions`), and each observed `(tenantId, gameInstanceId)` pin materializes durable `script_schedule_instances` rows. Those rows retain `scheduleDefinitionId`, patch/plugin ownership, cadence/unit, priority, binding target identity and priority/exclusivity, `scheduleSemanticsHash`, observed pin request, and mutable due-point state.
- Wall-clock timers persist `nextDueAt`. Tick-aligned schedules remain `PENDING_RUNTIME_PROGRESS` until Game Session reports `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` through `ObserveRuntimeTickProgress`; Automation then stores `runtimeRegionId`, `runtimeRegionEpoch`, `lastObservedTickId`, `lastRuntimeProgressObservedAt`, and the next future `nextDueTickId`.
- Game Session advances durable `lastCommittedTickId` on the runtime-ownership row that also carries `regionId`, `regionEpoch`, and `executorFence`, exposes that state through runtime ownership status, and publishes it after successful tick cycles. True region partitioning remains target-state, so the live ownership row currently uses a transitional non-empty `regionId` derived from `gameInstanceId`.
- Runtime observations stamp the fenced region scope onto wall-clock timers as well as tick schedules. Due tick and wall-clock candidates emit deterministic timer-derived `ScriptWorkItem` rows through the normal durable work-item, audit, and `automation:queue:*` projection path, then advance or clear the consumed due point.
- A newer observation that proves the runtime scope changed fences the stale due point instead of reminting it under the new epoch. The service records `runtime_scope_changed` against the old Trigger Identity and increments `automation_script_timer_runtime_fence_dropped_total`.
- Due candidates are selected in round-robin passes across schedule identities and capped by `script.scheduler.max-catch-up-firings-per-observation`. `ObserveRuntimeTickProgress` reports updated rows, fired work items, and truncated candidates; timer-fired, catch-up-truncated, and runtime-fence metrics expose aggregate outcomes.
- Control-plane timer audit reads expose scheduler-owned `script_event_audit` rows, including plugin ownership, admitted routing, due-point coordinates, and skipped-versus-persisted outcomes. Plugin-owned definitions retain `pluginId`/`pluginVersionId`, and materialization admits only the enabled runtime plugin version.
- The richer Redis leader/checkpoint model below remains target-state.

## Target-State Design

## Script Timers vs Tick Timers

Script timers are layered on top of the core tick model and always express cadence in terms of the authoritative game tick timeline, not raw wall-clock seconds:

- Cadence for `onInterval` and other tick-based timers is configured in ticks (for example, "every N ticks"). Internally, schedulers may derive wall-clock hints from tick heartbeat streams, but the public contract is expressed in game ticks.
- Missed firings are handled in a bounded, deterministic way:
  - When leaders change, the new leader walks forward from its last persisted `tickId` to the current `tickId` and enqueues at most one synthetic firing for each cadence boundary crossed in that gap.
  - After a due candidate passes admission, and before enqueueing that firing, the scheduler must claim or insert a durable trigger-instance row keyed by an instance-aware uniqueness projection so failover or duplicate consumers cannot create duplicate logical triggers.
  - The projection must include the applicable Trigger Identity fields, including `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `entityId`, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `isDryRun`, `scheduleDefinitionId`, the persisted due point (`dueTickId` or `dueAt`), and `triggerMode`; plugin-owned claims must additionally include `pluginId` and `pluginVersionId`. Globally unique schedule IDs do not replace runtime scope, version, dry-run namespace, plugin ownership, or timeline fencing.
  - Missed firings due to quotas, budgets, disabled scripts, or failed or unknown versions are not replayed later; they are recorded in `script_event_audit` and associated metrics as dropped or skipped triggers.

Within that model:

- The Game Session Service owns authoritative tick progression and tick timers, as described in [Tick System and Runtime Design](./system-architecture-ticks.md).
- The Automation & Scripting Service owns script timers and intervals, which are scheduled against tick heartbeat information but do not own ticks themselves.
- Scheduler data structures such as `automation:timer:{tenantRegionTag}` are used to track when script timers should fire relative to tick progression. `{tenantRegionTag}` is the opaque shared-key-builder tag derived from the full `<tenantId, gameInstanceId, regionId>` scope; it is not a competing schedule identity. Durable script schedules and quotas live in the Automation & Scripting Service’s PostgreSQL schema, while Redis indexes are coordination structures that can be rebuilt or reset without changing which scripts should eventually run.

From the tick system’s perspective, script timers are just another source of work that ultimately enqueues commands into tick queues. The determinism rules in the DSL reference apply equally to timer-driven triggers.

### Timer Resume Rule (Normative)

When reload, rollback, or schedule preservation keeps a logical timer alive across a version transition, the scheduler must recalculate its next due point using the same explicit rule:

- Inputs:
  - `resumeTickId` = the latest committed tick known for the timer's region when scheduling resumes for the runtime scope.
  - `previousDueTickId` = the durable due point stored before pause or reconciliation.
  - `intervalTicks` = the preserved schedule cadence in ticks.
- Rule:
  - if `previousDueTickId > resumeTickId`, keep `nextTick = previousDueTickId`;
  - otherwise calculate `remainder = (resumeTickId - previousDueTickId) % intervalTicks`; set `nextTick = resumeTickId` when `remainder = 0`, otherwise set `nextTick = resumeTickId + intervalTicks - remainder`.
- Consequences:
  - the scheduler resumes on the next valid cadence boundary at or after resume, never by replaying every missed firing from the paused window;
  - a preserved schedule may fire immediately after resume only when its next valid cadence boundary is exactly `resumeTickId`;
  - reload and rollback preservation and leader-failover catch-up remain distinct behaviors. The resume rule governs preserved timers after a version transition; bounded catch-up rules govern missed firings after scheduler downtime within the same logical schedule and version.
- Equivalent wall-clock timers must define an analogous formula over `nextRunAt` and `resumeAt`, but gameplay-facing cadence remains specified in ticks and must reduce to the same tick-boundary behavior when tick-aligned.

## End-to-End `onInterval` Timer Lifecycle

This section summarizes how a single `onInterval` timer behaves across normal operation, leader changes, and script reloads, and which Redis keys are authoritative at each step.

All durable timer identity and scheduler checkpoints must be instance-aware even when Redis keys are region-scoped for slotting and locality:

- `gameInstanceId` is a required part of timer identity, deterministic scheduler `scriptEventId` derivation, catch-up deduplication, and checkpoint projection state.
- If a Redis key name omits `gameInstanceId`, the stored timer or checkpoint payload must still include it, and rebuild logic must treat instance mismatches as corruption rather than as reusable timer state.
- Plugin timers must additionally carry `pluginId` and `pluginVersionId` in their durable identity so plugin rollback, disablement, and revocation cannot cross-contaminate interval state.

### Interval-Entry Identity and Due-Point Persistence

A durable interval has two identities that must not be conflated.

Stable schedule-instance row identity:

`<tenantId, gameInstanceId, playableStateScope, scriptId, pluginId?, pluginVersionId?, eventType, eventSchemaVersion, scriptPatchVersion, isDryRun, scheduleDefinitionId, targetScopeType, targetScopeId>`

- `scheduleInstanceId` is the stable durable row identity, whether represented by this natural key or by a surrogate row ID. It excludes the mutable runtime scope and due point. `runtimeRegionId`, `runtimeRegionEpoch`, `nextDueTickId`/`nextDueAt`, and last-observed progress are schedule state that may advance on heartbeat or reconciliation without creating a new schedule row.
- `scheduleDefinitionId` is the stable logical schedule identity within its patch or plugin owner, not the firing claim identity. `scriptPatchVersion` is the current definition owner, plugin-owned entries additionally carry `pluginId` and `pluginVersionId`, and `isDryRun` separates test execution from live scheduling. Live durable interval entries persist `isDryRun=false`.
- `duePoint` is exactly one mutable persisted value: `dueTickId` for tick-aligned schedules or `dueAt` for wall-clock timers. `nextTick` and `nextRunAt` are projections of that stored due point, not substitutes for it.
- Each firing has a separate durable claim keyed by the schedule-instance identity plus the applicable runtime scope (`regionId`, `regionEpoch`), target entity, trigger mode, and tagged due point. The deterministic `scriptEventId` must be derived from that complete firing-claim tuple, including the due point and plugin owner fields when applicable. Advancing the schedule due point must never rewrite a prior claim or reuse its `scriptEventId`.

### Normal Operation

- When an instance first observes a pinned patch, Automation materializes the patch-owned schedules for that `(tenantId, gameInstanceId)` scope into durable `script_schedule_instances` rows keyed by the stable schedule-instance identity above, with plugin owner metadata and binding target identity. Each row stores `scriptPatchVersion`, `isDryRun`, `scheduleDefinitionId`, cadence, unit, scheduler priority tag, normalized schedule metadata hash, observed pin request id, and exactly one mutable due-point field (`dueTickId` or `dueAt`). Wall-clock timers may initialize `dueAt` from the observed pin timestamp; tick-aligned schedules remain persisted but `PENDING_RUNTIME_PROGRESS` until Game Session's `ObserveRuntimeTickProgress` input supplies the current runtime region epoch and tick id from Game Session's durable runtime ownership row. At that point Automation records the runtime region fields and derives the first future `dueTickId` as `observedTickId + cadenceValue`; later observations that reach or pass it admit the due candidate before creating a firing claim containing the current due point and its deterministic `scriptEventId`, then use the same durable work-item, audit, and queue projection path as other admitted script events. Plugin-owned schedules are materialized only for the plugin version currently enabled in Automation's runtime registry for that instance; other published or previously active plugin versions must not keep durable schedule ownership. Region-scoped Redis indexes such as `automation:timer:{tenantRegionTag}` remain a derived coordination layer above those durable rows rather than the first source of schedule identity.
- Leaders track these interval entries alongside other automation timers, using bounded scans and the automation tick budget (for example, `AUTOMATION_TICK_DURATION_MS`, `AUTOMATION_TICK_MAX_EVENTS`, `AUTOMATION_TICK_BUDGET_MS`) to decide which `onInterval` triggers should fire in each automation tick.
- When an interval becomes due, the scheduler evaluates the candidate through the same admission, quota, cadence, and budgeting layers described in [Scripting Quotas and Operations](./system-architecture-scripting-quotas-and-operations.md). Only an admitted candidate receives a durable firing claim and a deterministic `scriptEventId`; skipped candidates are recorded in metrics and the audit feed without a firing claim, while admitted candidates are enqueued for sandbox execution and advance the interval entry’s next due point.

### Leader Changes

- Leaders advance a per-region notion of time by consuming the tick heartbeat stream and tracking how far they have progressed. In addition to the current heartbeat `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`, schedulers maintain a checkpoint such as `script-scheduler:{tenantRegionTag}:lastTickId`. The opaque `{tenantRegionTag}` physically carries the full `<tenantId, gameInstanceId, regionId>` scope, while the payload stores `regionEpoch`, `latestTickId`, and the last `streamOffset`. Consumers must compare the stored epoch with the authoritative current epoch before reusing either progress value.
- When leadership changes, the new leader:
  - reads the checkpoint for the current `regionEpoch`;
  - rejects a checkpoint whose stored `regionEpoch` does not match the current heartbeat/status epoch. It must not walk old-epoch ticks or reuse old-epoch due points. The mismatch is recorded in scheduler audit with `reason=checkpoint_region_epoch_mismatch` and increments `automation_script_timer_runtime_fence_dropped_total{scope, script_category, eventType, reason="checkpoint_region_epoch_mismatch"}`;
  - discards the stale checkpoint and stream offset, rebuilds a current-epoch checkpoint from the authoritative durable status/heartbeat, and then reconciles durable due points under the bounded catch-up policy; and
  - for each timer entry in the region index `automation:timer:{tenantRegionTag}`, determines which current-epoch "every N ticks" boundaries were crossed during the gap. It admits each due candidate before claiming or inserting a durable firing claim keyed by the stable schedule-instance identity, current runtime scope, and tagged due point; only then may it enqueue an `onInterval` trigger.
- Any missed `onInterval` triggers are then enqueued at most once before the leader resumes normal scheduling from the latest `(regionEpoch, tickId)`, capped by `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME` per resume window. Candidates beyond this cap are intentionally truncated (coalesced or dropped) and surfaced via audit and metrics such as `automation_script_timer_catchup_truncated_total`.
- Catch-up truncation must use one stable fairness algorithm when due candidates exceed `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME`:
  - Build the candidate set ordered by `dueTickId ASC`, then `priorityTag` (`high`, `normal`, `background`), then stable schedule identity ASC.
  - Admit catch-up firings in round-robin passes across distinct `scheduleDefinitionId` values so one noisy schedule cannot consume the entire resume window before other overdue schedules get one chance to fire.
  - Within one schedule, admit at most one missed firing per pass, always the earliest remaining `dueTickId`.
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

- Durable timer identities must be version-scoped by the schedule definition that created them, including `scriptPatchVersion` and, for plugin-owned schedules, `pluginId` and `pluginVersionId`; they must not be keyed just by entity and cadence.
- Every compiled timer or interval definition must carry a stable `scheduleDefinitionId` generated by the Game Design compilation pipeline and persisted with the schedule metadata. This identifier represents one logical schedule definition within a patch or plugin version.
- A patch or plugin version must not contain duplicate `scheduleDefinitionId` values within the same owning scope; compilation and publish must fail deterministically if duplicates are present.
- Implementations may additionally persist a `scheduleSemanticsHash` or equivalent normalized fingerprint for debugging and migration visibility, but reconciliation authority is the stable `scheduleDefinitionId`.
- When an instance observes a newly pinned `scriptPatchVersion`, the scheduler must compare the durable schedules for the previously observed patch with those for the newly pinned patch before resuming timer admission. Plugin activation, disablement, rollback, and revocation perform the same comparison for `(pluginId, pluginVersionId)`.
- Schedules that do not exist in the newly pinned patch must be removed or tombstoned so they can no longer generate triggers.
- Schedules may be preserved across patch or plugin changes only when the old and new definitions share the same `scheduleDefinitionId`. Matching by cadence, node shape, entity binding, or other inferred semantic similarity is not sufficient.
- When a preserved schedule keeps the same `scheduleDefinitionId`, reconciliation must create or confirm the replacement `script_schedule_instances` entry keyed by the newly pinned `scriptPatchVersion` and, when applicable, `(pluginId, pluginVersionId)`, then retire the old version-owned entry before scheduling resumes. Replacement creation and retirement must commit as one atomic durable reconciliation result. If they cannot commit atomically, reconciliation must be a durable, resumable, idempotent operation keyed by the existing operation identity: retries must create or confirm the replacement before finalizing the old-row tombstone, and the old entry remains authoritative until the replacement is durable. An interrupted rollback must never leave the logical schedule without a live row. The new entry may carry forward schedule state, but must recalculate `nextTick` or `nextRunAt` from the normative timer resume rule. Reconciliation creates only the replacement schedule identity; it does not create a firing claim or `scriptEventId`. After a due candidate passes admission, the scheduler mints the new firing claim and derives its `scriptEventId`; it must never rewrite the old entry or reuse its trigger identity.
- The same rule applies to plugin activation, disable, rollback, and signer-revocation flows: any schedule owned by a displaced `pluginVersionId` must be removed or tombstoned before normal scheduling resumes for that plugin.
- Canceling outbox work items alone is insufficient for rollback safety; old-version timer schedules must also be reconciled so they cannot mint new `scriptEventId` values after the version has been displaced.

Plugin example:

- If plugin version `combat-helper@v4` and `combat-helper@v5` both compile a "reapply guard aura every 20 ticks" timer with `scheduleDefinitionId=combat-helper.guard-aura.v1`, reconciliation atomically replaces the `v4` entry with a `v5` entry, or durably resumes an idempotent replacement operation that creates or confirms `v5` before retiring `v4`. It recalculates the next due point from the resume rule before scheduling resumes; the `v5` entry receives a firing claim and `scriptEventId` only after a due candidate passes admission.
- If `combat-helper@v5` replaces that timer with a different logical schedule such as "pulse only while threat > 0" compiled to a different `scheduleDefinitionId`, the old timer owned by `v4` must be tombstoned and a new timer created for `v5`.

Under this model, durable script schedules, quotas, and firing-claim de-duplication live in PostgreSQL, while `automation:timer:{tenantRegionTag}`, `script-scheduler:{tenantRegionTag}:lastTickId`, and related coordination keys form a reset-tolerant coordination layer for interval state. Stored entries and reconciliation logic remain instance-aware even though the Redis keys are region-scoped for slotting. The combination of tick heartbeat, durable firing claims, epoch-validated checkpoints, and script patch versioning preserves both correctness and determinism across failures and leader changes: losing or resetting these Redis keys may delay or slightly reshuffle timer firings within the tail-loss envelope but must not change which scripts are eventually scheduled according to their stored configurations or cause duplicate logical trigger creation.

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
  - New triggers for the affected runtime scope are not admitted; attempts to schedule additional runs receive explicit backpressure outcomes (`skipped_reloading` during reload, `rollback_paused` during rollback pause). For low-rate external events, callers may retry with the same full applicable Trigger Identity, including the same `scriptEventId`, using bounded backoff (`maxAttempts`, `maxElapsedMs`, jitter) and should honor server retry hints such as `retryAfterMs`; audit records must remain keyed by Trigger Identity and must not multiply rows per retry.
  - In-flight runs remain bounded by each script’s configured `maxConcurrent` and `concurrencyPolicy` (for example, `queue_until_free`); any short per-script waiting queues are allowed to drain, but no new entries are added while `reloadState=RELOADING`.
  - Pending timer-based triggers that became due during reload remain in the scheduler’s timer indexes until version reconciliation completes, then resume only for schedules that still exist under the newly observed patch or plugin version with recalculated `nextTick` or `nextRunAt` so cadences remain coherent.
- On success, the new `scriptPatchVersion` becomes active for future triggers; on failure:
  - `activePatchVersion` remains unchanged and continues to govern live execution;
  - `pendingPatchVersion` is marked failed along with an error reason, and leaders discard any partially loaded state for that patch and resume scheduling using the existing `activePatchVersion`; and
  - triggers referencing a failed or unknown patch are rejected explicitly with `finalOutcome=version_unavailable` (with specific cause in `finalReason`) and metrics like `automation_script_triggers_dropped_total{reason="version_unavailable"}` rather than silently falling back to an older patch.

- Timer-based triggers such as `onInterval` and `onTimerExpire` always execute against the currently pinned `scriptPatchVersion` for the game at the moment they are evaluated; they do not continue running older definitions after a patch is promoted.
- Older script versions remain in the Automation & Scripting Service database for auditing and potential rollback, but only the pinned active version is used for live execution.
