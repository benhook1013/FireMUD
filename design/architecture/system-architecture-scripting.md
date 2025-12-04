# FireMUD System Architecture: Scripting & Automation Framework

This document outlines how FireMUD executes custom in-game behavior through a sandboxed scripting framework. It complements the [Automation & Scripting Service](./microservices/automation-scripting-service/README.md) and expands on the extensibility goals in the [core requirements](../project-management/core-requirements.md).

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

## Advanced NPC Behavior Modules

- `NpcMoraleService` adjusts aggression based on health and morale so NPCs may flee or surrender.
- `PveEncounterService` generates random encounters and environmental hazards.
- `NpcFormationService` coordinates squad positioning for groups of NPCs.
Refer to the Automation & Scripting Service README for implementation details.

## Sandboxing & Security

- Script execution occurs in a **sandbox** with restricted APIs and resource limits.
- Components interact with the **Game Logic Service** through validated gRPC calls.
- The service enforces **per-script quotas** via `ScriptQuotaService`. **CPU and memory limits** are enforced automatically.

## Integration with Game Logic & Tick System

- **Scripts do not execute inside the tick system.** The Automation & Scripting Service evaluates scripts independently—on a schedule, via timers, or in response to events—and enqueues the resulting commands into each entity's command queue.
- These queued commands run during the **next tick cycle** via the normal Game Session and Game Logic flow, ensuring deterministic, replayable behavior that follows the tick system's fairness and retry rules.
- Script evaluation never blocks or interferes with tick execution. Scripts can still react to world events, NPC states, or timers provided by the tick system.
- Script-generated commands—like any gameplay command—may fail due to lock contention or target remote regions. These cases are automatically handled by the Game Session Service via standard tick rescheduling and cross-region routing logic.
- The Automation & Scripting Service only determines which commands to inject. It may query world state via gRPC but never mutates entity or world data directly—every action passes through the Game Session Service so tick regions remain consistent.
- **ScriptTickService** stages events in Redis before committing them to the tick queues. It uses `tick:{tenantId}:{regionId}:lock:{scriptId}` to ensure only one script tick for a given region runs at a time. See [Tick System and Runtime Design](./system-architecture-ticks.md) for how staged commands are processed.

## Scheduler Leadership & Coordination

The script scheduler runs inside a small cohort of Automation & Scripting Service instances. Each node competes for a **leadership lease** in Redis and the current leader is responsible for driving timers and scheduled triggers. Leadership uses short-lived leases (for example, 5 seconds) keyed by `script-leader:{tenantId}`; leaders refresh the lease via heartbeats and pause scheduling if their renewal fails, allowing another node to take over without duplicated work.

