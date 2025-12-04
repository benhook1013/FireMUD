# FireMUD System Architecture: Scripting & Automation Framework

This document outlines how FireMUD executes custom in-game behavior through a sandboxed scripting framework. It complements the [Automation & Scripting Service](./microservices/automation-scripting-service/README.md) and expands on the extensibility goals in the [core requirements](../project-management/core-requirements.md).

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
  - Scheduler leadership leases, per-region tick-stream consumption, and long-term audit retention are designed here; operators should verify concrete key names, metrics, and retention jobs against the current Automation & Scripting Service implementation and operations runbooks.

Maintainers should update this section whenever major scripting features land or significant architecture pieces change so it remains a reliable guide to what is live versus aspirational.

---

## Goals

- Enable **event-driven scripting** and **NPC automation** so worlds feel alive even without active players.
- Keep the system **extensible** while preventing malicious or abusive scripts.
- Support **persistence** and versioned updates so game creators can iterate safely.

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

### Component Versioning and Backwards Compatibility

- Each DSL component (node type) is versioned independently, for example `HealthCheck@v1`, `HealthCheck@v2`. Published scripts reference both the component key and its version so the Automation & Scripting Service can load the correct behavior for a given `scriptPatchVersion`.
- When a component evolves in a **backwards-compatible** way (adding an optional field or new output), a new minor version is registered and existing scripts remain pinned to their original version. The visual editor may offer an automatic migration that rewrites graphs to the new version, but runtime does not change behavior until a script revision is published.
- **Breaking changes** result in a new major component version (for example, `ReputationCheck@v2` with different output states). Existing scripts keep using the prior version and are flagged in the Game Design Service UI as “upgrade available” so designers can migrate them explicitly before publishing.
- Old component versions remain loadable as long as any published script still references them. Decommissioning a version requires migrating or retiring the dependent scripts; migration tooling in the Game Design Service generates updated graphs and revalidates them against the new component schema.
- The DSL and visual mapping are described in more depth in the Game Design Service documents; see [Web-Based Visual Design Interface](./microservices/game-design-service/web-visual-interface.md) and [World Editing & Customization Tools](./microservices/game-design-service/world-editing-tools.md) for how script graphs are created, versioned, and published.

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

### Event Fan-Out and Ordering

- Each entity may have **multiple scripts bound to the same event** (for example, two `onSpawn` handlers that set patrol routes and apply buffs). At design time the Game Design Service stores these bindings as an ordered list per `{entityId, eventType}`.
- When an event fires, the Automation & Scripting Service evaluates the bound handlers in a **deterministic order** based on their declared `orderIndex` and script identifier. This ordering is stable across deployments so the same sequence of commands is enqueued given the same set of scripts.
- **Failures are isolated per script**. If one handler fails (for example, quota denial, sandbox exception, or compilation error), the scheduler records the failure, increments the appropriate metrics, and continues to the next handler unless the script is explicitly marked as `requiresExclusiveEvent` for that event. In the exclusive case, a failure short-circuits remaining handlers and the event fan-out ends early.
- Quota checks (`ScriptQuotaService`) are performed **per script** before a handler runs. If a script exceeds its quota, its handler for that event is skipped and counted as `denied`, but other scripts bound to the same entity and event may still execute if their own quotas allow it.
- Script handlers enqueue commands **independently** into the entity’s command queue. The underlying tick system applies its normal fairness rules—only one command per entity per tick is executed—so even when many scripts respond to the same event, player-visible behavior remains bounded and replayable.

## Advanced NPC Behavior Modules

- `NpcMoraleService` adjusts aggression based on health and morale so NPCs may flee or surrender.
- `PveEncounterService` generates random encounters and environmental hazards.
- `NpcFormationService` coordinates squad positioning for groups of NPCs.
Refer to the Automation & Scripting Service README for implementation details.

## Sandboxing & Security

- Script execution occurs in a **sandbox** with restricted APIs and resource limits.
- Components interact with the **Game Logic Service** through validated gRPC calls.
- The service enforces **per-script quotas** via `ScriptQuotaService`. **CPU and memory limits** are enforced automatically.

