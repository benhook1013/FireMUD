# FireMUD System Architecture: Scripting & Automation Framework

This document outlines how FireMUD executes custom in-game behavior through a sandboxed scripting framework. It complements the [Automation & Scripting Service](./microservices/automation-scripting-service/README.md) and expands on the extensibility goals in the [core requirements](../project-management/core-requirements.md).

## Terminology Glossary

- **Game tick** – a region-scoped tick in the Game Session Service. Each `{tenantId, regionId}` advances through a monotonic `tickId` stream; game ticks are authoritative for gameplay state changes and use `tick:{tenantId}:{regionId}:...` keys and locks as described in [Tick System and Runtime Design](./system-architecture-ticks.md).
- **Automation/script tick** – a batching cycle inside the Automation & Scripting Service. `ScriptTickService` drains `automation_queue:{tenantId}:{entityId}` events, stages them under `automation:tick:{tenantId}:{scriptId}:...`, and enqueues resulting commands into per-entity queues for later execution by game ticks. Automation ticks control script-side quotas and batching, not authoritative game state.
- **Tick heartbeat** – the event stream produced by the Game Session Service that reports `tickId` progression per `{tenantId, regionId}`. The script scheduler consumes this heartbeat to count “every N ticks” intervals and align `onInterval` triggers with the canonical game tick timeline without owning tick execution itself.

## Implementation Status

This document describes the **target-state architecture** for scripting and automation. The implementation is evolving toward this design; this section captures a snapshot as of 2025-12-04. For the most accurate, fine-grained status, refer to the [Automation & Scripting Service Task List](../project-management/task-list-automation-scripting-service.md).

