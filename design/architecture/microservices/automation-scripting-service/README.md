# Automation & Scripting Service

## Overview

The Automation & Scripting Service drives non-player character (NPC) behavior and world automation. It executes custom scripts and AI routines so worlds stay alive even when no players are online.

### Responsibilities

- Executes sandboxed scripts triggered by world and player events
- Provides a visual DSL for designers to build behaviors
- Stores persistent NPC memory and automation queues
- Integrates with Game Session and World Management services for real-time updates

For details on how scripts are authored, how standard and custom events are modeled, and how they execute safely, see [System Architecture: Scripting & Automation](../../system-architecture-scripting.md#supported-script-events) and the subsection on [Custom and Service-Specific Events](../../system-architecture-scripting.md#custom-and-service-specific-events).

An OpenAPI specification for the REST endpoints is available at `src/main/resources/openapi.yaml` in the service repository.

## Architecture / Design Notes

- Executes scripts in response to world or player events received via **gRPC callbacks** from the Game Session Service and other domain services. Standard lifecycle events (`onSpawn`, `onEnterRegion`, `onCommand`, etc.) are delivered as unary gRPC calls (conceptually via a `TriggerScriptEvent`–style API), while tick-derived scheduling signals (for example, “every N ticks”) are driven by a **gRPC streaming tick heartbeat** originating from the Game Session Service. See [System Architecture: Scripting & Automation](../../system-architecture-scripting.md#supported-script-events) and [Tick System and Runtime Design](../../system-architecture-ticks.md#tick-events--heartbeat-stream) for event and heartbeat details.
- Scripts run inside a sandboxed engine to prevent malicious behavior.
- Scripts are authored in a **component-based DSL** using a visual editor so
  designers can build behaviors without coding.
- AI computations are optimized for large worlds by evaluating scripts on a separate schedule and batching the resulting commands before handing them to the tick system.
- Script definitions are versioned and can be hot reloaded without downtime as
  described in [System Architecture: Scripting & Automation](../../system-architecture-scripting.md).
  See also the detailed sandbox and loop safety design in
  [Script Sandbox & Resource Limits](./sandbox-runtime-design.md).
- The service listens for a `NotifyScriptVersionUpdate` event and reloads the
  specified scripts in memory, validating compatibility before updating the
  runtime registry. See [Hot Reload & Failure Handling](#hot-reload--failure-handling)
  for how `activePatchVersion`, `pendingPatchVersion`, and `reloadState` are
  managed.
- Uploading or replacing scripts via the `UpdateScript` gRPC method is handled as a Saga workflow so that failures
    can be rolled back. The service uses the shared `SagaBuilder` and
    `SagaRunner` helpers to persist the script and emit `sagas.active` metrics
    with a `correlationId` for troubleshooting. See
    [Transaction Strategies](../../system-architecture-transactions.md).
- Each game's scripts live in tables keyed by `tenantId`, ensuring automation for
  one game cannot access another's data. Redis queues also include the tenant
  prefix; see [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

### Redis Role and Prefixes

- **Coordination Redis participation**
  - Uses automation-specific coordination prefixes owned by the Game Session Service’s Lua registry, such as `automation:tick:{tenantScriptTag}:lock`, `automation:tick:{tenantScriptTag}:queue`, and `automation:tick:{tenantScriptTag}:pending`, as described in [Redis Architecture](../../system-architecture-redis.md#key-format-examples) and [Redis Lua Patterns](../../system-architecture-redis-lua-patterns.md).
  - Automation scripts are registered as **single-hash-slot** Lua scripts that operate only on `automation:tick:{tenantScriptTag}:*` keys; they never mix `automation:*` and `tick:*` prefixes in a single script invocation to avoid `CROSSSLOT` issues in Redis Cluster.
- **Cache/Rate-Limit Redis usage**
  - Stores script quota counters and similar best-effort aggregates in **Cache/Rate-Limit Redis** using prefixes such as `script_quota:<tenantId>:<scriptId>` and `automation_queue:<tenantId>:*`, following the cache key naming and isolation rules in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md).
  - Treats these keys as transient operational data; PostgreSQL remains authoritative for script definitions and long-lived automation state. Quota and queue-oriented prefixes are treated as **best-effort TTL-only caches** unless explicitly documented as strongly validated caches with versioned payloads and stricter invalidation semantics.

Ownership and durability expectations for Automation & Scripting–related prefixes:

| Prefix | Redis role | Durability / reset tolerance |
| --- | --- | --- |
| `automation:tick:{tenantScriptTag}:lock` | Coordination | Reset-tolerant; locks are volatile coordination state and can be dropped and reacquired after a coordination reset. |
| `automation:tick:{tenantScriptTag}:queue` | Coordination | Reset-tolerant; in-flight automation tick queues are rebuilt from PostgreSQL and fresh events. Dropping these keys may cause some automation work to be skipped within the accepted tail-loss envelope. |
| `automation:tick:{tenantScriptTag}:pending` | Coordination | Reset-tolerant; staged automation effects are coordinated with the main tick system and are replayed or discarded according to the same idempotency rules as tick `pending` entries. |
| `automation_queue:<tenantId>:*` | Cache/Rate-Limit | Reset-tolerant, best-effort cache/queue of automation work items. Loss is acceptable; authoritative script triggers and audit trails remain in PostgreSQL. |
| `script_quota:<tenantId>:<scriptId>` | Cache/Rate-Limit | Reset-tolerant, best-effort quota counters. Dropping these keys temporarily resets budgets but does not affect script correctness or long-term state. |

Any new Automation & Scripting–specific prefixes must be added to this table and to the central Redis key catalogs, with a clear statement of which Redis role they use and whether they are reset-tolerant, reset-sensitive, or reset-forbidden.

- Quota and queue-related caches are treated as **best-effort TTL-only caches** unless this README states otherwise; any future strongly validated caches must document their version fields and invalidation strategy explicitly, in line with the Redis cache design.

#### Redis Cluster Slotting Rules for Automation

- Automation Lua scripts must never perform multi-key operations that span both `automation:*` and `tick:*` keys in a single invocation:
  - **Allowed examples**
    - A script that touches only `automation:tick:{tenantScriptTag}:queue` and `automation:tick:{tenantScriptTag}:pending` for a single `<tenantId>` + `<scriptId>`.
    - A script that touches only `automation_queue:<tenantId>:*` keys for a single tenant.
  - **Disallowed examples**
    - A script that reads or writes both `automation:tick:{tenantScriptTag}:*` and `tick:{tenantRegionTag}:*` keys in one `EVALSHA` call.
    - A script that mixes `automation:tick:{tenantScriptTag}:*` with `automation:tick:{otherTenantScriptTag}:*` keys.
- Automation work is staged under `automation:tick:*` and `automation_queue:*` and then handed off to Game Session via gRPC; only Game Session scripts mutate `tick:*` prefixes. This keeps automation scripts shard-local and avoids `CROSSSLOT` errors in Redis Cluster.
  - Any change to automation Redis usage or Lua scripts must follow the [Redis Change Checklist](../../system-architecture-redis.md#redis-change-checklist) and the automation slotting rules above.

## Key Features

- Scriptable quests and event triggers
- Persistent NPC memory and dynamic reactions
- Timers and delayed actions for asynchronous events
- Script evaluation occurs outside the tick system. Results are queued as commands that run during tick cycles, ensuring fair scheduling without blocking gameplay.
- Faction reputation influences NPC aggression states. NPCs may become **FLEEING** or **SURRENDERED** when low on health or morale, allowing players to resolve encounters non-lethally.
- Web UI for creating and testing scripts using a component-based DSL.
- Advanced AI modules support formations, squads, and complex behaviors.
- Procedural population hooks populate rooms with NPCs and loot based on biome and depth.
- `ScriptQuotaService` enforces fairness quotas and per-script resource limits.

### PvE Mechanics

Random encounters and environmental hazards are generated by the service's
`PveEncounterService`. Encounters are seeded so results can be reproduced during
testing. The service offers a diverse library of biome-specific events and
selects an appropriate encounter when the Game Session Service requests a PvE
interaction.

### Data Model

- `script` table holds the compiled component definitions and version metadata.
- `npc_memory` table stores persistent state for NPC behaviors.
- `automation_queue` keys in Redis buffer **script work items** (the commands and metadata produced by sandboxed DSL handlers) after a script runs and before they are staged into tick-compatible command queues. Each entry includes the originating `scriptEventId`, `scriptId`, version metadata, and the domain commands that should be materialized when the event is processed.
- Internal automation tick staging uses a dedicated namespace:
  - `automation:tick:{tenantScriptTag}:queue` – per-script queue of work items being staged into tick-compatible commands.
  - `automation:tick:{tenantScriptTag}:pending` – per-script pending list of work items currently being applied.
  - `automation:tick:{tenantScriptTag}:lock` – per-script lock ensuring only one automation tick for a `<tenantId>` + `<scriptId>` pair runs at a time.
  These keys are separate from the game tick keys (`tick:{tenantRegionTag}:...`) used by the Game Session Service and are only touched by the Automation & Scripting Service’s own Lua scripts. Script ticks never acquire `tick:{tenantRegionTag}:lock:<entityId>`; they stage commands for later execution by the Game Session Service’s tick loop.
- `automation_queue_enqueued_total` and `automation_queue_drained_total` metrics
  track Redis queue activity.
- The staging Lua script processes only a limited number of events each tick
  (controlled by `AUTOMATION_TICK_MAX_EVENTS`) to keep automation work
  predictable.
- Player reputation data is stored in the Social & Groups Service; see its
  [data model](../social-groups-service/README.md#data-model) for the
  `faction` and `faction_standing` tables.

### Script Lifecycle

- Scripts reside in the Automation & Scripting Service database and are versioned along with other game data as described in the design service versioning process.
- Events from the Game Session Service trigger script execution via gRPC. For each admitted trigger, the service executes the relevant sandboxed DSL handler **synchronously**, producing domain commands instead of mutating game state directly.
- The sandboxed engine limits CPU time and memory for each script to prevent runaway behavior.
- After a handler runs, the resulting commands and metadata are enqueued into `automation_queue` for the affected entity. `ScriptTickService` then stages, commits, and, when necessary, rolls back these queued work items in Redis. Automation ticks run independently of the main game tick loop and operate only on the `automation:tick:{tenantScriptTag}:*` namespace described above.
  Script ticks never hold the game tick locks (`tick:{tenantRegionTag}:lock:<entityId>`); they only batch and stage automation work before handing it to the Game Session Service, which applies commands under its own tick and locking model. See [Tick System and Runtime Design](../../system-architecture-ticks.md) for how queued commands are processed once they enter the per-entity tick queues.

### Hot Reload & Failure Handling

Script definitions are updated via `NotifyScriptVersionUpdate` from the Game Design Service. For each `<tenantId, scriptPatchVersion>`:

- The service tracks the currently executing version as `activePatchVersion` and treats the incoming one as `pendingPatchVersion`, with a simple `reloadState` (`IDLE`, `RELOADING`, `FAILED`) mirroring [Hot Reload & Resume Behavior](../../system-architecture-scripting.md#hot-reload--resume-behavior).
- On `NotifyScriptVersionUpdate`, leaders set `pendingPatchVersion` and `reloadState=RELOADING` while keeping `activePatchVersion` unchanged. Scheduling is paused for that tenant, but in-flight executions complete and triggers remain queued.
- Leaders coordinate with `ScriptVersionService` to load and validate the pending scripts. If the reload succeeds on the current leaders responsible for that tenant, they atomically switch `activePatchVersion` to the new value, clear `pendingPatchVersion`, set `reloadState=IDLE`, and resume scheduling.
- If reload or validation fails, the new patch never becomes active. The service keeps `activePatchVersion` on the prior patch, marks the pending patch as failed and `reloadState=FAILED`, discards any partially loaded state, and resumes scheduling using the last known good configuration. A failure result is reported back to the Game Design Service so the publish can be investigated or retried.
- Triggers pinned to a failed or unknown `scriptPatchVersion` are rejected explicitly. The service records an audit entry (for example, `skipped_version_unavailable`) and increments a drop metric such as `automation_script_triggers_dropped_total{reason=version_unavailable}` instead of silently falling back to the previous patch.

This behavior ensures that a script patch either becomes the new active version for that tenant or fails cleanly without affecting live automation behavior.

### gRPC APIs

- `UpdateScript` – uploads or replaces a script definition for later use.
- `GetScriptStatus` – queries whether a script is queued or running for a given
  entity.
- `NotifyScriptVersionUpdate` – informs the service that a new `script_patch_version`
  is available; the service reloads affected scripts and updates its registry.

## Faction & Reputation System

NPC behaviour references player reputation to decide when to become hostile,
flee, or surrender. These reputation scores are maintained by the Social &
Groups Service. See the
[Social & Groups Service](../social-groups-service/README.md#data-model) for the
`faction` and `faction_standing` tables that store reputation data.

The service includes an **NpcMoraleService** which adjusts an NPC's
`AggressionState` based on its current health, morale, and reputation. When these
values fall below configurable thresholds the NPC may become `FLEEING` or
`SURRENDERED`, allowing encounters to end without a kill.

## Dependencies

- **Internal:**
  - Game Session Service sends events that trigger scripts.
  - Game Logic Service for rule evaluation.
  - World Management Service receives world-state updates from scripts.
  - **External:** PostgreSQL for script storage and Redis for queuing automation tasks and enforcing quotas (using the Coordination and Cache/Rate-Limit roles above).

> See [**Gateway Architecture**](../../system-architecture-gateway.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../system-architecture-protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

This service follows the common scheme in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It uses the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection)
variables to access its databases.
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

Additional variables tune the scripting engine:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `SCRIPT_QUOTA_LIMIT` | Number of events a script may process per window | `50` |
| `SCRIPT_QUOTA_WINDOWSECONDS` | Length of the quota window in seconds | `60` |
| `AUTOMATION_TICK_DURATION_MS` | Duration of a processing tick in milliseconds | `1000` |
| `AUTOMATION_TICK_MAX_EVENTS` | Max events staged from the automation queue each tick | `50` |
| `AUTOMATION_TICK_BUDGET_MS` | Soft execution budget for a script tick in milliseconds | `100` |
| `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` | Number of days to retain script audit records before cleanup | `30` |
| `SCRIPT_EVENT_AUDIT_MAX_ROWS` | Maximum number of rows to keep in the script audit store before truncation | `1000000` |

## Proto Files

API definitions are located in
[../../../../protos/automation-scripting/v1](../../../../protos/automation-scripting/v1).
Run `./gradlew generateProto` after modifying these schemas to update the gRPC
stubs.

## Related Documentation

- [System Architecture: Scripting & Automation](../../system-architecture-scripting.md)
- [Tick System and Runtime Design](../../system-architecture-ticks.md)
- [Redis Architecture](../../system-architecture-redis.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Add Automation & Scripting](../../user-journeys.md#4-add-automation--scripting)
- [System Architecture Overview](../../system-architecture-overview.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Database Migrations](../../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)

## Additional Details

### Configuration

PostgreSQL and Redis connections are configured via the common `DatabaseAutoConfiguration` and `RedisProperties` classes. Refer to [Deployment Environments](../../infrastructure/deployment-environments.md) for default values. Local development typically uses the settings from `.env.sample`.

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.

```bash
curl http://localhost:8080/ping
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`automation_scripting_service.proto`](../../../../protos/automation-scripting/v1/automation_scripting_service.proto).

```bash
grpcurl -plaintext localhost:6565 automation_scripting.v1.AutomationScriptingService/Ping
```

Expected response:

```json
{
  "message": "pong"
}
```

### Fairness Quotas

`ScriptQuotaService` limits how many times a script may execute within a
configurable window. Counters are stored in Redis using keys of the form
`script_quota:<tenantId>:<scriptId>`. When the quota is exceeded the event is
ignored and `script_quota_denied_total` is incremented. Enforcement metrics are
exported via the standard `sagas.active` gauge.

Key Automation & Scripting–specific metrics include:

- `automation_script_triggers_total`, `automation_script_skips_total`, and `automation_script_triggers_dropped_total` for scheduler activity and drops.
- `automation_script_queue_delay_seconds` and `automation_script_leadership_changes_total` for queue latency and leader stability.
- `automation_script_tenant_budget_seconds{tenantId, tier}` for per-tenant automation budgets.
- `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total` for quota enforcement and tick integration.
- `automation_script_sandbox_failures_total{reason=...}`, `automation_script_errors_total{tenantId, reason=...}`, and `automation_script_runtime_seconds` for sandbox and runtime health.

See [Logging & Monitoring](../../system-architecture-logging-monitoring.md) for how these metrics are scraped, visualized, and alerted on.

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
