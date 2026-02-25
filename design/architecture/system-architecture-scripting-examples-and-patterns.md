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

3. **Bindings and quotas**
   - The Automation & Scripting Service looks up all scripts bound to `onEnterRegion` for the target entity and tenant, using the version metadata provided by the Game Session Service to resolve the correct script definitions.
   - Per-script quotas and tenant budgets are applied before execution (see `design/architecture/system-architecture-scripting-quotas-and-operations.md` for details). Scripts that fail quota checks are skipped and logged; others proceed to sandboxed execution.

4. **Sandboxed DSL execution**
   - For each allowed script, the Automation & Scripting Service executes the `onEnterRegion` handler inside the sandboxed DSL runtime, walking the graph of condition, timer, and action nodes for the current event payload.
   - Typical patterns include:
     - Checking player or NPC state (faction, health, quest flags).
     - Branching into dialogue, combat, or flavor events.
     - Scheduling follow-up timers (for example, delayed emotes or encounter escalation).

5. **Automation queue staging**
   - Actions produced by the handler are converted into domain commands and persisted as a durable script work item (outbox), then indexed into `automation:queue:<tenantId>:<entityId>` for the affected entity.
   - Each work item carries the originating `scriptEventId`, `scriptId`, version metadata, and region context.

6. **Automation ticks and tick command enqueue**
   - `ScriptTickService` drains automation queues and batches work under `automation:tick:{tenantScriptTag}:...`.
   - It then hands the resulting commands to the Game Session Service over internal gRPC so Game Session can enqueue them into `tick:{tenantRegionTag}:queue:<entityId>` using the tick engine’s Lua registry and invariants.

7. **Execution, audit, and observability**
   - On subsequent ticks, the Game Session Service executes at most one command per entity per tick, so `onEnterRegion` effects follow the same fairness and conflict-resolution rules as player actions.
   - Metrics such as `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total` are updated throughout this flow; see the metrics glossary in `design/architecture/system-architecture-scripting-quotas-and-operations.md` for names and label conventions.
   - An audit record is written to `script_event_audit` with identifiers such as `scriptEventId`, `scriptId`, `tenantId`, `tickId`, plus stage-aware outcome fields (`finalStage`, `finalOutcome`, `finalReason`) so operators can distinguish “DSL evaluated” from “accepted into tick queues”, enabling replay and troubleshooting as described in the same quotas and operations document.

If the `scriptPatchVersion` pinned by the Game Session Service for a given game is later marked failed or unknown for that tenant, subsequent `onEnterRegion` triggers referencing it follow the reload failure behavior described in `design/architecture/system-architecture-scripting-quotas-and-operations.md` instead of the happy-path flow.

---

## Example: Periodic Patrol via `onInterval`

This example shows how a script that runs on a fixed cadence (for example, an NPC patrol) moves through the pipeline using `onInterval`. For the underlying timer and failover internals (including `automation:timer:{tenantRegionTag}` and `script-scheduler:{tenantRegionTag}:lastTickId`), see **End-to-End `onInterval` Timer Lifecycle** in `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`.

1. **Script configuration and publish**
   - A designer configures an NPC patrol script in the Game Design Service, binding an `onInterval` handler with a chosen cadence (for example, every N ticks) and a sequence of waypoints.
   - When the script is published, its compiled DSL graph, `intervalTicks` (or equivalent cadence configuration), and version metadata are stored in the Automation & Scripting Service database and exposed under the current `scriptPatchVersion` for that game.

2. **Scheduling the next interval**
   - When the NPC spawns or when the script is first loaded, the Automation & Scripting Service’s scheduler registers an interval entry for the `<tenantId, scriptId, entityId>` tuple, computing a `nextTick` or `nextRunAt` timestamp based on the configured cadence and current tick/time.
   - Leaders track these interval entries alongside other automation timers, using bounded scans and the automation tick budget (for example, `AUTOMATION_TICK_DURATION_MS`, `AUTOMATION_TICK_MAX_EVENTS`, `AUTOMATION_TICK_BUDGET_MS`) to decide which `onInterval` triggers should fire in each automation tick.

3. **Firing `onInterval` and enforcing budgets**
   - When an interval becomes due, the scheduler creates a `scriptEventId` for the `onInterval` trigger and evaluates it using the same quota, cadence, and budgeting layers described in `design/architecture/system-architecture-scripting-quotas-and-operations.md`.
   - If the script is outside its budgets or disabled, the trigger is skipped and recorded in both metrics and the audit feed.
   - If allowed, the scheduler enqueues the `onInterval` trigger for sandbox execution and updates the interval entry with a new `nextTick` or `nextRunAt`, ensuring the cadence remains stable even if some intervals are occasionally delayed by load.

4. **Sandbox execution and command enqueue**
   - The `onInterval` handler runs inside the sandboxed DSL engine, evaluating conditions such as “is the NPC currently out of combat?” and “is the patrol still active?” before deciding on the next waypoint or behavior.
   - Actions produced by the handler (for example, “move to the next patrol room,” “play an emote,” “schedule an `onTimerExpire` follow-up”) are converted into domain commands and persisted as a durable script work item (outbox), then indexed into `automation:queue:<tenantId>:<entityId>` for the affected entity.
   - Each work item carries the originating `scriptEventId`, `scriptId`, version metadata, and the **current region** for the entity at enqueue time.

5. **Execution, audit, and observability**
   - `ScriptTickService` later drains `automation:queue`, stages these events under `automation:tick:{tenantScriptTag}:...`, and hands the resulting commands to the Game Session Service over internal gRPC so Game Session can enqueue them into the appropriate `tick:{tenantRegionTag}:queue:<entityId>`.
   - On subsequent ticks, the Game Session Service executes at most one command per entity per tick, so patrol movements and emotes follow the same fairness and conflict-resolution rules as player actions.
   - Each fired interval contributes to `automation_script_triggers_total` (tagged with `eventType=onInterval`) and, if it produces work that is accepted into tick queues, increases `automation_tick_events_enqueued_total`. An audit record is written to `script_event_audit` so missed or delayed intervals can be debugged using stage-aware fields (`finalStage`, `finalOutcome`, `finalReason`) alongside identifiers like `scriptEventId`, `scriptId`, and `tickId`; see the metrics and audit sections in `design/architecture/system-architecture-scripting-quotas-and-operations.md` for interpretation.

As with `onEnterRegion`, reload failures or version issues are surfaced via specific outcomes (for example, `skipped_reloading`, `skipped_rollback_pause`, `version_unavailable`) and corresponding metrics, detailed in the quotas and operations document.

### Timer Reliability Notes

Timer-driven handlers such as `onInterval` follow the same **at-most-once per trigger** semantics described in the DSL reference:

- If an `onInterval` firing is skipped because of quotas, tenant budgets, cluster ceilings, or version issues, that specific firing is not automatically replayed later, although subsequent firings based on the cadence may still occur.
- If an admitted `onInterval` firing fails with `infrastructure_error`, lower layers may retry individual downstream operations in an idempotent way, but the DSL body is not re-executed for the same `scriptEventId`.
- Designers and operators should use `script_event_audit` and automation metrics to detect heavily throttled or consistently failing timers and adjust cadence, budgets, or script design as needed.