Leaders consume the tick heartbeat stream produced by the Game Session Service (see [Tick System and Runtime Design](./system-architecture-ticks.md#tick-events)). That stream provides a monotonically increasing `tickId` per `{tenantId, regionId}`. By counting tick events, the scheduler knows when “every N ticks” has elapsed without needing to control why ticks fire. Each tick event includes shard metadata, so multiple leaders can coordinate per-shard schedules without overlapping.

To make this stream resumable across leadership changes, the scheduler stores the **last processed tick** per `{tenantId, regionId}` in Redis under a key such as `script-scheduler:{tenantId}:{regionId}:lastTickId` (sharing the same hash tag as the region’s tick keys). When a new leader takes over, it:

- Reads `lastTickId` for each region it owns.
- Compares it to the latest `tickId` observed on the heartbeat stream.
- Computes which “every N ticks” boundaries have passed since `lastTickId` and enqueues any missing triggers exactly once before continuing from the current `tickId`.

Multiple leaders may exist for multi-tenant isolation (one leader per tenant shard or script group). Each script’s metadata stores scheduling rules, concurrency policy, and type tags (e.g., `npc-behavior`, `world-background`, `maintenance`). The leader uses this metadata plus observed tick counts, `lastTickId` state, and available quotas to decide when to enqueue the next execution.

## Per-Script Scheduling Policies

Scripts bring configurable guards so workloads behave under load:

-- `intervalTicks` defines the target cadence (e.g., 10). The scheduler increments a counter and enqueues a run when the tick stream indicates the interval completed.
-- `concurrencyPolicy` is either `drop_new` (skip new triggers while the previous run is still active) or `queue_until_free` (retain the trigger in a short waiting queue until the running instance finishes). Running instances are never preempted; the policy only governs how new triggers are handled. Queued triggers count toward the `ScriptQuotaService` window so scripts cannot keep backing up indefinitely—once the quota is reached the scheduler starts dropping the oldest pending trigger or refuses to queue more entries.
-- `maxConcurrent` restricts how many instances can execute simultaneously, helping you bound resource use for noisy background scripts.
-- `priorityTag` assigns a tier (`high`, `normal`, `background`). Each tier has a simple enqueue budget per minute; the scheduler prefers higher tiers when pending triggers accumulate. Background/maintenance scripts may be throttled or deferred when CPU/memory budgets are tight, while NPC behavior and critical world ticks keep their slot allocation so gameplay remains responsive.

These settings can be updated via the Game Design Service’s script editor. Version metadata ensures the scheduler executes the configuration that matches the pinned `scriptPatchVersion`.

## Auditability & Metrics

Every scheduler decision emits an audit record (stored in a lightweight `script_event_audit` table or Redis stream) containing `(scriptId, tickId, versionId, outcome, latency)`. Metrics include:

- `automation_script_triggers_total` and `automation_script_skips_total` (broken out by policy).
- `automation_script_queue_delay_seconds` for queued triggers waiting on concurrency limits.
- `automation_script_leadership_changes_total` to monitor failovers.
- `automation_script_quota_denied_total` and `.allowed` already exist for quota enforcement.

Logs annotate each audit row with the scheduler lease holder and tick details, making it easier to trace why a timer fired or was dropped.

## Hot Reload & Resume Behavior

When a new script version is published, the Game Design Service calls `NotifyScriptVersionUpdate`. Leaders pause scheduling (stop processing the tick stream) until the reload completes; in-progress executions finish without interruption. Pending triggers remain in the scheduler queue, bound to the tenant/shard, and resume after the reload with the new metadata (their `nextTick` is recalculated based on the latest tick count). This avoids mid-run swapping or lost timers, while still allowing refreshed scripts to take over once the system resumes.

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

### Environment Variables

The scripting engine exposes several environment variables so operators can tune quotas and tick behavior:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `SCRIPT_QUOTA_LIMIT` | Number of events a script may process per window | `50` |
| `SCRIPT_QUOTA_WINDOWSECONDS` | Length of the quota window in seconds | `60` |
| `AUTOMATION_TICK_DURATION_MS` | Duration of a processing tick in milliseconds | `1000` |
| `AUTOMATION_TICK_MAX_EVENTS` | Max events staged from the automation queue each tick | `50` |
| `AUTOMATION_TICK_BUDGET_MS` | Soft execution budget for a script tick in milliseconds | `100` |

These variables map to Spring Boot properties `script.quota.limit`, `script.quota.windowSeconds`, `automation.tick-duration-ms`, `automation.tick-max-events`, and `automation.tick-budget-ms`.

See the [Automation & Scripting Service README](./microservices/automation-scripting-service/README.md#environment-variables) for default values and additional details.

---

By constraining scripts to curated components and enforcing strict quotas, FireMUD delivers powerful automation tools while maintaining security and fair resource usage across all hosted games.

## Developer Tools

Several helper scripts streamline common tasks:

- `dev-tools/firemud-cli.sh` – command-line utility for starting and stopping the local stack.
- `dev-tools/docs/generate-erd.sh` – produces Entity Relationship Diagrams for each service.
- `dev-tools/docs/generate-grpc-docs.sh` – generates Markdown documentation from protobuf definitions.

These scripts complement the web-based editor and allow creators to automate routine actions.
