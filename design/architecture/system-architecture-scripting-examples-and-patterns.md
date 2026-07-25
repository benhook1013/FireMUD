# FireMUD Scripting Examples & Patterns

This document provides **worked examples and design patterns** for common scripting scenarios. It shows how the concepts from the DSL and lifecycle reference apply to concrete behaviors.

Companion docs:

- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md` – terminology, DSL semantics, event and timer lifecycle, determinism.
- `design/architecture/system-architecture-scripting-quotas-and-operations.md` – sandboxing, quotas/budgets, operational flows.
- `design/architecture/system-architecture-scripting.md` – high-level hub and TL;DR flow.

## Table of Contents

- [Audience](#audience)
- [Example: `onEnterRegion` Script Execution](#example-onenterregion-script-execution)
- [Example: Periodic Patrol via `onInterval`](#example-periodic-patrol-via-oninterval)
- [Example: Instance-Scoped Plugin Activation](#example-instance-scoped-plugin-activation)

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
     - `tenantId`, `gameInstanceId`, and `regionId`.
     - Target `entityId` (for example, an NPC guarding the room).
     - `eventType=onEnterRegion`.
     - The currently pinned `scriptPatchVersion` for that game.
     - `regionEpoch` so the trigger is fenced across scoped coordination resets.
   - For low-rate lifecycle events such as `onEnterRegion`, `onSpawn`, and `onCommand`, simple unary gRPC calls are sufficient; high-volume time-based scheduling comes from the tick heartbeat stream described in the tick architecture.
   - The unary ingress result is **event-scope** only: it tells the caller whether the event was accepted for handler resolution. If multiple scripts/plugins are bound, handler-specific success or failure is recorded later per resolved Trigger Identity in `script_event_audit`.

3. **Bindings and quotas**
   - The Automation & Scripting Service looks up all scripts bound to `onEnterRegion` for the target entity and tenant, using the version metadata provided by the Game Session Service to resolve the correct script definitions.
   - Per-script quotas and tenant budgets are applied before execution (see `design/architecture/system-architecture-scripting-quotas-and-operations.md` for details). Handler-scoped quota is charged at handler admission; tenant runtime budget is charged only when a handler actually reserves sandbox capacity. Scripts that fail quota checks are skipped and logged; others proceed to sandboxed execution.

4. **Sandboxed DSL execution**
   - For each allowed script, the Automation & Scripting Service executes the `onEnterRegion` handler inside the sandboxed DSL runtime, walking the graph of condition, timer, and action nodes for the current event payload.
   - All gameplay-affecting reads in that handler use the same run snapshot token captured at admission for the trigger's committed `(gameInstanceId, regionId, regionEpoch, tick/read-version)` view; the handler must not silently mix fresher state mid-run.
   - Typical patterns include:
     - Checking player or NPC state (faction, health, quest flags).
     - Branching into dialogue, combat, or flavor events.
     - Scheduling follow-up timers (for example, delayed emotes or encounter escalation).

5. **Automation queue staging**
   - Actions produced by the handler are converted into domain commands and persisted as a durable script work item (outbox), then indexed into `automation:queue:{tenantInstanceTag}:<entityId>` for the affected entity.
   - Each work item carries the originating `scriptEventId`, `scriptId`, `gameInstanceId`, version metadata, and region context. Each emitted gameplay command also carries its deterministic `automationDispatchId`; one handler's fan-out commands must not share only the trigger identity. Automation records each attempted command in a separate `ListScriptHandoffEvents` child record keyed by that dispatch ID, while retaining one handler audit row.

6. **Automation ticks and tick command enqueue**
   - Automation's durable execution loop claims pending work items and hands emitted commands to Game Session.
   - It then hands the resulting commands to the Game Session Service over internal gRPC so Game Session can enqueue them into `tick:{tenantRegionTag}:queue:<entityId>` using the tick engine’s Lua registry and invariants.

7. **Execution, audit, and observability**
   - On subsequent ticks, the Game Session Service executes at most one command per entity per tick, so `onEnterRegion` effects follow the same fairness and conflict-resolution rules as player actions.
   - Metrics such as `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total` are updated throughout this flow; see the metrics glossary in `design/architecture/system-architecture-scripting-quotas-and-operations.md` for names and label conventions.
   - An audit record is written to `script_event_audit` for each resolved handler Trigger Identity, with identifiers such as `scriptEventId`, `scriptId`, `tenantId`, `gameInstanceId`, `regionId`, `regionEpoch`, and `tickId`, plus stage-aware outcome fields (`finalStage`, `finalOutcome`, `finalReason`) so operators can distinguish “DSL evaluated” from “accepted into tick queues”, enabling replay and troubleshooting as described in the same quotas and operations document.

### Mixed Fan-Out Example

One admitted event can still produce different outcomes per bound handler. For example:

- Game Session emits one `TriggerScriptEvent` for `eventType=onEnterRegion` with a single `scriptEventId`.
- The Automation & Scripting Service accepts that event at ingress, resolves three handlers, and creates three handler-scoped Trigger Identities.
- The first handler is admitted, evaluates successfully, and reaches `finalStage=TICK_HANDOFF`, `finalOutcome=success` after all of its commands are accepted; each handed-off command is identified separately by its own `automationDispatchId`.
- The second handler is rejected during admission with `finalStage=ADMISSION`, `finalOutcome=quota_denied`, `finalReason=per_script_window_exhausted`.
- The third handler is skipped with `finalStage=ADMISSION`, `finalOutcome=script_disabled`, `finalReason=admin_hard_disable`.

The first handler's audit remains one row for its Trigger Identity even if it emits two commands. Its separate command-handoff records are, for example:

| `automationDispatchId` | Parent `outboxWorkItemId` | Handoff outcome |
| --- | --- | --- |
| `work-9#0` | `work-9` | `accepted` |
| `work-9#1` | `work-9` | `accepted` |

