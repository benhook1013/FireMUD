# FireMUD Scripting DSL & Event Lifecycle

This document focuses on **how scripts are represented and executed** in FireMUD: the terminology used across services, the DSL’s semantics, and the rules that keep behavior deterministic and replayable.

It is a companion to:

- `design/architecture/system-architecture-scripting.md` – high-level hub for scripting and automation.
- `design/architecture/scripting-examples-and-patterns.md` – worked examples and common behaviors.
- `design/architecture/scripting-quotas-and-operations.md` – sandboxing, quotas, and operational guidance.

For service-level implementation details, also see:

- Automation & Scripting Service README: `design/architecture/microservices/automation-scripting-service/README.md`
- Tick System and Runtime Design: `design/architecture/system-architecture-ticks.md`
- Versioning & Runtime Configuration: `design/architecture/system-architecture-versioning-runtime.md`

## Table of Contents

- [Audience](#audience)
- [Terminology Glossary](#terminology-glossary)
- [Versioning Terms](#versioning-terms)
- [Script Execution Lifecycle (Terminology)](#script-execution-lifecycle-terminology)
- [`scriptEventId` Lifecycle and Deduplication](#scripteventid-lifecycle-and-deduplication)
- [Supported Script Events](#supported-script-events)
- [Scripting DSL](#scripting-dsl)
- [Determinism & Allowed Non-Determinism](#determinism--allowed-non-determinism)
- [Integration with Game Logic & Tick System](#integration-with-game-logic--tick-system)
- [Script Timers vs Tick Timers](#script-timers-vs-tick-timers)
- [Scheduler Leadership & Coordination](#scheduler-leadership--coordination)
- [Hot Reload & Resume Behavior](#hot-reload--resume-behavior)
- [Failure Modes and Error Handling](#failure-modes-and-error-handling)

---

## Audience

- **Game designers and content authors**
  - Use this document to understand what scripts can express, how events and timers are modeled, and how publish and hot-reload behave conceptually.
  - Pair with `design/architecture/scripting-examples-and-patterns.md` for concrete patterns.

- **Implementers and backend developers**
  - Use this document as the reference for terminology, event lifecycles, DSL semantics, determinism rules, and scheduler behavior.
  - Pair with `design/architecture/system-architecture-ticks.md` and `design/architecture/system-architecture-transactions.md` for cross-cutting concerns.

---

## Terminology Glossary

- **Game tick** – a region-scoped tick in the Game Session Service. Each `<tenantId, regionId>` advances through a monotonic `tickId` stream; game ticks are authoritative for gameplay state changes and use `tick:{tenantRegionTag}:...` keys and locks as described in [Tick System and Runtime Design](./system-architecture-ticks.md).
- **Automation/script tick** – a batching cycle inside the Automation & Scripting Service. `ScriptTickService` drains **script work items** from Redis-backed queues such as `automation:queue:<tenantId>:<entityId>`, stages them under `automation:tick:{tenantScriptTag}:...`, and enqueues resulting **tick commands** into per-entity tick command queues for later execution by game ticks. Automation ticks control script-side quotas and batching, not authoritative game state.
- **Automation queue** – a per-tenant, per-entity Redis queue (`automation:queue:<tenantId>:<entityId>`) that holds **post-handler script work items** (domain commands plus script metadata such as `scriptEventId`, `scriptId`, and version information) after sandboxed DSL execution and before automation ticks convert them into tick commands and move them into tick-compatible queues.
- **Tick heartbeat** – a **gRPC streaming feed** produced by the Game Session Service that reports `tickId` progression per `<tenantId, regionId>`. The script scheduler consumes this heartbeat over a long-lived gRPC stream to count “every N ticks” intervals and align `onInterval` triggers with the canonical game tick timeline without owning tick execution itself. See [Tick Events & Heartbeat Stream](./system-architecture-ticks.md#tick-events--heartbeat-stream) for transport details.

### Versioning Terms

These definitions summarize how common versioning concepts are used in scripting; the full model lives in [System Architecture: Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).

- **`scriptPatchVersion`** – a logical script-only patch identifier tracked per tenant/game (for example in the Game Session Service as `script_patch_version`). It pins which published script set is considered active at runtime so all triggers and timers execute against a consistent script configuration.
- **`versionId`** – an internal identifier for a concrete compiled script or component version. `versionId` values distinguish individual revisions within a `scriptPatchVersion` and are used by the Automation & Scripting Service to load the exact behavior that should run for a given trigger.
- **`runtimeStatus`** – the current runtime state of a script as seen by the scheduler (for example, `ENABLED`, `DISABLE_AFTER_DRAIN`, `DISABLED`, `DISABLED_DUE_TO_ERRORS`). `runtimeStatus` controls whether new triggers are accepted, drained, or skipped and is updated by hot reload flows and administrative actions.

---

## Script Execution Lifecycle (Terminology)

The scripting pipeline uses a small set of terms repeatedly; the table below summarizes them and how they relate:

| Step | Term | Description | Stored as / example |
| --- | --- | --- | --- |
| 1 | **Trigger** | A concrete event such as `onEnterRegion`, `onCommand`, or a custom event emitted by a service. | gRPC `TriggerScriptEvent` call, tick heartbeat, or internal scheduler event. |
| 2 | **DSL run** | Execution of a script handler in the sandboxed DSL for a single trigger. Produces domain commands, not direct state changes. | In-memory execution in the Automation & Scripting Service; results summarized as script work items. |
| 3 | **Script work item** | A post-DSL, per-entity descriptor of what should happen (domain commands + `scriptEventId`, `scriptId`, version metadata). | Enqueued in `automation:queue:<tenantId>:<entityId>` and staged under `automation:tick:{tenantScriptTag}:...`. |
| 4 | **Tick command** | A concrete command that the Game Session Service executes during game ticks under its normal locking and idempotency rules. | Enqueued into `tick:{tenantRegionTag}:queue:<entityId>` for consumption by the tick loop. |

Triggers lead to DSL runs, which produce script work items in the automation queues, which automation ticks turn into tick commands for the Game Session Service.

---

## Game Design vs Automation & Scripting Responsibilities

Two services collaborate to deliver scripting and automation:

- **Game Design Service**
  - Owns the **authoring UX**: the visual DSL editor, component palettes, world-generation triggers, and per-tenant configuration screens.
  - Manages **draft and published configurations** for scripts, event bindings, and world-generation presets inside its own schema, including version history and “upgrade available” hints when components change.
  - Controls the **publish lifecycle** for scripts and component graphs: designers edit drafts, run validations, and then publish a new `scriptPatchVersion` tied to a `baseVersionId` as described in [System Architecture: Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#script-only-patch-versions).
  - Drives a **cross-service Saga** when a script version is published:
    - Writes the final, validated script graphs and bindings into its own tables.
    - Starts a Saga that upserts the compiled script definitions, event bindings, and any world-generation hooks into the Automation & Scripting Service schema for the target `<tenantId, scriptPatchVersion>`.
    - On success, marks the version as `PUBLISHED` and calls `NotifyScriptVersionUpdate` so the Automation & Scripting Service reloads runtime state.
    - On failure, rolls back or marks the publish as `FAILED`, keeping the prior `scriptPatchVersion` as the active one for that game.

- **Automation & Scripting Service**
  - Owns the **compiled graph schema and runtime registry**: it stores compiled DSL graphs, per-tenant script metadata, and runtime flags (`runtimeStatus`, quotas, priorities) in its own database.
  - Enforces **runtime behavior**: sandbox execution, loop safety, per-script and per-tenant quotas (`ScriptQuotaService`), tick integration, and leadership leases over automation ticks.
  - Maintains **auditability and observability** for script execution via the `script_event_audit` feed and automation metrics (for example, `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`).
  - Implements **hot reload and failure handling** for script patches, including `activePatchVersion`, `pendingPatchVersion`, and `reloadState` as described in [Hot Reload & Resume Behavior](#hot-reload--resume-behavior).

This split lets the Game Design Service focus on ergonomics and version lifecycle, while the Automation & Scripting Service focuses on safe, deterministic execution, quotas, and operational guarantees.

### `scriptEventId` Lifecycle and Deduplication

`scriptEventId` is the canonical identifier for a single script trigger/run; it appears on automation queue entries, tick commands, and `script_event_audit` rows so behavior can be correlated end-to-end.

- **Generation rules**
  - For **external events** (for example, `onEnterRegion`, `onSpawn`, `onCommand`, and custom service events), the **event source** that owns the trigger (typically the Game Session Service or another domain service) creates a `scriptEventId` when the event is first emitted and includes it in the `TriggerScriptEvent` payload. If the caller retries the gRPC call due to infrastructure errors, it must reuse the same `scriptEventId` so the Automation & Scripting Service can recognize the trigger as the same logical event.
  - For **scheduler-originated events** such as `onInterval` and `onTimerExpire`, the **Automation & Scripting Service scheduler** creates the `scriptEventId` when the timer or interval becomes due.

- **Uniqueness scope**
  - Within a given `<tenantId, regionId, scriptId, eventType>`, each logically distinct trigger is assigned a unique `scriptEventId`. There is no requirement for global uniqueness across all tenants; the combination of `<tenantId, regionId, scriptId, scriptEventId, tickId>` is what downstream services use as an idempotency key.

- **Handling retries and duplicates**
  - The Automation & Scripting Service treats script execution as **at-most-once per `scriptEventId`**. If it receives a duplicate delivery for the same `<tenantId, regionId, scriptId, eventType, scriptEventId>`—for example, because the caller retried a gRPC call—it does **not** re-run the DSL graph for that trigger. Instead it consults existing `script_event_audit` state and treats the duplicate as a replay of an already completed or skipped trigger.
  - Downstream services and replay tools rely on `(tenantId, regionId, tickId, scriptEventId)` (or a derived `effectId`) as their idempotency token when applying script-originated effects, so duplicate deliveries for the same tuple are safe replays that do not produce new side effects.

---

## Supported Script Events

Scripts are bound to **standard lifecycle events** and **custom service events**. Examples include:

- `onSpawn` – when an entity (player or NPC) spawns into the world.
- `onEnterRegion` – when the entity moves into a new region.
- `onLeaveRegion` – when the entity leaves a region.
- `onCommand` – when the player issues a game command (for example, interaction or dialogue).
- `onInterval` – a scheduler-driven interval for periodic behavior.
- `onTimerExpire` – a one-shot or repeating timer expiring.
- Domain-specific events such as `inventory.item_added`, `combat.round_started`, or world-generation hooks as defined by domain services.

Scripts should use the most specific event available rather than polling general state. Per-entity initialization is typically done via `onSpawn`, `onEnterRegion`, or related lifecycle events instead of relying on ad-hoc `onLoad`-style handlers.

### Event Fan-Out and Handler Ordering

An entity may have **multiple scripts bound to the same event** (for example, two `onSpawn` handlers that set patrol routes and apply buffs). The Game Design Service stores these bindings as an ordered list per `{entityId, eventType}`.

When an event fires, the Automation & Scripting Service evaluates bound handlers in a **deterministic order** sorted by `(orderIndex ASC, scriptId ASC)`. This ordering is stable across deployments so that the same set of scripts produces the same sequence of commands for a given event.

Failures are **isolated per script** by default. If one handler fails (for example, quota denial, sandbox exception, or compilation error), the scheduler records the failure and continues to the next handler unless the binding is explicitly marked as requiring exclusive handling. Designers can opt into **exclusive handling** on a per-binding basis (for example, `requiresExclusiveEvent=true`) so that a terminal outcome for one handler short-circuits remaining handlers for that event. Quota checks (`ScriptQuotaService`) remain **per script** either way.

---

## Scripting DSL

- **For game designers**
  - Build behaviors by wiring together predefined components (conditions, actions, timers, counters) in the visual editor; you never write raw Lua or general-purpose code.
  - Loops must always be bounded: use timers and counters to express “repeat every N ticks” or “do this up to N times,” rather than wiring a pure cycle with no guard.
  - If a graph would create an unsafe loop or an incompatible connection, the editor surfaces a clear validation error pointing at the offending nodes; fix the wiring (typically by adding a timer/counter or breaking the cycle) and re-run validation before publishing.
  - You do not need to understand the internal graph or analysis algorithms; they exist so the platform can guarantee scripts cannot busy-loop or hang the game.

- Scripts are authored as structured graphs of these components in the visual editor; the editor exports structured data that the Automation & Scripting Service compiles into execution units. Each component maps to a safe, well-defined operation, and the lack of raw code prevents arbitrary behavior while limiting scripts to the capabilities exposed by the platform.

### Control Flow and Predicates

- The DSL uses a **directed graph model**: nodes represent conditions, actions, and timers, and edges represent control-flow transitions (`onTrue`, `onFalse`, `onTimeout`, etc.). Execution walks this graph; there is no general-purpose stack or call frame.
- **Branching** is expressed via condition nodes that evaluate predicates and route to different successors; for example, a `HealthCheck` node exposes `onBelowThreshold` and `onAboveThreshold` outputs, and a `ReputationCheck` node exposes `onFriendly`, `onNeutral`, and `onHostile` outputs. Designers combine these via explicit condition nodes instead of inlining arbitrary expressions.
- **Loops** are supported only as **bounded, explicit cycles** in the graph (for example, timer nodes that reschedule the current subgraph or counter nodes that decrement and branch while a limit remains). The engine rejects graphs that contain unbounded cycles without a timer or counter guard to keep scripts from busy-waiting.
- **Complex predicates** such as “if reputation < X and HP < Y” are modeled as small subgraphs that compose simpler condition nodes. A typical pattern is `HealthCheck` → `ReputationCheck` → `AllOf`/`AnyOf` aggregator nodes, which then forward to action nodes. The visual editor enforces these patterns so predicates stay declarative and analyzable.
- Each node type defines **strongly typed inputs** (attributes, thresholds, flags) and a fixed set of outputs. The visual editor validates connections at design time, and the Automation & Scripting Service revalidates when compiling scripts so ill-typed or incompatible graphs never reach runtime.

### Loop Safety Analysis

Before a script is accepted, the Automation & Scripting Service runs a **loop safety analysis** over the component graph to ensure there are no unbounded cycles within a single script invocation:

- The compiler builds a **reduced graph** for analysis that includes only **same-run edges**. Asynchronous edges (for example, timer callbacks that fire in a future tick) are treated as new invocations and are excluded from this graph so they do not count as busy loops.
- It then computes **strongly connected components (SCCs)** on the reduced graph. Any SCC with more than one node, or a self-loop, is treated as a candidate loop.
- A loop is considered **safe** only if the SCC contains at least one **bounded guard node**, such as a `Counter` node with a finite `maxIterations`. Loops without such a guard are rejected at validation time with a descriptive error that points to the participating nodes and is surfaced in the Game Design Service UI so designers see which connections must change before the script can be published.
- In addition to static checks, the runtime enforces a **per-run iteration budget**. If a bug or future change allows an unsafe loop to slip through static analysis, the engine aborts the run with a `sandbox_error` (for example, `reason=iteration_budget_exceeded`) before it can spin indefinitely. See `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md` for details on runtime safeguards.

### Designer Debugging & Validation

From a game designer’s perspective, debugging a script centers on **what the editor shows** and **how the platform reports problems**, rather than on implementation details:

- **Graph validation and unsafe loops**
  - The Game Design Service highlights invalid wiring (for example, missing connections, incompatible types, or unbounded cycles) directly in the visual editor. Errors point to the specific nodes and edges that must change before publish, including loops rejected by the loop safety analysis described above.
  - Scripts with unsafe or deprecated components (for example, components marked `UNSAFE`) appear in a dedicated “requires migration” view. They must be migrated and republished before they are eligible to run again; see the forced deprecation flow in this section for details.

- **Runtime outcomes and auto-disable**
  - When a script misbehaves at runtime—exceeding quotas, hitting sandbox errors, or being disabled by the failure-rate circuit breaker—the Automation & Scripting Service records a canonical `outcome` and `reason` in `script_event_audit`. Common examples include `quota_denied`, `sandbox_error`, `disabled_due_to_errors`, and `skipped_disabled`.
  - Administrative disables and throttling (for example, `runtimeStatus=DISABLED` or `DISABLE_AFTER_DRAIN`) are reflected in script metadata and surfaced through the Game Design and Logging & Admin tools. Designers can see which scripts are paused, why they were disabled (for example, `admin_hard_disable`), and when they can be safely re-enabled.

- **Where to look when debugging**
  - For **editor-time issues**, fix the graph based on the validation errors in the Game Design Service UI and re-run validation before publishing.
  - For **runtime issues**, start from the script’s recent entries in `script_event_audit` and the associated metrics in the quotas and operations doc (`design/architecture/scripting-quotas-and-operations.md`), then adjust quotas or disable/throttle the script using the operational flows described there.

---

## Determinism & Allowed Non-Determinism

Scripts are designed to behave **deterministically for a given game configuration and event**, so that both the original execution and any offline replay in tools or tests produce the same observable behavior. The Automation & Scripting Service enforces this by constraining how randomness and time are exposed to DSL components:

- All **pseudo-random behavior** (for example, “pick a random waypoint”, “roll for loot”, or encounter selection) flows through curated components that read from a **seeded RNG** supplied by the runtime. The seed is derived from stable identifiers such as `<tenantId, regionId, scriptId, scriptEventId, tickId, scriptPatchVersion>` so that re-evaluating the same trigger with the same inputs produces the **same sequence of random values**. Components must not call process-wide RNG APIs directly; they receive a scoped RNG instance from the sandbox.
  - Seeds are derived from this tuple primarily so offline replay tools and test harnesses can reproduce behavior for a given event stream; production tick replays never re-enter the DSL for the same `scriptEventId`.
- **Wall-clock time is not exposed** to scripts. DSL components see only **derived game time** sourced from the tick and session model (for example, `tickId`, region-local “world time” counters, or effect durations computed by Game Logic). This ensures that replaying the same tick timeline yields the same time values from the script’s perspective, independent of real-world clock drift.
- Any component that introduces variability must either:
  - be implemented in terms of the seeded RNG and tick-based time described above, or
  - be explicitly documented as **non-replayable** and confined to side channels such as logging and metrics where non-determinism does not affect gameplay state or authoritative decisions.

Under these rules, the combination of `<tenantId, regionId, scriptId, scriptEventId, tickId, scriptPatchVersion>` fully determines the observable behavior of a script run that contributes commands to the tick system.

Crucially, **script handlers are not re-executed during tick replay or recovery**. The Automation & Scripting Service evaluates each trigger at most once, produces a set of commands annotated with `scriptEventId`, and hands those commands to the tick system. Tick-level crash recovery and retries reapply those commands idempotently in the Game Session and domain services without re-entering the DSL graph for the same trigger. Determinism for scripting therefore depends on this **“no re-execution per trigger”** guarantee plus the seeded RNG and time constraints.

---

## Integration with Game Logic & Tick System

- **Scripts do not execute inside the tick system.** The Automation & Scripting Service evaluates scripts independently—on a schedule, via timers, or in response to events—and enqueues the resulting commands into each entity's command queue.
- These queued commands run during the **next tick cycle** via the normal Game Session and Game Logic flow, ensuring deterministic, replayable behavior that follows the tick system's fairness and retry rules.
- Script evaluation never blocks or interferes with tick execution. Scripts can still react to world events, NPC states, or timers provided by the tick system.
- Script-generated commands—like any gameplay command—may fail due to lock contention or target remote regions. These cases are automatically handled by the Game Session Service via standard tick rescheduling and cross-region routing logic.
- The Automation & Scripting Service only determines which commands to inject. It may query world state via gRPC but never mutates entity or world data directly—every action passes through the Game Session Service so tick regions remain consistent.

### Redis Key Summary for Scripting

The main Redis keys used by the Automation & Scripting Service are:

| Key pattern | Owner / service | Purpose | Hash tag / shard scope | TTL / retention expectations |
| --- | --- | --- | --- | --- |
| `automation:queue:<tenantId>:<entityId>` | Automation & Scripting | Per-tenant, per-entity queue of post-DSL script work items awaiting automation ticks. | Single-key queue per entity; automation ticks drain these and enqueue commands into tick queues. | Ephemeral backlog; drained continuously by automation ticks. Any TTL is a short safety valve, not long-term storage. |
| `automation:tick:{tenantScriptTag}:lock` | Automation & Scripting (`ScriptTickService`) | Per-script automation tick lock to serialize staging for a script’s work batch. | Hash-tagged on `{tenantScriptTag}` so multi-key operations remain shard-local. | Short-lived lock; lifetime bounded by a single automation tick batch and its retry window. |
| `automation:tick:{tenantScriptTag}:queue` | Automation & Scripting (`ScriptTickService`) | Staging queue for batched script events before they are written into per-entity tick queues. | Hash-tagged on `{tenantScriptTag}`. | Batch-scoped staging; entries exist only while a batch is being processed and are cleared on commit/rollback. |
| `automation:tick:{tenantScriptTag}:pending` | Automation & Scripting (`ScriptTickService`) | Pending entry for an in-flight automation tick batch; replayable if a crash occurs mid-staging. | Hash-tagged on `{tenantScriptTag}`. | Crash-replay state; retained only for the duration of an in-flight batch and removed once replay/commit completes. |
| `automation:timer:{tenantRegionTag}` | Automation & Scripting scheduler | Region-scoped index of script timers/intervals (`onTimerExpire`, `onInterval`, `intervalTicks`). | Hash-tagged on `{tenantRegionTag}` to align with tick-region keys. | Persistent while timers are active; entries are added/removed as timers are created and satisfied. |
| `script-leader:{<tenantId>}` | Automation & Scripting scheduler | Leadership lease for scheduler coordination per tenant. | Hash-tagged per tenant. | Short-lived lease refreshed by the active scheduler instance. |

See `design/architecture/system-architecture-redis.md` and the Automation & Scripting Service README for broader Redis usage patterns.

### Script Timers vs Tick Timers

- **Script timers** (for example, `onTimerExpire`, `onInterval`) are managed by the Automation & Scripting Service. They determine when a script handler should run but do not apply game-state changes directly.
- **Tick timers** belong to the Game Session Service and control when gameplay commands are executed. Script-generated commands enter the same command queues as player actions and are subject to the same per-tick budgets and fairness rules.
- The scheduler uses region-scoped timer indexes (for example, `automation:timer:{tenantRegionTag}`) plus tick heartbeats to align script timers with the canonical tick timeline while preserving separation of responsibilities.

### End-to-End `onInterval` Timer Lifecycle

This section summarizes how a single `onInterval` timer behaves across normal operation, leader changes, and script reloads, and which Redis keys are authoritative at each step.

- **Normal operation**
  - When an NPC spawns or a script is first loaded, the scheduler creates or updates an interval entry for the `<tenantId, scriptId, entityId>` tuple in the region-scoped timer index under `automation:timer:{tenantRegionTag}`. That entry stores at least the configured cadence (`intervalTicks` or equivalent) and the next due point (`nextTick` or `nextRunAt`). If a per-script index is enabled, a corresponding projection entry may be written under `automation:script:{tenantScriptTag}:timer`, but the region index remains authoritative.
  - Leaders advance a per-region notion of time by consuming the tick heartbeat stream and updating `script-scheduler:{tenantRegionTag}:lastTickId`. For **“every N ticks”** intervals, the leader compares `lastTickId` with the current `tickId` and the stored `intervalTicks` / `nextTick` for each timer entry to decide which `onInterval` triggers are due.
  - When an interval becomes due and passes quota/budget checks, the scheduler:
    - emits a trigger (and audit row) for the `onInterval` handler, and
    - recomputes and persists the next due point (`nextTick` or `nextRunAt`) in the timer entry so the cadence remains stable, even if some firings are delayed by load.

- **Leader failover**
  - Leaders periodically persist `script-scheduler:{tenantRegionTag}:lastTickId` as they process the heartbeat stream. The authoritative source of **“how far this region has progressed”** is therefore the combination of:
    - the most recent `tickId` seen on the heartbeat stream, and
    - the stored `lastTickId` for that `<tenantId, regionId>` key.
  - When leadership changes, the new leader:
    - reads `script-scheduler:{tenantRegionTag}:lastTickId` for each region it owns,
    - walks forward from `lastTickId` to the current `tickId` using the heartbeat stream, and
    - for each timer entry in the region index `automation:timer:{tenantRegionTag}`, determines which “every N ticks” boundaries were crossed during the gap. Any missed `onInterval` triggers are enqueued exactly once before the leader resumes normal scheduling from the latest `tickId`. If a per-script index is used, it is reconciled against the region index as needed; discrepancies are treated as projection bugs and corrected, not as new timers.
  - Because the authoritative timer state lives in Redis (the region index plus `lastTickId`), leader changes do not reset cadences; they only introduce a bounded delay before the new leader catches up.

- **Script reload**
  - During reload, leaders set `reloadState=RELOADING` for the affected `<tenantId, pendingPatchVersion>` and pause new triggers, including `onInterval` firings, while they load and validate the new script definitions. Existing timer entries in the region index `automation:timer:{tenantRegionTag}` (and any derived per-script projections) remain in Redis but are treated as **pending**.
  - Once reload succeeds and `activePatchVersion` is switched, the leader:
    - re-reads `script-scheduler:{tenantRegionTag}:lastTickId` and the current `tickId`,
    - updates each interval entry’s next due point (`nextTick` or `nextRunAt`) in the region index as needed so the cadence resumes from the latest tick/time (rather than replaying the paused window), and
    - resumes normal scheduling for `onInterval` using the updated `activePatchVersion`. No interval runs against a partially loaded script definition.
  - If reload fails, `activePatchVersion` remains unchanged, `pendingPatchVersion` is marked failed, and the leader resumes using the existing region-index timer entries as-is. Any `onInterval` triggers that fire after a failed reload are still scheduled according to the stored cadence, but always execute under the last known good patch version.

### Scheduler Leadership & Coordination

- Scheduler instances coordinate via tenant-scoped leadership leases such as `script-leader:{<tenantId>}` and region-scoped timer indexes.
- At any given time, a single scheduler instance per tenant owns the right to fire timers and intervals for that tenant’s automation workload.
- Leadership leases and timer indexes are designed to be shard-local and replayable so that leader failover does not cause duplicate script execution; at-most-once-per-`scriptEventId` behavior is preserved.

---

## Advanced NPC Behavior Modules

Several higher-level behavior modules build on the scripting framework and integrate with other microservices:

- `NpcMoraleService` adjusts aggression based on health and morale so NPCs may flee or surrender. It relies on Social & Groups–provided faction and reputation data; see `design/architecture/microservices/social-groups-service/README.md` for the `faction` and `faction_standing` model.
- `PveEncounterService` generates random encounters and environmental hazards, coordinating with world and game-logic services to spawn entities and apply effects. See the Automation & Scripting Service README (`design/architecture/microservices/automation-scripting-service/README.md`) and Game Logic Service design (`design/architecture/microservices/game-logic-service/README.md`) for encounter definitions and combat rules.
- `NpcFormationService` coordinates squad positioning for groups of NPCs, using shared world topology and movement rules from the World and Game Logic services.

Detailed behavior, data models, and service-specific responsibilities for these modules are defined in the Automation & Scripting Service README and the relevant microservice design docs; this section only highlights that they are implemented on top of the scripting and tick pipeline described here.
`NpcMoraleService` and basic encounter/formation behavior are implemented today; the breadth of the PvE encounter library and biome-specific events is still expanding and tracked in the Automation & Scripting Service task list (`design/project-management/task-list-automation-scripting-service.md`).

---

## Hot Reload & Resume Behavior

- Scripts are versioned and published via the Game Design Service; the Game Session Service pins an active `scriptPatchVersion` per game.
- When a new script patch is published, the Automation & Scripting Service:
  - loads the new definitions and validates them,
  - updates bindings and metadata, and
  - transitions scripts through reload states (for example, `reloadState=RELOADING`) to avoid partial visibility.
- During reloads, triggers may be temporarily paused or skipped with outcomes such as `skipped_reloading` if the active version for a tenant is not yet available. These decisions are surfaced through `script_event_audit` and metrics.
- On success, the new `scriptPatchVersion` becomes active for future triggers; on failure, the tenant remains pinned to the previous known-good version, and triggers referencing the failed version follow the `version_unavailable` behavior described in the quotas and operations doc.

- **Timer-based triggers** such as `onInterval` and `onTimerExpire` always execute against the **currently pinned `scriptPatchVersion`** for the game at the moment they are evaluated; they do not continue running older definitions after a patch is promoted.
- **Older script versions** remain in the Automation & Scripting Service database for auditing and potential rollback, but only the **pinned active version** is used for live execution.

---

## Failure Modes and Error Handling

Script executions are treated as **at-most-once per trigger**. Combined with quotas and circuit breakers (covered in `design/architecture/scripting-quotas-and-operations.md`), this ensures that misbehaving scripts cannot hot-loop or consume unbounded resources.

Common outcome classes include:

- `success` – script ran and enqueued commands.
- `quota_denied` – `ScriptQuotaService` limit exceeded before execution.
- `sandbox_error` – exception in the sandboxed DSL runtime or validation failure.
- `validation_error` – static validation on inputs or script configuration failed.
- `disabled_due_to_errors` – failure-rate circuit breaker opened for the script.
- `version_unavailable` / `skipped_version_unavailable` – trigger referenced a script patch version that failed reload or is unknown.
- `infrastructure_error` – transient infrastructure issues such as gRPC `UNAVAILABLE` or Redis timeouts.

Retry behavior:

- Logical failures (`sandbox_error`, `validation_error`, `quota_denied`, `disabled_due_to_errors`, `version_unavailable`) are treated as **final** for a trigger; the scheduler does not re-run the script body for the same `scriptEventId`.
- Infrastructure errors **may be retried** by lower layers following platform-wide retry policies and idempotency contracts, but those retries operate only on idempotent downstream operations, not on the DSL body.

When script components call other services over gRPC, they must pass a stable idempotency key (for example, a composite of `<tenantId, regionId, scriptId, scriptEventId, tickId>` or a dedicated `effectId`) and rely on the transaction strategies in `design/architecture/system-architecture-transactions.md` so those downstream operations can be safely retried without duplicating effects.
