# FireMUD Scripting Examples & Patterns

This document provides **worked examples and design patterns** for common scripting scenarios. It shows how the concepts from the DSL and lifecycle reference apply to concrete behaviors.

All examples use the single DSL. Embedded scripts are game-owned patch content; the plugin example below demonstrates the separate immutable artifact and instance activation lifecycle without introducing a second language or trust path. See [One DSL, Distinct Artifact and Lifecycle Roles](./system-architecture-scripting-dsl-reference-and-lifecycle.md#one-dsl-distinct-artifact-and-lifecycle-roles).

Companion docs:

- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md` – terminology, DSL semantics, event and timer lifecycle, determinism.
- `design/architecture/system-architecture-scripting-quotas-and-operations.md` – sandboxing, quotas/budgets, operational flows.
- `design/architecture/system-architecture-scripting.md` – high-level hub and TL;DR flow.

## Table of Contents

- [Audience](#audience)
- [Implementation Status](#implementation-status)
- [Example: `onEnterRegion` Script Execution](#example-onenterregion-script-execution)
- [Example: Periodic Patrol via `onInterval`](#example-periodic-patrol-via-oninterval)
- [Example: Instance-Scoped Plugin Activation](#example-instance-scoped-plugin-activation)

---

## Implementation Status

These examples are target-state first. The target pre-DSL trigger lifecycle is `PENDING_EVALUATION` -> `EXECUTING` -> `EVALUATED_COMMITTED`; executor acceptance, the execution-start charge marker, and the fenced capacity lease commit before DSL evaluation. The current live runtime still accepts one emitted command per work item; its Game Session handoff does not carry `commandOrdinal` or the complete Trigger Identity end to end. Unresolved current-live `EVALUATING` work remains fail-closed and active, and the live runtime lacks `EVALUATED_COMMITTED` descriptor replay without DSL re-entry. See [Cross-Service Contracts](./system-architecture-scripting-contracts.md#implementation-status), the [normative Command-Handoff Identity](./system-architecture-scripting-normative-contract-tables.md#command-handoff-identity-target-state), [ADR 0063](./decisions/adr-0063-durable-per-dispatch-script-handoff.md), and the [automation and scheduler runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status).

---

## Audience

- **Game designers and content authors**
  - Use these examples as blueprints for common behaviors (entrance triggers, patrols, etc.).
  - Pair with the Game Design Service’s visual editor documentation to map nodes and edges to the described flows.

- **Implementers and backend developers**
  - Use the examples to understand how events, quotas, and automation queues interact across services.
  - Refer back to `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md` for definitions and lifecycle details.

---

## Example: `onEnterRegion` Script Execution

This example walks through how a typical `onEnterRegion` script executes end-to-end, from a player movement to automation queues and tick commands.

1. **Player moves and region change commits**
   - A player moves into a new room or region. The Game Session Service processes the movement command under the usual tick and transaction rules and commits the region change.

2. **`onEnterRegion` event is emitted**
   - After the move is committed and the player is now in the new region, the Game Session Service emits an `onEnterRegion` **script event** to the Automation & Scripting Service over gRPC.
   - Conceptually this is a unary `TriggerScriptEvent` call on the Automation & Scripting Service that carries:
     - `tenantId`, `gameInstanceId`, `playableStateScope`, and `regionId`.
     - Target `entityId` (for example, an NPC guarding the room).
     - `eventType=onEnterRegion`.
     - The exact currently pinned `(scriptPatchVersion, scriptPinEpoch)` tuple for that game.
     - `regionEpoch` so the trigger is fenced across scoped coordination resets.
   - For low-rate lifecycle events such as `onEnterRegion`, `onSpawn`, and `onCommand`, simple unary gRPC calls are sufficient; high-volume time-based scheduling comes from the tick heartbeat stream described in the tick architecture.
   - The unary ingress result is **event-scope** only: it tells the caller whether the event was accepted for handler resolution. If multiple scripts/plugins are bound, handler-specific success or failure is recorded later per resolved Trigger Identity in `script_event_audit`.

3. **Bindings and quotas**
   - The Automation & Scripting Service looks up all scripts bound to `onEnterRegion` for the target entity and tenant, using the version metadata provided by the Game Session Service to resolve the correct script definitions.
   - Per-script quotas and tenant budgets are applied before execution under the [scripting quota lifecycle](./system-architecture-scripting-quotas-and-operations.md#budget-accounting-rules). The quota owner records one handler admission charge; queued work holds no capacity, and the execution-start marker is recorded only when a separately fenced, reclaimable capacity lease is acquired. A per-run timeout is a separate execution guard, not the aggregate tick-window capacity. Authoritative quota or occupancy denials are skipped and logged with their typed handler outcomes; infrastructure that cannot produce the decision follows the canonical non-OK gRPC classification.

4. **Sandboxed DSL execution**
   - For each allowed script, the Automation & Scripting Service executes the `onEnterRegion` handler inside the sandboxed DSL runtime, walking the graph of condition, timer, and action nodes for the current event payload.
   - All gameplay-affecting reads in that handler use the durable handler-scoped input manifest owned by the [DSL lifecycle contract](./system-architecture-scripting-dsl-reference-and-lifecycle.md#read-consistency-contract): owner-versioned bounded inputs and their causal floor are reused on retry, and an owner-specific `readSnapshotToken`, when required, is only one manifest input or correlation value rather than universal authority. The current implementation remains narrower and does not yet seal the complete manifest.
   - Typical patterns include:
     - Checking player or NPC state (faction, health, quest flags).
     - Branching into dialogue, combat, or flavor events.
     - Scheduling follow-up timers (for example, delayed emotes or encounter escalation).

5. **Automation queue staging**
   - Actions produced by the handler are converted into domain commands and persisted as a durable script work item (outbox), then indexed into `automation:queue:{tenantInstanceTag}:<entityId>` for the affected entity.
   - The firing admission links the durable firing claim, applicable resolved-handler audit rows, work items and queue-pointer projections only for quota-allowed handlers, and due-point advancement in one atomic durable transition or a resumable idempotent recovery operation. Zero-handler firings create no handler audit, work-item, or queue-pointer projection; quota-denied handlers create an audit row but no work item or queue-pointer projection. Firing-claim and queue-link recovery completes missing links from the existing claim rather than re-evaluating the handler.
   - Each work item carries the originating `scriptEventId`, `scriptId`, `gameInstanceId`, `playableStateScope`, exact `scriptPatchVersion`/`scriptPinEpoch`, and region context. In the target handoff contract, each emitted child is keyed by the complete source runtime scope plus any distinct target runtime scope, the persisted `automationDispatchId`, and deterministic `commandOrdinal` from canonical output order; both child identities are reused for every handoff, retry, and downstream disposition. The full parent Trigger Identity (including `bindingId` where applicable), `outboxWorkItemId`, and `scriptEventId` remain correlation only, while bounded `workItemIds[]` are parent-row selectors rather than child identities. The target `ListScriptHandoffEvents` child records are keyed by the complete Command-Handoff Identity, with `(automationDispatchId, commandOrdinal)` retained only as its dispatch-group suffix, and retain the full parent Trigger Identity while the handler audit remains one row. See [Implementation Status](#implementation-status) for the current live handoff boundary.

6. **Automation ticks and tick command enqueue**
   - Automation's durable execution loop claims pending work items and hands emitted commands to Game Session.
   - It then hands the resulting commands to the Game Session Service over internal gRPC so Game Session can enqueue them into `tick:{tenantRegionTag}:queue:<entityId>` using the tick engine’s Lua registry and invariants.

7. **Execution, audit, and observability**
   - On subsequent ticks, the Game Session Service selects at most one root actor action per eligible entity in the `actor_action` lane; the separately bounded `passive_effect` lane includes passive, inbound, and actor-generated effect sources without consuming that actor-action slot. Both lanes follow their canonical fairness and conflict-resolution rules.
   - Metrics such as `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total` are updated throughout this flow; see the metrics glossary in `design/architecture/system-architecture-scripting-quotas-and-operations.md` for names and label conventions.
   - An audit record is written to `script_event_audit` for each resolved handler Trigger Identity, with identifiers such as `scriptEventId`, `scriptId`, `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, and `tickId`, plus stage-aware outcome fields (`finalStage`, `finalOutcome`, `finalReason`) so operators can distinguish “DSL evaluated” from “accepted into tick queues”, enabling replay and troubleshooting as described in the same quotas and operations document.

### Mixed Fan-Out Example

The per-handler outcome mix is canonical, but the two-command child handoff shown below is **target-state only**; see [Implementation Status](#implementation-status).

One admitted event can still produce different outcomes per bound handler. For example:

- Game Session emits one `TriggerScriptEvent` for `eventType=onEnterRegion` with a single `scriptEventId`.
- The Automation & Scripting Service accepts that event at ingress, resolves three handlers, and creates three handler-scoped Trigger Identities.
- The first handler is admitted, evaluates successfully, and reaches `finalStage=TICK_HANDOFF`, `finalOutcome=handoff_accepted` after every required child dispatch is durably accepted; its later gameplay results remain per dispatch. The target Command-Handoff Identity carries the complete source/target scope together with one persisted `automationDispatchId` and distinct `commandOrdinal` values for its commands; retries reuse those identities.
- The second handler is rejected during admission with `finalStage=ADMISSION`, `finalOutcome=quota_denied`, `finalReason=per_script_window_exhausted`.
- The third handler is skipped with `finalStage=ADMISSION`, `finalOutcome=script_disabled`, `finalReason=admin_hard_disable`.

The first handler's audit remains one row for its Trigger Identity even if it emits two commands. Each separate command-handoff record is keyed by the complete source runtime scope plus any distinct target runtime scope, the persisted dispatch ID, and deterministic ordinal; parent Trigger Identity, `outboxWorkItemId`, and `scriptEventId` remain correlation only. The records are, for example:

| Source runtime scope | Distinct target runtime scope | `automationDispatchId` | `commandOrdinal` | Handoff outcome |
| --- | --- | --- | --- | --- |
| `{tenant-7, instance-4, shared, region-2, epoch-19}` | absent | `dispatch-9` | `0` | `accepted` |
| `{tenant-7, instance-4, shared, region-2, epoch-19}` | absent | `dispatch-9` | `1` | `accepted` |

If Game Session later fences the command whose dispatch/ordinal suffix is `(automationDispatchId=dispatch-9, commandOrdinal=1)` within the applicable complete Command-Handoff Identity, that command-handoff record gets the bounded version-fence disposition; the handler audit and the `(automationDispatchId=dispatch-9, commandOrdinal=0)` sibling identity remain unchanged.

In this case the unary ingress response still reports only that the event itself was accepted for handler resolution. Operators and replay tooling must inspect `script_event_audit` by Trigger Identity to understand the handler-level mix of success, denial, and skip outcomes for that one inbound event.

They must inspect the separate per-command handoff records by their complete Command-Handoff Identity; `(automationDispatchId, commandOrdinal)` is only the display and dispatch-group suffix for that lookup, while parent Trigger Identity, `outboxWorkItemId`, `workItemIds[]`, and `scriptEventId` cannot replace the child identity.
For the handler-scoped idempotency and audit-row rule behind this fan-out behavior, see the **Idempotency & Retries** section in `design/architecture/microservices/automation-scripting-service/README.md`.

If the `scriptPatchVersion` pinned by the Game Session Service for a given game is later marked failed or unknown for that tenant, subsequent `onEnterRegion` triggers referencing it follow the reload failure behavior described in `design/architecture/system-architecture-scripting-quotas-and-operations.md` instead of the happy-path flow.

---

## Example: Periodic Patrol via `onInterval`

This example shows how a script that runs on a fixed cadence (for example, an NPC patrol) moves through the pipeline using `onInterval`. For the underlying timer and failover internals (including the region-scoped coordination keys `automation:timer:{tenantRegionTag}` and `script-scheduler:{tenantRegionTag}:lastTickId`, whose stored entries remain instance-aware), see **End-to-End `onInterval` Timer Lifecycle** in `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`.

1. **Script configuration and publish**
   - A designer configures an NPC patrol script in the Game Design Service, binding an `onInterval` handler with a chosen cadence (for example, every N ticks) and a sequence of waypoints.
   - When the script is published, its compiled DSL graph, `intervalTicks` (or equivalent cadence configuration), and version metadata are stored in the Automation & Scripting Service database and exposed under the current `scriptPatchVersion` for that game.

2. **Scheduling the next interval**
   - When the NPC spawns or when the script is first loaded, the Automation & Scripting Service's scheduler registers a durable interval row retaining patch/plugin provenance. A wall-clock row carries exactly one due point; a tick-aligned row may remain `PENDING_RUNTIME_PROGRESS` without a due point until Game Session supplies runtime region/epoch and tick progress. Its stable continuity key is `{stableOwnerKind, stableOwnerId, scheduleDefinitionId, targetScopeType, targetScopeId}`; `scriptPatchVersion`, `scriptPinEpoch`, and `pluginVersionId` remain provenance and execution-fence metadata, while `scheduleSemanticsHash` is diagnostic evidence only. The default transition is cancel-and-recreate; continuity eligibility requires explicit compatible declarations on both sides, `source.isDryRun == target.isDryRun`, and typed cadence/target checks. Runtime `regionId`/`regionEpoch` and due state remain mutable schedule state rather than continuity identity.
   - Leaders track these interval entries alongside other automation timers using bounded scans. Event gates, the durable firing claim, handler resolution, and quota decisions happen first; quota-allowed handlers become durable queued work. The later execution scheduler applies `AUTOMATION_TICK_BUDGET_MS` to the canonical ordered prefix of queued handler work. This reservation is not actual execution time, a per-run timeout, or a capacity lease.

3. **Firing `onInterval` and enforcing budgets**
   - When an interval becomes due, the scheduler first applies event-scope candidate gates (cadence, reload/version visibility, and applicable event-policy checks); a gate failure is recorded by a deterministic `scheduleCandidateId` derived from the complete event-scope due-candidate identity: tenant/instance/playable scope, stable schedule-owned script/plugin/binding identity when present, region and region epoch, exact script patch and pin epoch, event type and schema version, dry-run namespace, schedule definition, trigger mode, target selector when applicable, and tagged due point. It creates no firing claim, `scriptEventId`, or handler identity. Only after those gates pass does it create a durable firing claim from that same complete identity and derive `scriptEventId` from it. After handlers are bound, the quota owner records each handler charge; a quota denial is handler-scoped `ADMISSION/quota_denied` with no work item, lease, or execution marker, while quota-allowed handlers create durable queued work. The later execution scheduler considers queued handler work in canonical order for the artifact-estimate prefix reservation; its remainder remains durably queued/deferred. When a handler is selected, a separately fenced capacity lease is acquired and its fence, executor acceptance, execution-start marker, and `EXECUTING` transition commit in one Automation-owned durable transaction before evaluation. The firing transition records denied handlers and admitted handler work/queue projections without claiming every handler has a work item; firing-claim and queue-link retries do not re-run the DSL, while eligible pre-`EVALUATED_COMMITTED` evaluation recovery may re-run the frozen DSL under the original identity and evidence.
   - If multiple handlers are bound to the timer event, the timer firing is admitted once at event scope and then fans out into handler-scoped Trigger Identities whose outcomes are tracked independently.
   - If an event-scope candidate gate fails, the due candidate is skipped and recorded in both metrics and an event-scope audit record keyed by the same complete event-scope `scheduleCandidateId` identity: tenant/instance/playable scope, stable schedule-owned script/plugin/binding identity when present, region and region epoch, exact script patch and pin epoch, event type and schema version, dry-run namespace, schedule definition, trigger mode, target selector when applicable, and tagged due point. The skip must atomically consume the candidate by advancing its canonical due point, or quarantine/tombstone the schedule when safe advancement is impossible; it creates no firing claim, handler Trigger Identity, or `scriptEventId` and cannot be rediscovered on a later scan. Handler quota denial after a firing claim is instead recorded under that handler's full Trigger Identity and does not consume the candidate as an event-scope skip.
   - If the event-scope candidate is allowed, the scheduler enqueues the `onInterval` trigger for sandbox execution, advances the canonical due point (`dueTickId` for tick cadence or `dueAt` for wall-clock cadence) according to the cadence or resume rule, and then derives `nextTick` or `nextRunAt` from that due point. This keeps the cadence stable even if some intervals are occasionally delayed by load. If the timer survives a reload or rollback because the logical schedule is preserved, the next due point is recalculated from the canonical resume rule rather than by replaying the paused window.

4. **Sandbox execution and command enqueue**
   - The `onInterval` handler runs inside the sandboxed DSL engine, evaluating conditions such as “is the NPC currently out of combat?” and “is the patrol still active?” before deciding on the next waypoint or behavior.
   - Actions produced by the handler (for example, “move to the next patrol room,” “play an emote,” “schedule an `onTimerExpire` follow-up”) are converted into domain commands and persisted as a durable script work item (outbox), then indexed into `automation:queue:{tenantInstanceTag}:<entityId>` for the affected entity.
   - Before persistence, runtime output budgets cap how many commands and how many serialized bytes this single firing may emit. Oversized patrol firings fail as non-success outcomes rather than creating unbounded backlog.
   - Each work item carries the full applicable Trigger Identity, including `scriptEventId`, `scriptId`, `gameInstanceId`, `playableStateScope`, version metadata, and the immutable trigger-created `regionId` and `regionEpoch`. If current entity state is needed to route the work at enqueue time, persist separate fields such as `enqueueRegionId` and `enqueueRegionEpoch`; never overwrite the original trigger fields. Deduplication, fencing, and replay continue to use the original `regionId` and `regionEpoch`. In the target handoff contract, each emitted child uses the complete source runtime scope plus any distinct target runtime scope, a persisted `automationDispatchId`, and deterministic `commandOrdinal` from canonical output order; retries reuse that complete child identity. Parent Trigger Identity, `outboxWorkItemId`, and `scriptEventId` remain correlation only. See [Implementation Status](#implementation-status) for the current live handoff boundary.

5. **Execution, audit, and observability**
   - Automation later claims the durable work items, uses `automation:queue` only as a rebuildable pointer index, and hands the resulting commands to the Game Session Service over internal gRPC so Game Session can enqueue them into the appropriate `tick:{tenantRegionTag}:queue:<entityId>`.
   - On subsequent ticks, the Game Session Service selects at most one root actor action per eligible entity in the `actor_action` lane; the separately bounded `passive_effect` lane includes passive, inbound, and actor-generated effect sources without consuming that actor-action slot. Patrol movements and emotes follow the canonical lane fairness and conflict-resolution rules.
   - For this live flow, `automation_script_triggers_total{eventType=onInterval}` increments once per resolved live handler using its applicable outcome, not once per interval. An admitted live event that resolves zero handlers increments once at event scope with metric-only `outcome=admitted_no_handlers`; this example does not imply dry-run metrics or execution. If a resolved handler produces work that is accepted into tick queues, `automation_tick_events_enqueued_total` increases. An audit record is written to `script_event_audit` per resolved handler so missed or delayed intervals can be debugged using stage-aware fields (`finalStage`, `finalOutcome`, `finalReason`) alongside identifiers like `scriptEventId`, `scriptId`, `scheduleDefinitionId`, the persisted due point, and `tickId`.
   - The target `ListScriptHandoffEvents` view composes downstream execution dispositions keyed by the complete source/optional-target Command-Handoff Identity, with persisted `automationDispatchId` and deterministic `commandOrdinal`; `(automationDispatchId, commandOrdinal)` is only its suffix. Parent Trigger Identity, `outboxWorkItemId`, `workItemIds[]`, and `scriptEventId` are correlation/selectors only and cannot replace that child identity. See [Implementation Status](#implementation-status) for the current live read boundary and the metrics and audit sections in `design/architecture/system-architecture-scripting-quotas-and-operations.md` for interpretation.

As with `onEnterRegion`, reload failures or version issues are surfaced via specific outcomes (for example, `skipped_reloading`, `rollback_paused`, `version_unavailable`) and corresponding metrics, detailed in the quotas and operations document.

### Timer Reliability Notes

Recurring timer-driven handlers such as `onInterval` produce at most one event-scoped durable firing per due candidate; that firing may fan out to zero or more resolved handler work items, each keyed by its own full Trigger Identity:

- If an `onInterval` candidate is skipped because of cadence, reload, version, or event-policy gates, that candidate is atomically consumed by due-point advancement or quarantine/tombstoning and is not automatically replayed later, although subsequent firings based on the cadence may still occur. A handler quota denial after firing admission is recorded under the handler Trigger Identity and does not create work, but it is not an event-scope candidate skip.
- **Target-state recovery:** Before `EVALUATED_COMMITTED`, a failed physical evaluation attempt may retry under the same full applicable Trigger Identity and, for emitted children, must converge on the same complete source/optional-target Command-Handoff Identity with its persisted `automationDispatchId` and deterministic `commandOrdinal` from canonical output order. After that descriptor-commit boundary, recovery replays the durable descriptors and does not re-enter the DSL; independently idempotent downstream operations may still retry. See [Implementation Status](#implementation-status) for the current live descriptor-recovery boundary.
- Designers and operators should use the event-scope candidate audit for pre-admission skips, `script_event_audit` for admitted handler outcomes, and automation metrics to detect heavily throttled or consistently failing timers and adjust cadence, budgets, or script design as needed.

### `scheduleDefinitionId` Example

`scheduleDefinitionId` participates in the stable compiled continuity key but does not by itself decide whether a logical timer survives publish, reload, or rollback. Initial materialization and default/incompatible reset transitions create a new physical durable row and retire the displaced row. A valid explicitly declared, typed-compatible continuity transition instead preserves the existing stable row, rewrites its target patch/plugin provenance and exact pin/epoch fencing metadata in place, and recalculates its due point without creating a replacement row or tombstone; the logical key must not be confused with a row or firing-claim identity:

For carry-forward, match the stable schedule-instance identity and the stable logical continuity key `{stableOwnerKind, stableOwnerId, scheduleDefinitionId, targetScopeType, targetScopeId}` within the applicable tenant/instance scope, then apply the explicit typed compatibility checks. `scheduleSemanticsHash` and displaced/replacement patch or plugin versions are transition-mapping provenance and exact execution-fence evidence, not immutable continuity identity; matching only `scheduleDefinitionId` or semantic similarity is insufficient.

- If patch `P11` and patch `P12` both define the NPC patrol timer as "run every 30 ticks while patrol is enabled", both explicitly declare the same stable continuity key, `source.isDryRun == target.isDryRun`, and cadence, target, and playable scope are compatible, Automation preserves the existing stable row, rewrites its target patch/plugin provenance and exact pin/epoch fencing metadata in place, and applies the resume calculation without creating a replacement row or tombstone. The in-place update is atomic or resumable/idempotent. After a due candidate passes admission, the scheduler creates a separate firing claim and `scriptEventId` keyed by the complete candidate identity; the stable schedule row remains distinct from the firing claim and event identity, while the old owner history is not reused as a new row.
- If either side omits continuity, explicitly resets it, changes cadence/target/scope/key, or the target definition is absent, the scheduler retires/tombstones P11. When the P12 definition is absent, it creates no P12 replacement row, due state, firing claim, or `scriptEventId`; only a validated replacement definition, after target schedule reconciliation, may receive fresh due state and proceed to exact admission. Existing P11 firing claims and candidate audits retain their original exact patch/epoch and resume-window metadata; only pending firing claims may be fenced or terminal-marked, while candidate-audit identities and outcomes remain immutable and are never rewritten or reused by P12. P12 claims are created only after target schedule reconciliation and exact admission. Rollback to P11 follows the same matrix; hash equality or historical similarity never grants continuity.

---

## Example: Instance-Scoped Plugin Activation

This example shows how one published plugin version is activated for one running game instance without changing other instances for the same tenant.

1. **Plugin version is uploaded and published**
   - A creator uploads plugin bundle `town-crier-v3` through the Game Design Service.
   - Game Design verifies signatures, extracts `plugin-manifest.json`, validates bindings against `baseVersionId=66666666-6666-4666-8666-666666666666`, and records the version as `PUBLISHED`.

2. **Instance-scoped activation is requested**
   - An operator uses Logging & Admin to call `SetPluginActiveVersion` for `<tenantId=11111111-1111-4111-8111-111111111111, gameInstanceId=44444444-4444-4444-8444-444444444444, pluginId=town-crier, targetPluginVersionId=town-crier-v3>`.
   - Another instance for the same tenant, such as `55555555-5555-4555-8555-555555555555`, is unaffected because plugin activation is scoped to one `(tenantId, gameInstanceId, pluginId)`.

3. **Runtime compatibility gates are enforced**
   - Automation & Scripting loads the published plugin metadata and verifies that:
     - `town-crier-v3` is `PUBLISHED`.
     - The instance runtime version is exactly `66666666-6666-4666-8666-666666666666`.
     - The instance’s bound ability schema digest matches the plugin’s recorded `abilitySchemaDigest`.
   - If any of these checks fail, activation is rejected deterministically and the active plugin state for `44444444-4444-4444-8444-444444444444` is unchanged.

4. **Bindings resolve for the activated instance only**
   - Suppose the plugin manifest contains a binding:
     - `eventType=onEnterRegion`
     - `targetScopeType=REGION`
     - `targetSelector={"regionTemplateId":"regionTemplateId:market-square"}`
     - `entrypointGraphId=announce-arrival`
     - `bindingId=announce-on-enter-market`
   - After activation, only triggers occurring inside `44444444-4444-4444-8444-444444444444` that match `regionTemplateId:market-square` resolve this plugin binding. The same tenant’s other instance `55555555-5555-4555-8555-555555555555` does not resolve the plugin unless it separately activates the same `pluginVersionId`.

5. **Trigger execution and audit**
   - When a player enters `market-square` in `44444444-4444-4444-8444-444444444444`, Game Session emits the event to Automation & Scripting.
   - Automation resolves the active plugin binding for `44444444-4444-4444-8444-444444444444`, executes graph `announce-arrival`, and records the resulting handler activity in `script_event_audit` with `pluginId=town-crier`, `pluginVersionId=town-crier-v3`, and `bindingId=announce-on-enter-market`.

6. **Rollback remains instance-scoped**
   - If `town-crier-v3` misbehaves in `44444444-4444-4444-8444-444444444444`, Logging & Admin can disable or roll back that plugin only for `44444444-4444-4444-8444-444444444444`.
   - Any other instance continues using its own separately activated plugin state.