### Failure Modes and Error Handling

- Each script run produces a **structured outcome** such as `success`, `quota_denied`, `sandbox_error`, or `infrastructure_error`. Outcomes are written to the `script_event_audit` stream/table and exposed via metrics (see **Auditability & Metrics**) so operators can correlate failures with specific `scriptId`, `tenantId`, and `tickId`.
- By default, **failures are surfaced through logs and metrics**, not detailed player-visible stack traces. Players typically experience a missing or degraded behavior (for example, an NPC does not respond) unless the script deliberately enqueues a fallback command that emits a message through the Game Logic Service.
- Script executions are treated as **at-most-once per trigger**. If a handler fails due to a sandbox or validation error, the scheduler records the failure and moves on; it does not automatically retry the same script invocation to avoid hot loops and duplicate side effects. Infrastructure-level issues (for example, transient gRPC or Redis errors) follow the platform’s standard retry policies, but those retries are bounded and still respect idempotency rules in downstream services.
- To guard against **repeated hot-loop failures**, the scheduler combines per-script quotas, concurrency limits, and a **failure-rate circuit breaker**. Scripts that exceed a configurable failure threshold within a time window are temporarily placed into a `disabled_due_to_errors` state: new triggers are skipped, failures are counted, and an audit entry is written so administrators can review and re-enable the script via the Game Design or Logging & Admin tools.
- Quota enforcement (`ScriptQuotaService`) runs **before** script execution; a script that misbehaves by emitting too many triggers is constrained by its quota window and `concurrencyPolicy`. Even if the logic always throws, it cannot exceed its configured execution rate, and the combination of quotas plus the circuit breaker prevents runaway resource usage.

## Integration with Game Logic & Tick System

- **Scripts do not execute inside the tick system.** The Automation & Scripting Service evaluates scripts independently—on a schedule, via timers, or in response to events—and enqueues the resulting commands into each entity's command queue.
- These queued commands run during the **next tick cycle** via the normal Game Session and Game Logic flow, ensuring deterministic, replayable behavior that follows the tick system's fairness and retry rules.
- Script evaluation never blocks or interferes with tick execution. Scripts can still react to world events, NPC states, or timers provided by the tick system.
- Script-generated commands—like any gameplay command—may fail due to lock contention or target remote regions. These cases are automatically handled by the Game Session Service via standard tick rescheduling and cross-region routing logic.
- The Automation & Scripting Service only determines which commands to inject. It may query world state via gRPC but never mutates entity or world data directly—every action passes through the Game Session Service so tick regions remain consistent.
- **ScriptTickService** stages events in Redis before committing them to the tick queues. It uses `tick:{tenantId}:{regionId}:lock:{scriptId}` to ensure only one script tick for a given region runs at a time. These **script locks are separate from the entity locks** (`tick:{tenantId}:{regionId}:lock:{entityId}`) managed by the Game Session Service; script ticks never bypass entity-level locking or tick isolation and only inject work that the normal tick pipeline will process. See [Tick System and Runtime Design](./system-architecture-ticks.md) for how staged commands are processed.

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

## Per-Script Scheduling Policies

Scripts bring configurable guards so workloads behave under load:

- **`intervalTicks`** defines the target cadence (e.g., 10). The scheduler increments a counter using the tick stream and enqueues a run when the configured interval is reached, ensuring scripts stay aligned with the canonical `tickId`.
- **`concurrencyPolicy`** is either `drop_new` (skip new triggers while the previous run is still active) or `queue_until_free` (retain the trigger in a short waiting queue until the running instance finishes). Running instances are never preempted; the policy only governs how new triggers are handled. Queued triggers count toward the `ScriptQuotaService` window so scripts cannot keep backing up indefinitely—once the quota is reached the scheduler drops the oldest pending trigger (counted by `automation_script_triggers_dropped_total`) and records an audit entry with outcome `dropped_quota`.
- **`maxConcurrent`** restricts how many instances can execute simultaneously, helping you bound resource use for noisy background scripts and preventing starvation of higher-tier workloads.
- **`priorityTag`** assigns a tier (`high`, `normal`, `background`). Each tier has an enqueue budget per minute (default: high=8, normal=4, background=2), and the scheduler accounts for both that budget and any outstanding `ScriptQuotaService` usage before granting a slot. High-tier scripts keep their allocation even under pressure, while background tier scripts may be deferred to preserve responsiveness for NPC and world-critical behaviors.