- **Implemented and in active use**
  - Sandboxed script runtime and core Automation & Scripting Service, including quota enforcement via `ScriptQuotaService` and Redis-backed `ScriptTickService` staging.
  - Hot reloading of scripts published by the Game Design Service and version-aware script execution, aligned with the versioning model in [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#script-only-patch-versions).
  - Visual DSL editor for script creation and testing in the Game Design Service, mapping component graphs to Automation & Scripting Service definitions.
  - Advanced NPC behavior modules (morale, PvE encounters, formations) and state-driven / event-driven NPC behaviors integrated with the tick system.

- **Planned or partially implemented**
  - Copying published version data into the Automation & Scripting Service schema via Saga, and broader script-driven world generation flows (runtime generation requests via isolated ticks, generation seed persistence, and script-driven population triggers).
  - Expansion of the PvE encounter library, biome-specific events, and world generation features called out in the Automation & Scripting Service and world generation task lists.
  - Scheduler leadership leases, per-region tick-stream consumption, and long-term audit retention are designed here; see [Scheduler Leadership & Coordination](#scheduler-leadership--coordination) for scripting-specific lease keys (for example, `script-leader:{tenantId}`) and [Redis Architecture – Region Leadership and Tick Executor Lease](./system-architecture-redis.md#region-leadership-and-tick-executor-lease) for the underlying tick leadership model. Operators should verify concrete key names, metrics, and retention jobs against the current Automation & Scripting Service implementation and operations runbooks.

Maintainers should update this section whenever major scripting features land or significant architecture pieces change so it remains a reliable guide to what is live versus aspirational.

---

## Goals

- Enable **event-driven scripting** and **NPC automation** so worlds feel alive even without active players.
- Keep the system **extensible** while preventing malicious or abusive scripts.
- Support **persistence** and versioned updates so game creators can iterate safely.

## TL;DR Flow

At a high level, scripting follows this pipeline:

1. **Event fires** – Game Session or another service emits a standard or custom event for an entity.
2. **Bindings & quotas** – The Automation & Scripting Service looks up bound handlers for that `{tenantId, eventType}` and applies per-script and per-tenant limits via `ScriptQuotaService`.
3. **Sandboxed DSL execution** – Allowed handlers run in the sandboxed DSL runtime, reading world state via gRPC and producing domain commands rather than mutating state directly.
4. **Automation queue staging** – Commands are enqueued into Redis-backed automation queues under keys such as `automation_queue:{tenantId}:{entityId}`, along with `scriptEventId`, `scriptId`, and version metadata. These queues are per-tenant and per-entity; region-scoped tick keys remain the responsibility of the Game Session Service.
5. **Script ticks & commit** – `ScriptTickService` batches automation events into tick-compatible queues with quotas and budgets, using Redis Lua scripts for atomic staging and commit.
6. **Game tick execution** – The Game Session Service consumes at most one command per entity per tick from the combined player-and-automation queues and applies effects under the normal lock and replay rules.

## Game Design vs Automation & Scripting Responsibilities

Two services collaborate to deliver scripting and automation:

- **Game Design Service**
  - Owns the **authoring UX**: the visual DSL editor, component palettes, world-generation triggers, and per-tenant configuration screens.
  - Manages **draft and published configurations** for scripts, event bindings, and world-generation presets inside its own schema, including version history and “upgrade available” hints when components change.
  - Controls the **publish lifecycle** for scripts and component graphs: designers edit drafts, run validations, and then publish a new `scriptPatchVersion` tied to a `baseVersionId` as described in [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#script-only-patch-versions).
  - Drives a **cross-service Saga** when a script version is published:
    - Writes the final, validated script graphs and bindings into its own tables.
    - Starts a Saga that upserts the compiled script definitions, event bindings, and any world-generation hooks into the Automation & Scripting Service schema for the target `{tenantId, scriptPatchVersion}`.
    - On success, marks the version as `PUBLISHED` and calls `NotifyScriptVersionUpdate` so the Automation & Scripting Service reloads runtime state.
    - On failure, rolls back or marks the publish as `FAILED`, keeping the prior `scriptPatchVersion` as the active one for that game.

- **Automation & Scripting Service**
  - Owns the **compiled graph schema and runtime registry**: it stores compiled DSL graphs, per-tenant script metadata, and runtime flags (`runtimeStatus`, quotas, priorities) in its own database.
  - Enforces **runtime behavior**: sandbox execution, loop safety, per-script and per-tenant quotas (`ScriptQuotaService`), tick integration, and leadership leases over automation ticks.
  - Maintains **auditability and observability** for script execution via the `script_event_audit` feed and automation metrics (for example, `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`).
  - Implements **hot reload and failure handling** for script patches, including `activePatchVersion`, `pendingPatchVersion`, and `reloadState` as described in [Hot Reload & Resume Behavior](#hot-reload--resume-behavior).

This split lets the Game Design Service focus on ergonomics and version lifecycle, while the Automation & Scripting Service focuses on safe, deterministic execution, quotas, and operational guarantees.

## Example: `onEnterRegion` Script Execution

This section walks through a typical happy-path flow where an NPC script reacts when a player enters its room or region. It ties together the Game Session Service, the Automation & Scripting Service, the tick system, and the auditing/metrics layer.

1. **Player movement and event emission**
   - A player issues a movement command that causes them to enter a new room. The Game Session Service processes this action as part of a tick for the relevant `{tenantId, regionId}`.
   - After the move is committed and the player is now in the new region, the Game Session Service emits an `onEnterRegion` script trigger to the Automation & Scripting Service via gRPC. The trigger includes the `tenantId`, `regionId`, target `entityId` (for example, an NPC guarding the room), and the currently pinned `scriptPatchVersion` for that game as stored by the Game Session Service.

2. **Script lookup and quota checks**
   - The Automation & Scripting Service looks up all scripts bound to `onEnterRegion` for the target entity and tenant, using the version metadata provided by the Game Session Service to resolve the correct script definitions.
   - For each candidate script, `ScriptQuotaService` applies per-script and per-tenant limits (for example, `SCRIPT_QUOTA_LIMIT` / `SCRIPT_QUOTA_WINDOWSECONDS`) before any work is enqueued. If a script has exceeded its quota, the trigger for that script is skipped, `script_quota_denied_total` is incremented, and an audit entry is recorded with an appropriate outcome; other scripts bound to the same event may still proceed if their quotas allow it.

3. **DSL graph evaluation in the sandbox**
   - Scripts that pass quota checks are compiled DSL graphs. The Automation & Scripting Service executes the `onEnterRegion` handler inside a sandboxed runtime, walking the graph of condition, timer, and action nodes for the current event payload.
   - Instead of mutating game state directly, action nodes produce a set of **domain commands** (for example, “NPC says a line,” “NPC targets the player,” “schedule a follow-up patrol timer”) that describe what should happen in the game world.

4. **Command enqueue into tick-compatible queues**
   - The Automation & Scripting Service batches the resulting commands and enqueues them into Redis-backed automation queues such as `automation_queue:{tenantId}:{entityId}`. A staging script then merges these commands into the same per-entity command queues used by the Game Session Service, preserving FIFO order and the invariant of at most one command per entity per tick as described in the [Tick System and Runtime Design](./system-architecture-ticks.md).
   - Each enqueued command carries the originating `scriptEventId`, `scriptId`, and version metadata so downstream logs, metrics, and audits can correlate behavior to the script that produced it.

5. **Tick execution and world updates**
   - On subsequent ticks for that `{tenantId, regionId}`, the Game Session Service’s `TickScheduler` pulls at most one command per entity from the combined player-and-automation queues and executes it under the usual lock and conflict-resolution rules.
   - The NPC’s script-produced command runs alongside player commands with the same fairness guarantees: if the tick budget is reached or locks are contended, the command is deferred or retried according to the logic in the tick architecture.

6. **Audit trail and metrics**
   - For each trigger, the Automation & Scripting Service emits an audit record into the `script_event_audit` store; in the target architecture this is a **PostgreSQL table** managed by the Automation & Scripting Service. Each record captures fields such as `scriptEventId`, `scriptId`, `tenantId`, `tickId`, `versionId`, and an outcome (`allowed`, `denied_quota`, `skipped_disabled`, etc.). Retention is controlled by `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` and `SCRIPT_EVENT_AUDIT_MAX_ROWS` as described in the Automation & Scripting Service README.
   - Metrics such as `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total` are updated throughout this flow so operators can monitor how often `onEnterRegion` scripts fire, how many are skipped by policy, and how much automation work is being handed to the tick system. These metrics integrate with the broader logging and monitoring strategy described in [System Architecture: Logging & Monitoring](./system-architecture-logging-monitoring.md).

## Example: Periodic Patrol via `onInterval`

This example shows how a script that runs on a fixed cadence (for example, an NPC patrol) moves through the same pipeline using `onInterval`.

1. **Script configuration and publish**
   - A designer configures an NPC patrol script in the Game Design Service, binding an `onInterval` handler with a chosen cadence (for example, every N ticks or seconds) and a sequence of waypoints.
   - When the script is published, its compiled DSL graph, `intervalTicks` (or equivalent cadence configuration), and version metadata are stored in the Automation & Scripting Service database and exposed under the current `scriptPatchVersion` for that game.

2. **Scheduling the next interval**
   - When the NPC spawns or when the script is first loaded, the Automation & Scripting Service’s scheduler registers an interval entry for the `{tenantId, scriptId, entityId}` tuple, computing a `nextTick` or `nextRunAt` timestamp based on the configured cadence and current tick/time.
   - Leaders track these interval entries alongside other automation timers, using bounded scans and the automation tick budget (`AUTOMATION_TICK_DURATION_MS`, `AUTOMATION_TICK_MAX_EVENTS`, `AUTOMATION_TICK_BUDGET_MS`) to decide which `onInterval` triggers should fire in each automation tick.

3. **Firing `onInterval` and enforcing budgets**
   - When an interval becomes due, the scheduler creates a `scriptEventId` for the `onInterval` trigger and performs the same per-script and per-tenant quota checks described earlier. If the script is outside its budgets or disabled, the trigger is skipped and recorded in both metrics and the audit feed.
   - If allowed, the scheduler enqueues the `onInterval` trigger for sandbox execution and updates the interval entry with a new `nextTick` or `nextRunAt`, ensuring the cadence remains stable even if some intervals are occasionally delayed by load.

4. **Sandbox execution and command enqueue**
   - The `onInterval` handler runs inside the sandboxed DSL engine, evaluating conditions such as “is the NPC currently out of combat?” and “is the patrol still active?” before deciding on the next waypoint or behavior.
   - Actions produced by the handler (for example, “move to the next patrol room,” “play an emote,” “schedule an `onTimerExpire` follow-up”) are converted into domain commands and enqueued into the automation queues for the relevant `{tenantId, regionId, entityId}`, then merged into the per-entity command queues so they execute during future ticks.

5. **Execution, audit, and observability**
   - On subsequent ticks, the Game Session Service executes at most one command per entity per tick, so patrol movements and emotes follow the same fairness and conflict-resolution rules as player actions.
   - Each fired interval contributes to `automation_script_triggers_total` (tagged with `eventType=onInterval`) and, if it produces work, increases `automation_tick_events_enqueued_total`. Outcomes are written to `script_event_audit` with enough context to debug missed or delayed intervals, and long-term patterns are visible via the same dashboards and alerts used for other script events.

## Scripting DSL

- Scripts are authored in a **visual editor** where designers assemble **predefined components** (conditions, actions, timers, etc.).
- Each component maps to a safe, well-defined operation in the Automation & Scripting Service.
- The editor exports structured data—**not raw Lua or general-purpose code**—which the service compiles into execution units.
- This approach prevents arbitrary behavior and limits scripts to the capabilities exposed by the platform.

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
- A loop is considered **safe** only if the SCC contains at least one **bounded guard node**, such as a `Counter` node with a finite `maxIterations`. Loops without such a guard are rejected at validation time with a descriptive error that points to the participating nodes and is surfaced in the Game Design Service UI so designers see exactly which connections must change before the script can be published.
- In addition to static checks, the runtime enforces a **per-run iteration budget**. If a bug or future change allows an unsafe loop to slip through static analysis, the engine aborts the run with a `sandbox_error` (for example, `reason=iteration_budget_exceeded`) before it can spin indefinitely. See `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md` for details on runtime safeguards.

### Determinism & Allowed Non-Determinism

Scripts are designed to be **deterministic under replay** for a given game configuration and event. The Automation & Scripting Service enforces this by constraining how randomness and time are exposed to DSL components:

- All **pseudo-random behavior** (for example, “pick a random waypoint”, “roll for loot”, or encounter selection) flows through curated components that read from a **seeded RNG** supplied by the runtime. The seed is derived from stable identifiers such as `{tenantId, regionId, scriptId, scriptEventId, tickId, scriptPatchVersion}` so that re-evaluating the same trigger with the same inputs produces the **same sequence of random values**. Components must not call process-wide RNG APIs directly; they receive a scoped RNG instance from the sandbox.
- **Wall-clock time is not exposed** to scripts. DSL components see only **derived game time** sourced from the tick and session model (for example, `tickId`, region-local “world time” counters, or effect durations computed by Game Logic). This ensures that replaying the same tick timeline yields the same time values from the script’s perspective, independent of real-world clock drift.
- Any component that introduces variability must either:
  - be implemented in terms of the seeded RNG and tick-based time described above, or
  - be explicitly documented as **non-replayable** and confined to side channels such as logging and metrics where non-determinism does not affect gameplay state or authoritative decisions.

Under these rules, the combination of `{tenantId, regionId, scriptId, scriptEventId, tickId, scriptPatchVersion}` fully determines the observable behavior of a script run that contributes commands to the tick system. This aligns script behavior with the determinism guarantees of the underlying tick and transaction architecture.

### Component Versioning and Backwards Compatibility

- Each DSL component (node type) is versioned independently, for example `HealthCheck@v1`, `HealthCheck@v2`. Published scripts reference both the component key and its version so the Automation & Scripting Service can load the correct behavior for a given `scriptPatchVersion`.
- When a component evolves in a **backwards-compatible** way (adding an optional field or new output), a new minor version is registered and existing scripts remain pinned to their original version. The visual editor may offer an automatic migration that rewrites graphs to the new version, but runtime does not change behavior until a script revision is published.
- **Breaking changes** result in a new major component version (for example, `ReputationCheck@v2` with different output states). Existing scripts keep using the prior version and are flagged in the Game Design Service UI as “upgrade available” so designers can migrate them explicitly before publishing.
- Old component versions remain loadable as long as any published script still references them. Decommissioning a version requires migrating or retiring the dependent scripts; migration tooling in the Game Design Service generates updated graphs and revalidates them against the new component schema.
- The DSL and visual mapping are described in more depth in the Game Design Service documents; see [Web-Based Visual Design Interface](./microservices/game-design-service/web-visual-interface.md) and [World Editing & Customization Tools](./microservices/game-design-service/world-editing-tools.md) for how script graphs are created, versioned, and published.

In rare cases a component version may be marked **unsafe** (for example, due to a security issue or correctness bug that cannot be fixed in place). The platform supports a **forced deprecation** flow:

- The component version is marked `UNSAFE` in shared metadata and hidden from the visual editor so new scripts cannot reference it.
- The Automation & Scripting Service refuses to load or execute scripts that still depend on the unsafe version, treating them as disabled and recording audit entries with an outcome such as `disabled_unsafe_component`.
- The Game Design Service surfaces these scripts in a dedicated “requires migration” view so designers can migrate them to a safe component version. Only after migration and republish will the scripts become eligible for execution again.

## Supported Script Events

Scripts may register handlers for a set of standard lifecycle events. The Automation & Scripting Service emits these events and queues them as commands so they run during the normal tick flow.

- `onLoad` – when the script is first loaded or hot reloaded
- `onSpawn` – when the associated entity enters the world
- `onDeath` – when the entity dies
- `onDestroy` – when the entity is permanently removed
- `onEnterRegion` – when the entity moves into a new region
- `onLeaveRegion` – when the entity leaves a region
- `onTimerExpire` – when a scheduled timer finishes
- `onCommand` – when a player targets the entity with a command
- `onInterval` – periodic execution at a configured rate

### Custom and Service-Specific Events

Beyond the standard lifecycle events, FireMUD supports **extensible event types** so games and services can introduce new triggers without changing the core scheduler:

- Each event is identified by a stable **event type key** (for example, `inventory.item_added`, `social.guild_rank_changed`) and an associated **versioned schema** defined in shared DTOs owned by the platform. Schemas live in a shared protobuf/DTO package referenced by participating services and follow the same compatibility rules as other platform contracts.
- The Game Design Service controls which event types are **enabled per game/tenant**. A game may opt into additional event types while another game ignores them, but the meaning of a given `{eventTypeKey, version}` pair is global and deterministic.
- The visual DSL exposes **event source nodes** for any event types enabled for the current game. Designers bind scripts to these nodes in the same way they do for `onSpawn` or `onCommand`; under the hood, bindings are stored as `{tenantId, eventTypeKey, eventSchemaVersion, scriptId}`.
- When an upstream service emits a custom event, it includes the event type key, schema version, and a canonical ordering token (for example, `tickId` or a monotonic sequence). The Automation & Scripting Service uses this metadata to:
  - deterministically route the event to matching script handlers, and
  - enqueue resulting commands into the same per-entity queues used for standard events, preserving tick-based ordering and replay guarantees.

New event types and their schemas are introduced via the normal design and review process for shared contracts: changes land in the shared DTO/proto package, are reviewed by platform maintainers, and are then surfaced in the Game Design Service as new event type options. Individual games cannot unilaterally mint conflicting event type keys or schemas; additions go through this centralized “event catalog” so producers, consumers, and scripting bindings stay aligned.

New event types therefore extend **what** can trigger scripts without changing **how** triggers are scheduled or executed, keeping determinism and fairness aligned with the existing tick and automation model.

### Event Fan-Out and Ordering

- Each entity may have **multiple scripts bound to the same event** (for example, two `onSpawn` handlers that set patrol routes and apply buffs). At design time the Game Design Service stores these bindings as an ordered list per `{entityId, eventType}`.
- When an event fires, the Automation & Scripting Service evaluates the bound handlers in a **deterministic order** sorted by `(orderIndex ASC, scriptId ASC)`. This ordering is stable across deployments so the same sequence of commands is enqueued given the same set of scripts. `orderIndex` is maintained per `{tenantId, entityId, eventType}`; two scripts with the same `orderIndex` for the same event are ordered by their `scriptId`.
- **Failures are isolated per script**. If one handler fails (for example, quota denial, sandbox exception, or compilation error), the scheduler records the failure, increments the appropriate metrics, and continues to the next handler unless the script is explicitly marked as `requiresExclusiveEvent` for that event. In the exclusive case, a failure short-circuits remaining handlers and the event fan-out ends early.
- Quota checks (`ScriptQuotaService`) are performed **per script** before a handler runs. If a script exceeds its quota, its handler for that event is skipped and counted as `denied`, but other scripts bound to the same entity and event may still execute if their own quotas allow it.
- Script handlers enqueue commands **independently** into the entity’s command queue. The underlying tick system applies its normal fairness rules—only one command per entity per tick is executed—so even when many scripts respond to the same event, player-visible behavior remains bounded and replayable.

## Advanced NPC Behavior Modules

- `NpcMoraleService` adjusts aggression based on health and morale so NPCs may flee or surrender.
- `PveEncounterService` generates random encounters and environmental hazards.
- `NpcFormationService` coordinates squad positioning for groups of NPCs.
Refer to the Automation & Scripting Service README for implementation details.

## Sandboxing & Security

- Script execution occurs in a **sandbox** with restricted APIs and resource limits. High-level behavior is described here; see [Script Sandbox & Resource Limits](./microservices/automation-scripting-service/sandbox-runtime-design.md) for a detailed model.
- Components interact with the **Game Logic Service** and other backends only through validated gRPC calls; scripts never call arbitrary services directly.
- The sandbox **prohibits direct access** to networking primitives, filesystems, blocking system calls, and process-wide time sources. Scripts see only curated DTOs, a seeded RNG, and tick-derived time as described in [Determinism & Allowed Non-Determinism](#determinism--allowed-non-determinism).
- The service enforces **per-script quotas** via `ScriptQuotaService`, and CPU/memory limits are enforced via the sandbox runtime and Kubernetes resource limits, as detailed in the sandbox runtime design.

### Failure Modes and Error Handling

- Each script run produces a **structured outcome** such as `success`, `quota_denied`, `sandbox_error`, or `infrastructure_error`. Outcomes are written to the `script_event_audit` table and exposed via metrics (see **Auditability & Metrics**) so operators can correlate failures with specific `scriptId`, `tenantId`, and `tickId`.
- By default, **failures are surfaced through logs and metrics**, not detailed player-visible stack traces. Players typically experience a missing or degraded behavior (for example, an NPC does not respond) unless the script deliberately enqueues a fallback command that emits a message through the Game Logic Service.
- Script executions are treated as **at-most-once per trigger by the Automation & Scripting Service**. If a handler fails due to a sandbox or validation error, the scheduler records the failure and moves on; it does not automatically re-run the script body for the same trigger to avoid hot loops and duplicate side effects. Infrastructure-level issues (for example, transient gRPC or Redis errors) may be retried by lower layers (for example, gRPC clients, Redis clients) according to the platform’s standard retry policies, but those retries operate on **idempotent downstream operations only** and do not cause the script logic to execute a second time for the same trigger. Tick-level idempotent replay and recovery remain the responsibility of the Game Session Service as described in the tick and Redis architecture docs.
- To guard against **repeated hot-loop failures**, the scheduler combines per-script quotas, concurrency limits, and a **failure-rate circuit breaker**. Scripts that exceed a configurable failure threshold within a time window are temporarily placed into a `disabled_due_to_errors` state: new triggers are skipped, failures are counted, and an audit entry is written so administrators can review and re-enable the script via the Game Design or Logging & Admin tools.
- Quota enforcement (`ScriptQuotaService`) runs **before** script execution; a script that misbehaves by emitting too many triggers is constrained by its quota window and `concurrencyPolicy`. Even if the logic always throws, it cannot exceed its configured execution rate, and the combination of quotas plus the circuit breaker prevents runaway resource usage.

The table below summarizes **which outcomes are retried** and which are treated as final for a given trigger:

| Outcome / error class | Typical source | Scheduler retry behavior |
| --- | --- | --- |
| `success` | Script ran and enqueued commands | No retry (completed) |
| `quota_denied` | `ScriptQuotaService` limit exceeded before execution | No retry for this trigger; future triggers may succeed when quota window resets |
| `sandbox_error` | Exception in sandboxed DSL runtime, validation failure, rejected operation | No retry; recorded as a failure, may contribute to circuit breaker and `disabled_due_to_errors` |
| `validation_error` | Static validation on inputs or script config fails | No retry; treat as a permanent failure until configuration changes |
| `disabled_due_to_errors` | Circuit breaker opened for the script | No retry while disabled; new triggers are skipped until operators re-enable the script |
| `version_unavailable` / `skipped_version_unavailable` | Trigger references a scriptPatchVersion that failed reload or is unknown | No retry; audit and metrics record the drop so design/ops can fix the version mapping |
| `infrastructure_error` (e.g., gRPC `UNAVAILABLE`, `DEADLINE_EXCEEDED`, transient Redis timeout) | Network hiccups, temporary downstream unavailability | **May be retried** according to platform retry policy (bounded attempts, backoff, and idempotent downstream handlers) |
| `INVALID_ARGUMENT` / `PERMISSION_DENIED` / other client or policy errors | Callers sent bad data or lack permission | No retry; callers must correct inputs or permissions |

**Outcome-to-metric mapping**

Common outcomes are surfaced in metrics as follows (labels such as `tenantId`, `scriptId`, and `eventType` are omitted here for brevity):

- `success` → `automation_script_triggers_total{outcome="success"}` and, when commands are enqueued, `automation_tick_events_enqueued_total`.
- `quota_denied` → `script_quota_denied_total` and `automation_script_triggers_dropped_total{reason="quota"}`.
- `sandbox_error` → `automation_script_triggers_total{outcome="sandbox_error"}` and may contribute to `automation_script_triggers_dropped_total{reason="sandbox_error"}` depending on whether commands were enqueued before failure.
- `validation_error` → `automation_script_triggers_total{outcome="validation_error"}`.
- `disabled_due_to_errors` → `automation_script_skips_total{reason="disabled_due_to_errors"}` and `automation_script_triggers_dropped_total{reason="disabled_due_to_errors"}`.
- `version_unavailable` / `skipped_version_unavailable` → `automation_script_triggers_dropped_total{reason="version_unavailable"}`.
- `infrastructure_error` → `automation_script_triggers_total{outcome="infrastructure_error"}` and, when retries are exhausted, may also increment `automation_script_triggers_dropped_total{reason="infrastructure_error"}`.

## Integration with Game Logic & Tick System

- **Scripts do not execute inside the tick system.** The Automation & Scripting Service evaluates scripts independently—on a schedule, via timers, or in response to events—and enqueues the resulting commands into each entity's command queue.
- These queued commands run during the **next tick cycle** via the normal Game Session and Game Logic flow, ensuring deterministic, replayable behavior that follows the tick system's fairness and retry rules.
- Script evaluation never blocks or interferes with tick execution. Scripts can still react to world events, NPC states, or timers provided by the tick system.
- Script-generated commands—like any gameplay command—may fail due to lock contention or target remote regions. These cases are automatically handled by the Game Session Service via standard tick rescheduling and cross-region routing logic.
- The Automation & Scripting Service only determines which commands to inject. It may query world state via gRPC but never mutates entity or world data directly—every action passes through the Game Session Service so tick regions remain consistent.
- **ScriptTickService** stages events in Redis before committing them to the tick queues. It uses a **per-script automation tick namespace**:
  - `automation:tick:{tenantId}:{scriptId}:lock`
  - `automation:tick:{tenantId}:{scriptId}:queue`
  - `automation:tick:{tenantId}:{scriptId}:pending`

  Keys within this namespace share a hash tag on `{tenantId}:{scriptId}` so multi-key Lua operations remain shard-local in Redis Cluster. These automation tick locks are **separate from the game tick locks** (`tick:{tenantId}:{regionId}:lock:{entityId}`) managed by the Game Session Service. Script ticks never bypass entity-level locking or tick isolation; they only batch and stage automation events before handing them to the normal tick pipeline. See [Tick System and Runtime Design](./system-architecture-ticks.md) for how staged commands are processed once they enter the per-entity command queues.

### Ordering Between Player and Script Commands

- Each entity has a **single authoritative command queue** in Redis (for example, `tick:{tenantId}:{regionId}:queue:{entityId}`) that aggregates both player-originated commands and script-generated commands.
- Player commands and automation commands are appended to this queue in the order they are accepted by the Game Session Service and the Automation & Scripting Service. Within a given entity’s queue, commands are therefore processed in **FIFO order**, regardless of whether they came from a player or a script.
- During tick processing, the Game Session Service:
  - Reads at most one command per entity per tick from this combined queue.
  - Applies its existing fairness and conflict-resolution rules (as described in the tick architecture) when deciding which entities to service on a given tick.
- Script-generated commands carry `scriptEventId`, `scriptId`, and (when applicable) upstream ordering tokens such as `tickId` from custom events. Combined with the per-entity FIFO queue and the monotonic `tickId` stream, this ensures that:
  - The order in which commands affect an entity is deterministic for a given event stream and configuration.
  - Automation ticks cannot “jump ahead of” or reorder already-queued player commands for the same entity; they simply contribute additional commands into the same ordered queue that ticks consume.

### Idempotency and Replay

- Script executions are treated as **at-most-once per trigger** at the scheduler level, but the resulting commands participate in the same **idempotent replay model** as other tick actions. Ticks may retry commands after lock contention or crash recovery as described in [Tick System and Runtime Design](./system-architecture-ticks.md) and [Redis Architecture](./system-architecture-redis.md).
- To support this, script-generated commands must be **idempotent with respect to `tickId` and `scriptEventId`**. These identifiers travel with the command payload and are recorded alongside `scriptId` and `tenantId` in `script_event_audit` records and logs so operators can correlate replays and ensure side effects remain consistent even when ticks are retried.

### Script Timers vs Tick Timers

- Core gameplay timers (cooldowns, regeneration, generic delayed effects) live in the **Game Session Service** under `timer:{tenantId}:{regionId}` and are processed as part of each region’s tick loop (see [Timers and Time Scaling](./system-architecture-ticks.md#timers-and-time-scaling)). These timers are governed by `game.tick-max-timers` and share pacing with other tick work.
- Scripted timers power `onTimerExpire`, `onInterval`, and `intervalTicks` scheduling. They are stored in **Automation & Scripting–scoped keys** such as `automation:timer:{tenantId}:{regionId}` and `automation:script:{tenantId}:{scriptId}:timer`, which share the same hash tags as the region’s tick keys for locality but are not mixed into the core `timer:{tenantId}:{regionId}` ZSET.
- The script scheduler converts timer expirations into **script triggers**, then enqueues resulting commands into the same per-entity command queues that ticks consume. This keeps script timing decisions decoupled from tick ownership while still aligning execution with the canonical `tickId` stream.
- Script timers obey their own **per-tick and per-window limits** controlled by automation-specific settings such as `AUTOMATION_TICK_MAX_EVENTS` and `AUTOMATION_TICK_BUDGET_MS`, in addition to per-script quotas. They do **not** count against `game.tick-max-timers`; instead, they are bounded by `automation.tick-max-events` as they are staged into tick queues.
- This separation avoids double-scheduling and unexpected load coupling: tick timers determine when gameplay effects should fire within a region, while script timers determine **when scripts decide to enqueue actions**. Both ultimately converge on the same tick-based command queues, but each subsystem enforces its own quotas and per-tick limits.

## Scheduler Leadership & Coordination

The script scheduler runs inside a small cohort of Automation & Scripting Service instances. Each node competes for a **leadership lease** in Redis and the current leader is responsible for driving timers and scheduled triggers. Leadership uses short-lived leases (for example, 5 seconds) keyed by `script-leader:{tenantId}`; leaders refresh the lease via heartbeats and pause scheduling if their renewal fails, allowing another node to take over without duplicated work.

Leaders consume the tick heartbeat stream produced by the Game Session Service (see [Tick System and Runtime Design](./system-architecture-ticks.md#tick-events)). That stream provides a monotonically increasing `tickId` per `{tenantId, regionId}`. By counting tick events, the scheduler knows when “every N ticks” has elapsed without needing to control why ticks fire. Each tick event includes shard metadata, so multiple leaders can coordinate per-shard schedules without overlapping; if a leader misses a tick it simply replays the delta against the stored `lastTickId` before continuing.

To make this stream resumable across leadership changes, the scheduler stores the **last processed tick** per `{tenantId, regionId}` in Redis under a key such as `script-scheduler:{tenantId}:{regionId}:lastTickId` (sharing the same hash tag as the region’s tick keys). When a new leader takes over, it:

- Reads `lastTickId` for each region it owns.
- Compares it to the latest `tickId` observed on the heartbeat stream.
- Computes which “every N ticks” boundaries have passed since `lastTickId` and enqueues any missing triggers exactly once before continuing from the current `tickId`.

Multiple leaders may exist for multi-tenant isolation (one leader per tenant shard or script group). Each script’s metadata stores scheduling rules, concurrency policy, and type tags (e.g., `npc-behavior`, `world-background`, `maintenance`). The leader uses this metadata plus observed tick counts, `lastTickId` state, and available quotas to decide when to enqueue the next execution.

Automation-specific keys such as `script-leader:{tenantId}` and `script-scheduler:{tenantId}:{regionId}:lastTickId` follow the same naming and hash-tagging conventions described in [Redis Architecture – Key Naming and Shard Discipline](./system-architecture-redis.md#🗂️-key-naming-and-shard-discipline). For a full catalog of tick and lock keys, see [Redis Architecture – Key Format Examples](./system-architecture-redis.md#key-format-examples); this document only calls out the scripting-specific keys used by the scheduler.

### Leadership Scope and Failure Semantics

- **Leadership scope**
  - By default, leadership is **one-per-tenant**: a single `script-leader:{tenantId}` key elects the Automation & Scripting Service instance responsible for that tenant’s automation workload across all regions.
  - Larger deployments may introduce sharding (for example, `script-leader:{tenantId}:{shardId}` or script-group–specific leases) so multiple leaders can coordinate independent subsets of a tenant’s scripts. When sharding is enabled, each shard’s leader still obeys the same lease and heartbeat rules described above; the mapping from `{tenantId, regionId}` to `{tenantId, shardId}` is defined in configuration and documented alongside the multi-tenancy design.

- **Heartbeat loss vs Redis availability**
  - If a leader loses access to the tick heartbeat stream (for example, due to network partition or Game Session unavailability) but can still reach Redis, it treats the tick timeline as **unreliable**:
    - `onInterval` and other cadence-based triggers pause for the affected `{tenantId, regionId}` entries rather than extrapolating tick counts locally.
    - One-off event-driven triggers that do not depend on tick cadence may continue to run if safe and configured to do so, but the recommended default is to bias toward pausing automation rather than drifting away from the canonical tick stream.
  - Leaders log structured warnings and emit metrics (for example `automation_script_leadership_changes_total` and a heartbeat health gauge) so operators can detect and remediate heartbeat issues.

- **Reload windows and RELOADING state**
  - During a script reload for `{tenantId, pendingPatchVersion}`, leaders set `reloadState=RELOADING` and **pause new triggers** for that tenant’s scripts until the new definitions are loaded and validated.
  - Reloads are expected to be **short-lived** (on the order of seconds). A configurable threshold (for example `SCRIPT_RELOAD_MAX_PAUSE_SECONDS`) defines how long automation may remain paused in `RELOADING` before warnings and alerts fire.
  - While in `RELOADING`:
    - In-flight script executions are allowed to finish.
    - New triggers for affected scripts are queued or skipped according to policy, but no script runs against a partially-loaded definition.
    - Gameplay correctness remains governed by the tick system; the impact of extended reloads is increased latency or gaps in automation, not inconsistent authoritative state.

## Per-Script Scheduling Policies

Scripts bring configurable guards so workloads behave under load:

- **`intervalTicks`** defines the target cadence (e.g., 10). The scheduler increments a counter using the tick stream and enqueues a run when the configured interval is reached, ensuring scripts stay aligned with the canonical `tickId`.
- **`concurrencyPolicy`** is either `drop_new` (skip new triggers while the previous run is still active) or `queue_until_free` (retain the trigger in a short waiting queue until the running instance finishes). Running instances are never preempted; the policy only governs how new triggers are handled. Queued triggers count toward the `ScriptQuotaService` window so scripts cannot keep backing up indefinitely—once the quota is reached the scheduler drops the oldest pending trigger (counted by `automation_script_triggers_dropped_total`) and records an audit entry with outcome `dropped_quota`.
- **`maxConcurrent`** restricts how many instances can execute simultaneously, helping you bound resource use for noisy background scripts and preventing starvation of higher-tier workloads.
- **`priorityTag`** assigns a tier (`high`, `normal`, `background`). Each tier has an enqueue budget per minute (default: high=8, normal=4, background=2), and the scheduler accounts for both that budget and any outstanding `ScriptQuotaService` usage before granting a slot. High-tier scripts keep their allocation even under pressure, while background tier scripts may be deferred to preserve responsiveness for NPC and world-critical behaviors.

These settings can be updated via the Game Design Service’s script editor. Version metadata ensures the scheduler executes the configuration that matches the pinned `scriptPatchVersion`.

Once a script run emits commands, **tick fairness rules take over**: commands are appended to the per-entity queues and the Game Session Service still executes at most one command per entity per tick in FIFO order. `priorityTag` influences **which scripts get to enqueue work and how often**, but it does not change per-entity ordering or the deterministic conflict resolution defined in the tick system.

### Resource Isolation and Multi-Level Budgets

- **Per-script budgets**: Each script is bounded by its own quota window (`SCRIPT_QUOTA_LIMIT` / `SCRIPT_QUOTA_WINDOWSECONDS`), `intervalTicks`, `maxConcurrent`, and `priorityTag`. These caps ensure that no single script can dominate Automation & Scripting Service capacity, even if it is triggered frequently.
- **Per-tenant budgets**: Leaders also maintain **tenant-scoped aggregates** per tier, such as `automation_script_tenant_budget_seconds{tenantId, tier}`. Each tenant receives a configurable slice of automation throughput per tier; if a tenant exceeds its budget in a window, lower-priority scripts for that tenant are throttled or skipped (`automation_script_skips_total` tagged with `reason=tenant_budget_exceeded`) while other tenants continue to make progress.
- **Cluster-level safety limits**: The Automation & Scripting Service instances enforce global ceilings on automation work (for example, total automation CPU budget per second and `AUTOMATION_TICK_MAX_EVENTS` across all tenants and regions). When these cluster-level limits are reached, the scheduler favors `high`-priority, latency-sensitive scripts and defers or drops `background` work, emitting metrics so operators can tune capacity.
- `priorityTag` interacts with these budgets at each level: high-priority scripts retain their share of per-script, per-tenant, and cluster budgets as long as possible, while `background` scripts are the first to be throttled when tenant or cluster-wide automation usage approaches configured limits.

Together, these **per-script**, **per-tenant**, and **cluster-wide** caps form a noisy-tenant protection story that aligns with the broader multi-tenancy model in [System Architecture: Multi-Tenancy](./system-architecture-multi-tenancy.md). All script-side keys and metrics are scoped by `tenantId`, and leadership leases such as `script-leader:{tenantId}` ensure that each tenant’s automation workload can be reasoned about and tuned independently while still sharing the same infrastructure.

## Auditability & Metrics

Every scheduler decision emits an audit record (stored in a lightweight `script_event_audit` table in PostgreSQL) containing `(scriptEventId, scriptId, tickId, versionId, outcome, latency)`. `scriptEventId` uniquely identifies the trigger instance so retries, replays, and downstream side effects can be correlated across logs, metrics, and traces. Metrics include:

- **Scheduler metrics** – `automation_script_triggers_total`, `automation_script_skips_total` (broken out by policy), `automation_script_queue_delay_seconds` for queued triggers waiting on concurrency limits, `automation_script_leadership_changes_total` to monitor failovers, and `automation_script_triggers_dropped_total` to capture quota/queue drops so operators can tune `ScriptQuotaService` windows.
- **Quota metrics** – `script_quota_allowed_total` and `script_quota_denied_total` (shared with the Automation & Scripting Service README) track per-script quota decisions in a consistent way across documentation and implementations.

Logs annotate each audit row with the scheduler lease holder and tick details, making it easier to trace why a timer fired or was dropped.

The list above highlights the most important cross-cutting metrics for the scripting architecture. The **Automation & Scripting Service README** remains the **single source of truth** for the complete set of service-specific metrics, their labels, and any future additions or removals.

Audit records remain available for troubleshooting for the first 30 days or until the table reaches 1,000,000 rows, whichever comes first; a nightly maintenance job truncates old entries to keep storage bounded while preserving recent history.
Operators tune retention via `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` (default `30`) and `SCRIPT_EVENT_AUDIT_MAX_ROWS` (default `1000000`), ensuring the cleanup job can safely trim both by row count and elapsed duration.

## Hot Reload & Resume Behavior

At any point in time, the Automation & Scripting Service tracks, per tenant, an **active script patch version** (`activePatchVersion`) and may temporarily hold a **pending script patch version** (`pendingPatchVersion`) while a reload is in progress. A simple `reloadState` (`IDLE`, `RELOADING`, or `FAILED`) describes where a tenant currently sits in the reload lifecycle.

When a new script version is published, the Game Design Service calls `NotifyScriptVersionUpdate` with the target `{tenantId, scriptPatchVersion}`:

- Leaders set `pendingPatchVersion` for that tenant and flip `reloadState=RELOADING` while **leaving `activePatchVersion` unchanged**.
- Scheduling for that tenant is paused (leaders stop processing the automation tick stream), but in-progress executions finish without interruption.
- Pending triggers remain in the scheduler queue, bound to the tenant/shard, and resume after reload with whatever version ends up as `activePatchVersion`. Their `nextTick` is recalculated based on the latest tick count so timers and intervals remain coherent.
- Leaders coordinate with `ScriptVersionService` to load and validate the pending scripts, then listen for a `reloadComplete` confirmation before resuming scheduling. This explicit “pause until safe” handshake ensures no trigger runs against a partially-loaded definition.

Once reload succeeds, leaders atomically switch `activePatchVersion` to the new value, clear `pendingPatchVersion`, set `reloadState=IDLE`, and resume normal scheduling. From the perspective of callers, script execution jumps from the previous patch to the new patch without ever exposing a mixed or half-applied state.

### Reload Failure Handling

If reload or validation fails for `{tenantId, pendingPatchVersion}` (for example, due to a compilation error, incompatible component versions, or a sandbox configuration problem), the system **fails safely back** to the last known good patch:

- `activePatchVersion` remains unchanged; the new patch never becomes the active runtime configuration.
- `pendingPatchVersion` is marked as failed (for example, `FAILED_VALIDATION`) and `reloadState` is set to `FAILED` along with an error reason.
- Leaders discard any partially loaded state for the pending patch and resume scheduling using the existing `activePatchVersion`, ensuring automation continues to run on the previous, validated script set.
- `ScriptVersionService` emits a failure result (for example, `reloadComplete(failure)`) back to the Game Design Service so designers and operators can see that the publish did not take effect and inspect the error details.

Reload success is determined by the **leaders currently responsible for that tenant’s shards**. As long as each leader instance reloads and validates the pending patch successfully, the reload is considered accepted; non-leader replicas may pick up the new patch lazily or on restart. In very rare cases where some replicas fail to reload while leaders succeed, the platform treats this as a recoverable operational issue (for example, by restarting the unhealthy instances) rather than blocking the reload or running a partially active patch.

Triggers that reference a **failed** patch version are rejected explicitly:

- If the Game Session Service or another caller sends a trigger pinned to a `scriptPatchVersion` that is marked as failed or unknown for a tenant, the Automation & Scripting Service does not attempt to quietly fall back to the previous patch.
- Instead, it records an audit entry with an outcome such as `skipped_version_unavailable`, increments `automation_script_triggers_dropped_total{reason=version_unavailable}`, and returns an appropriate error or no-op outcome to the caller depending on the API contract.

This behavior makes reload outcomes predictable: scripts either continue to run on the prior patch, or they run on the new patch everywhere that matters. A bad patch cannot partially apply or silently change behavior; it simply fails to become active until the underlying issue is fixed and a new publish succeeds.

## Deployment & Versioning

- Script definitions are stored in the **Automation & Scripting Service** database and versioned alongside other game assets. Publishing updates from the Game Design Service is supported.
- Designers can deploy updated scripts without redeploying code. The Automation & Scripting Service retrieves the current live versions as needed.
- Script-only patches create a `scriptPatchVersion` (the logical/API name) tied to a `baseVersionId` so new behaviors can be loaded on the fly. In the Game Session Service database this is persisted as `script_patch_version`. See [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#script-only-patch-versions) for how these patch versions work.
- The Game Session Service stores the active `scriptPatchVersion` for each running game. When a new patch is published, the Game Design Service calls `NotifyScriptVersionUpdate`, allowing the Automation & Scripting Service to reload updated scripts via `ScriptVersionService` without downtime.
- Timer events and scheduled evaluations always reference the version pinned by the Game Session Service at the moment they run.
- Older versions remain in the database for auditing or rollback, but only the pinned version is executed.

## Fairness & Abuse Prevention

The Automation & Scripting Service enforces several safeguards to prevent runaway
scripts and ensure fair resource usage:

- `ScriptQuotaService` limits how often a script may execute within a configurable
  window. **Quota checks happen before commands are enqueued**, so abusive scripts never reach
  the tick queues. When the quota is exceeded the event is ignored and the
  `script_quota_denied_total` metric is incremented. Successful executions are tracked via
  `script_quota_allowed_total`.
- The tick system only processes these queued commands—it never runs script logic itself.
- Metrics such as `automation_tick_events_enqueued_total`, `script_quota_allowed_total`, and `script_quota_denied_total` expose script activity for monitoring.
- Administrators may disable or throttle problematic scripts via the Game Design
  Service, which updates definitions and triggers hot reloads in the Automation &
  Scripting Service.
 - These quota and throttling controls work **in combination with** the failure-rate circuit breaker described under [Failure Modes and Error Handling](#failure-modes-and-error-handling), which can automatically place misbehaving scripts into a `disabled_due_to_errors` state when error rates exceed configured thresholds.

#### Operational Disable / Throttle Flows

- **Disable now (hard stop)** – When an administrator marks a script as disabled in the Game Design or Logging & Admin tools, the Automation & Scripting Service flips a `runtimeStatus=DISABLED` flag in script metadata. The scheduler stops accepting **new triggers** for that script immediately (treating them as `skipped_disabled` in audit records), but does not preempt in-flight runs; they are allowed to complete under existing quotas.
- **Soft-disable after current run** – For scripts that should drain gracefully, administrators can set `runtimeStatus=DISABLE_AFTER_DRAIN`. The scheduler continues to run any currently queued triggers up to a small grace window, then transitions the script to `DISABLED` once its active and queued counts reach zero. Subsequent triggers are skipped and logged as `skipped_disabled`.
- **Throttling** – Throttling is modeled as a temporary adjustment of per-script and per-tenant budgets rather than a separate toggle. Operators can reduce `SCRIPT_QUOTA_LIMIT`, increase `intervalTicks`, or change `priorityTag` to `background`; the scheduler immediately applies the new configuration when evaluating triggers. In addition, the failure-rate circuit breaker may place a script into `runtimeStatus=DISABLED_DUE_TO_ERRORS`, which behaves like a hard disable until an administrator explicitly clears the status.
- All disable/enable and throttle actions are **idempotent** and recorded in the `script_event_audit` table/feed with the acting principal (where available), so operators can trace when and why a script stopped executing.

### Environment Variables

The **authoritative, up-to-date list of environment variables and defaults** lives in the Automation & Scripting Service README (`design/architecture/microservices/automation-scripting-service/README.md#environment-variables`). This architecture doc only highlights the most important knobs conceptually:

- `SCRIPT_QUOTA_LIMIT` / `SCRIPT_QUOTA_WINDOWSECONDS` – control per-script quota windows used by `ScriptQuotaService`.
- `AUTOMATION_TICK_DURATION_MS` / `AUTOMATION_TICK_MAX_EVENTS` / `AUTOMATION_TICK_BUDGET_MS` – bound how much automation work `ScriptTickService` performs per script tick.
- `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` / `SCRIPT_EVENT_AUDIT_MAX_ROWS` – govern how long `script_event_audit` records remain available for troubleshooting.

For exact defaults, additional flags, and any future additions, always refer to the service README.

---

By constraining scripts to curated components and enforcing strict quotas, FireMUD delivers powerful automation tools while maintaining security and fair resource usage across all hosted games.

## Developer Tools

Several helper scripts streamline common tasks:

- `dev-tools/firemud-cli.sh` – command-line utility for starting and stopping the local stack.
- `dev-tools/docs/generate-erd.sh` – produces Entity Relationship Diagrams for each service.
- `dev-tools/docs/generate-grpc-docs.sh` – generates Markdown documentation from protobuf definitions.
- `dev-tools/seed/seed-automation-scripting-data.sh` – populates the Automation & Scripting Service with sample scripts, actions, and quotas so you can observe scheduler behavior without manual editing.

These scripts complement the web-based editor and allow creators to automate routine actions.