If Game Session later fences only `work-9#1`, the command-handoff record for that dispatch gets the bounded version-fence disposition; the handler audit and the `work-9#0` sibling record remain unchanged.

In this case the unary ingress response still reports only that the event itself was accepted for handler resolution. Operators and replay tooling must inspect `script_event_audit` by Trigger Identity to understand the handler-level mix of success, denial, and skip outcomes for that one inbound event.

They must inspect the separate per-command handoff records by `automationDispatchId` for command-level outcomes.
For the handler-scoped idempotency and audit-row rule behind this fan-out behavior, see the **Idempotency & Retries** section in `design/architecture/microservices/automation-scripting-service/README.md`.

If the `scriptPatchVersion` pinned by the Game Session Service for a given game is later marked failed or unknown for that tenant, subsequent `onEnterRegion` triggers referencing it follow the reload failure behavior described in `design/architecture/system-architecture-scripting-quotas-and-operations.md` instead of the happy-path flow.

---

## Example: Periodic Patrol via `onInterval`

This example shows how a script that runs on a fixed cadence (for example, an NPC patrol) moves through the pipeline using `onInterval`. For the underlying timer and failover internals (including the region-scoped coordination keys `automation:timer:{tenantRegionTag}` and `script-scheduler:{tenantRegionTag}:lastTickId`, whose stored entries remain instance-aware), see **End-to-End `onInterval` Timer Lifecycle** in `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`.

1. **Script configuration and publish**
   - A designer configures an NPC patrol script in the Game Design Service, binding an `onInterval` handler with a chosen cadence (for example, every N ticks) and a sequence of waypoints.
   - When the script is published, its compiled DSL graph, `intervalTicks` (or equivalent cadence configuration), and version metadata are stored in the Automation & Scripting Service database and exposed under the current `scriptPatchVersion` for that game.

2. **Scheduling the next interval**
   - When the NPC spawns or when the script is first loaded, the Automation & Scripting Service's scheduler registers a stable interval row for `<tenantId, gameInstanceId, playableStateScope, scriptId, pluginId?, pluginVersionId?, eventType=onInterval, eventSchemaVersion, scriptPatchVersion, isDryRun=false, scheduleDefinitionId, targetScopeType, targetScopeId>`. Runtime `regionId`/`regionEpoch` and exactly one due point, `dueTickId` for tick cadence or `dueAt` for wall-clock cadence, are mutable schedule state rather than row identity. `nextTick` and `nextRunAt` are derived projections. Plugin-owned entries include both plugin owner fields.
   - Leaders track these interval entries alongside other automation timers, using bounded scans and the execution-budget knobs (for example, `AUTOMATION_TICK_DURATION_MS`, `AUTOMATION_TICK_MAX_EVENTS`, `AUTOMATION_TICK_BUDGET_MS`) to decide which `onInterval` triggers should fire in each scheduling window.

3. **Firing `onInterval` and enforcing budgets**
   - When an interval becomes due, the scheduler creates a durable firing claim from the stable schedule-row identity plus the applicable runtime region and epoch, target entity, trigger mode, and tagged due point. It derives `scriptEventId` from that complete firing identity and creates a per-command `automationDispatchId` during handoff. It evaluates the `onInterval` trigger using the same quota, cadence, and budgeting layers described in `design/architecture/system-architecture-scripting-quotas-and-operations.md`.
   - If multiple handlers are bound to the timer event, the timer firing is admitted once at event scope and then fans out into handler-scoped Trigger Identities whose outcomes are tracked independently.
   - If the script is outside its budgets or disabled, the trigger is skipped and recorded in both metrics and the audit feed.
   - If allowed, the scheduler enqueues the `onInterval` trigger for sandbox execution, advances the canonical due point (`dueTickId` for tick cadence or `dueAt` for wall-clock cadence) according to the cadence or resume rule, and then derives `nextTick` or `nextRunAt` from that due point. This keeps the cadence stable even if some intervals are occasionally delayed by load. If the timer survives a reload or rollback because the logical schedule is preserved, the next due point is recalculated from the canonical resume rule rather than by replaying the paused window.