These settings can be updated via the Game Design Service’s script editor. Version metadata ensures the scheduler executes the configuration that matches the pinned `scriptPatchVersion`.

### Resource Isolation and Multi-Level Budgets

- **Per-script budgets**: Each script is bounded by its own quota window (`SCRIPT_QUOTA_LIMIT` / `SCRIPT_QUOTA_WINDOWSECONDS`), `intervalTicks`, `maxConcurrent`, and `priorityTag`. These caps ensure that no single script can dominate Automation & Scripting Service capacity, even if it is triggered frequently.
- **Per-tenant budgets**: Leaders also maintain **tenant-scoped aggregates** per tier, such as `automation_script_tenant_budget_seconds{tenantId, tier}`. Each tenant receives a configurable slice of automation throughput per tier; if a tenant exceeds its budget in a window, lower-priority scripts for that tenant are throttled or skipped (`automation_script_skips_total` tagged with `reason=tenant_budget_exceeded`) while other tenants continue to make progress.
- **Cluster-level safety limits**: The Automation & Scripting Service instances enforce global ceilings on automation work (for example, total automation CPU budget per second and `AUTOMATION_TICK_MAX_EVENTS` across all tenants and regions). When these cluster-level limits are reached, the scheduler favors `high`-priority, latency-sensitive scripts and defers or drops `background` work, emitting metrics so operators can tune capacity.
- `priorityTag` interacts with these budgets at each level: high-priority scripts retain their share of per-script, per-tenant, and cluster budgets as long as possible, while `background` scripts are the first to be throttled when tenant or cluster-wide automation usage approaches configured limits.

## Auditability & Metrics

Every scheduler decision emits an audit record (stored in a lightweight `script_event_audit` table or Redis stream) containing `(scriptId, tickId, versionId, outcome, latency)`. Metrics include:

- **Scheduler metrics** – `automation_script_triggers_total`, `automation_script_skips_total` (broken out by policy), `automation_script_queue_delay_seconds` for queued triggers waiting on concurrency limits, `automation_script_leadership_changes_total` to monitor failovers, and `automation_script_triggers_dropped_total` to capture quota/queue drops so operators can tune `ScriptQuotaService` windows.
- **Quota metrics** – `script_quota_allowed_total` and `script_quota_denied_total` (shared with the Automation & Scripting Service README) track per-script quota decisions in a consistent way across documentation and implementations.

Logs annotate each audit row with the scheduler lease holder and tick details, making it easier to trace why a timer fired or was dropped.

Audit records remain available for troubleshooting for the first 30 days or until the table reaches 1,000,000 rows, whichever comes first; a nightly maintenance job truncates old entries to keep storage bounded while preserving recent history.
Operators tune retention via `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` (default `30`) and `SCRIPT_EVENT_AUDIT_MAX_ROWS` (default `1000000`), ensuring the cleanup job can safely trim both by row count and elapsed duration.

## Hot Reload & Resume Behavior

When a new script version is published, the Game Design Service calls `NotifyScriptVersionUpdate`. Leaders pause scheduling (stop processing the tick stream) until the reload completes; in-progress executions finish without interruption. Pending triggers remain in the scheduler queue, bound to the tenant/shard, and resume after the reload with the new metadata (their `nextTick` is recalculated based on the latest tick count). Leaders listen for the matching `reloadComplete` confirmation from `ScriptVersionService` before resuming, ensuring there is an explicit “pause until safe” handshake so no trigger runs against a partially-loaded definition. This avoids mid-run swapping or lost timers, while still allowing refreshed scripts to take over once the system resumes.