4. **Sandbox execution and command enqueue**
   - The `onInterval` handler runs inside the sandboxed DSL engine, evaluating conditions such as “is the NPC currently out of combat?” and “is the patrol still active?” before deciding on the next waypoint or behavior.
   - Actions produced by the handler (for example, “move to the next patrol room,” “play an emote,” “schedule an `onTimerExpire` follow-up”) are converted into domain commands and persisted as a durable script work item (outbox), then indexed into `automation:queue:{tenantInstanceTag}:<entityId>` for the affected entity.
   - Before persistence, runtime output budgets cap how many commands and how many serialized bytes this single firing may emit. Oversized patrol firings fail as non-success outcomes rather than creating unbounded backlog.
   - Each work item carries the full applicable Trigger Identity, including `scriptEventId`, `scriptId`, `gameInstanceId`, `playableStateScope`, version metadata, and the **current region and epoch** for the entity at enqueue time. Each emitted command carries its `automationDispatchId` and target aggregate identity for downstream effect idempotency.

5. **Execution, audit, and observability**
   - Automation later claims the durable work items, uses `automation:queue` only as a rebuildable pointer index, and hands the resulting commands to the Game Session Service over internal gRPC so Game Session can enqueue them into the appropriate `tick:{tenantRegionTag}:queue:<entityId>`.
   - On subsequent ticks, the Game Session Service executes at most one command per entity per tick, so patrol movements and emotes follow the same fairness and conflict-resolution rules as player actions.
   - Each fired interval contributes to `automation_script_triggers_total` (tagged with `eventType=onInterval`) and, if it produces work that is accepted into tick queues, increases `automation_tick_events_enqueued_total`. An audit record is written to `script_event_audit` so missed or delayed intervals can be debugged using stage-aware fields (`finalStage`, `finalOutcome`, `finalReason`) alongside identifiers like `scriptEventId`, `scriptId`, `scheduleDefinitionId`, the persisted due point, and `tickId`.
   - The separate `ListScriptHandoffEvents` records carry each emitted command's `automationDispatchId` and later handoff/execution dispositions; the handler audit does not carry a single dispatch ID. See the metrics and audit sections in `design/architecture/system-architecture-scripting-quotas-and-operations.md` for interpretation.

As with `onEnterRegion`, reload failures or version issues are surfaced via specific outcomes (for example, `skipped_reloading`, `rollback_paused`, `version_unavailable`) and corresponding metrics, detailed in the quotas and operations document.

### Timer Reliability Notes

Timer-driven handlers such as `onInterval` follow the same **at-most-once per trigger** semantics described in the DSL reference:

- If an `onInterval` firing is skipped because of quotas, tenant budgets, cluster ceilings, or version issues, that specific firing is not automatically replayed later, although subsequent firings based on the cadence may still occur.
- If an admitted `onInterval` firing fails with `infrastructure_error`, lower layers may retry individual downstream operations in an idempotent way, but the DSL body is not re-executed for the same full applicable Trigger Identity.
- Designers and operators should use `script_event_audit` and automation metrics to detect heavily throttled or consistently failing timers and adjust cadence, budgets, or script design as needed.

### `scheduleDefinitionId` Example

`scheduleDefinitionId` is the stable compiled identity used to decide whether a logical timer survives publish, reload, or rollback:

- If patch `P11` and patch `P12` both define the NPC patrol timer as "run every 30 ticks while patrol is enabled" and Game Design emits the same `scheduleDefinitionId`, Automation & Scripting tombstones the `P11` version-owned timer row, claims or creates a `P12` row with the same logical `scheduleDefinitionId`, and carries its due state forward through the canonical resume rule. The new row gets a new trigger claim and `scriptEventId`; the old row and its trigger history are not reused.
- If patch `P12` replaces that patrol timer with a different logical schedule such as "run every 5 ticks while alerted" and the compiled `scheduleDefinitionId` changes, the old timer is tombstoned and a new timer is created. Rollback to `P11` follows the same rule in reverse: preserve only matching `scheduleDefinitionId` values, and recreate timers for schedules whose identity no longer matches.

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