## Deployment & Versioning

- Script definitions are stored in the **Automation & Scripting Service** database and versioned alongside other game assets. Publishing updates from the Game Design Service is supported.
- Designers can deploy updated scripts without redeploying code. The Automation & Scripting Service retrieves the current live versions as needed.
- Script-only patches create a `scriptPatchVersion` tied to a `baseVersionId` so new behaviors can be loaded on the fly. See [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#script-only-patch-versions) for how these patch versions work.
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

#### Operational Disable / Throttle Flows

- **Disable now (hard stop)** – When an administrator marks a script as disabled in the Game Design or Logging & Admin tools, the Automation & Scripting Service flips a `runtimeStatus=DISABLED` flag in script metadata. The scheduler stops accepting **new triggers** for that script immediately (treating them as `skipped_disabled` in audit records), but does not preempt in-flight runs; they are allowed to complete under existing quotas.
- **Soft-disable after current run** – For scripts that should drain gracefully, administrators can set `runtimeStatus=DISABLE_AFTER_DRAIN`. The scheduler continues to run any currently queued triggers up to a small grace window, then transitions the script to `DISABLED` once its active and queued counts reach zero. Subsequent triggers are skipped and logged as `skipped_disabled`.
- **Throttling** – Throttling is modeled as a temporary adjustment of per-script and per-tenant budgets rather than a separate toggle. Operators can reduce `SCRIPT_QUOTA_LIMIT`, increase `intervalTicks`, or change `priorityTag` to `background`; the scheduler immediately applies the new configuration when evaluating triggers. In addition, the failure-rate circuit breaker may place a script into `runtimeStatus=DISABLED_DUE_TO_ERRORS`, which behaves like a hard disable until an administrator explicitly clears the status.
- All disable/enable and throttle actions are **idempotent** and recorded in the `script_event_audit` feed with the acting principal (where available), so operators can trace when and why a script stopped executing.

### Environment Variables

The scripting engine exposes several environment variables so operators can tune quotas and tick behavior:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `SCRIPT_QUOTA_LIMIT` | Number of events a script may process per window | `50` |
| `SCRIPT_QUOTA_WINDOWSECONDS` | Length of the quota window in seconds | `60` |
| `AUTOMATION_TICK_DURATION_MS` | Duration of a processing tick in milliseconds | `1000` |
| `AUTOMATION_TICK_MAX_EVENTS` | Max events staged from the automation queue each tick | `50` |
| `AUTOMATION_TICK_BUDGET_MS` | Soft execution budget for a script tick in milliseconds | `100` |
| `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` | Number of days to retain script audit records before cleanup | `30` |
| `SCRIPT_EVENT_AUDIT_MAX_ROWS` | Maximum number of rows to keep in the script audit store before truncation | `1000000` |

These variables map to Spring Boot properties `script.quota.limit`, `script.quota.windowSeconds`, `automation.tick-duration-ms`, `automation.tick-max-events`, and `automation.tick-budget-ms`.

See the [Automation & Scripting Service README](./microservices/automation-scripting-service/README.md#environment-variables) for default values and additional details.

---

By constraining scripts to curated components and enforcing strict quotas, FireMUD delivers powerful automation tools while maintaining security and fair resource usage across all hosted games.

## Developer Tools

Several helper scripts streamline common tasks:

- `dev-tools/firemud-cli.sh` – command-line utility for starting and stopping the local stack.
- `dev-tools/docs/generate-erd.sh` – produces Entity Relationship Diagrams for each service.
- `dev-tools/docs/generate-grpc-docs.sh` – generates Markdown documentation from protobuf definitions.
- `dev-tools/seed/seed-automation-scripting-data.sh` – populates the Automation & Scripting Service with sample scripts, actions, and quotas so you can observe scheduler behavior without manual editing.

These scripts complement the web-based editor and allow creators to automate routine actions.
