# Automation & Scripting Service Runtime and Data

This document defines the Automation & Scripting Service runtime model, persistence boundaries, reload semantics, Redis usage, and script work-item lifecycle.

## Architecture and Design Notes

- Executes scripts in response to world or player events received via gRPC callbacks from Game Session and other domain services. Standard lifecycle events (`onSpawn`, `onEnterRegion`, `onCommand`, and similar) are delivered as unary gRPC calls via `TriggerScriptEvent`, while tick-derived scheduling signals are driven by a gRPC streaming tick heartbeat originating from Game Session.
- Scripts run inside a sandboxed engine to prevent malicious behavior.
- Scripts are authored in a component-based DSL using a visual editor so designers can build behaviors without coding.
- AI computations are optimized for large worlds by evaluating scripts on a separate schedule and batching the resulting commands before handing them to the tick system.
- Script definitions are versioned and can be hot reloaded without downtime as described in [System Architecture: Scripting & Automation](../../system-architecture-scripting.md). Detailed sandbox and loop-safety rules live in [Script Sandbox & Resource Limits](./sandbox-runtime-design.md).
- The service listens for `NotifyScriptVersionUpdate` and reloads the specified patch into tenant-readiness state, validating compatibility and running `onLoad` before any running instance is allowed to switch to it. Tenant readiness is single-pending per tenant: if a newer publish arrives before the current pending patch reaches a terminal state, the older pending patch becomes `SUPERSEDED` and cannot later advance to `READY`.
- Runtime execution is instance-aware even when patch readiness is tenant-scoped: a tenant-level `READY` patch is only eligible for pinning, while admission, timer scheduling, rollback pause, and plugin activation are evaluated per `<tenantId, gameInstanceId>`.
- Patch reload now also persists a first durable `script_schedule_definitions` catalog for scheduler-owned handlers such as `onInterval` / `onTimerExpire`, keyed by `scheduleDefinitionId` with cadence/unit metadata and normalized schedule hash. That is patch-scoped schedule metadata only; it is not yet the later instance/region timer-runtime table.
- Authoritative gameplay reads within one handler-scoped run must share the same runtime-issued `readSnapshotToken` captured at admission. In practice, a `TriggerScriptEvent`-style ingress may carry an opaque `readSnapshotToken` equivalent to `<tenantId=T1, gameInstanceId=G7, regionId=R2, regionEpoch=14, tickId=981223>`, and every downstream gameplay-affecting read made during that run must forward the same token rather than silently reading a fresher tick midway through evaluation.
- For gameplay-originated events, ingress and durable runtime state now also persist the resolved `playableStateScope` alongside `tenantId`, `gameInstanceId`, and `entityId`, so shared-state and isolated-state realms remain distinct through work-item identity, timer/schedule materialization, replay checks, dead letters, and operator reads even when they share the same tenant.
- Each game’s scripts live in tables keyed by `tenantId`, ensuring automation for one game cannot access another’s data. Derived Redis coordination and index keys must preserve runtime instance isolation as well, not just tenant isolation.
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Workflow Participation

The Automation & Scripting Service uses the shared workflow substrates as follows:

- Script patch consumption: when a script-only patch version is published, Game Design drives the durable publish workflow and this service participates as a consumer via `NotifyScriptVersionUpdate`, ingesting the patch for tenant readiness without coordinating its own publish-workflow steps. Running instances reload only after a later pin change to that tenant-`READY` patch.
- Bootstrap and dev-only upload path: if `UpdateScript` exists in an environment, it must be explicitly marked non-production and must not bypass patch lifecycle gates, readiness checks, or control-plane events.

Tick-driven automation and event handling never use synchronous sagas or Temporal; they follow the Redis and tick contracts described in [System Architecture: Scripting & Automation](../../system-architecture-scripting.md) and [Tick System and Runtime Design](../../system-architecture-ticks.md).

## Runtime Data Model

- `script` holds immutable compiled component definitions and exact version metadata. An admitted trigger resolves the graph identified by its `scriptPatchVersion`; cache misses or worker restarts must not substitute another version.
- `npc_memory` stores persistent state for NPC behaviors.
- Admitted script work items are persisted durably in a PostgreSQL-backed outbox keyed by Trigger Identity plus sequencing fields as needed, including the resolved binding `priorityTag` used for live tenant-budget reservation. For gameplay/runtime triggers, that Trigger Identity includes the resolved `playableStateScope` rather than inferring scope later from `gameInstanceId` conventions or payload internals.
- `automation:queue:{tenantInstanceTag}:<entityId>` keys in Redis buffer work-item indexes or pointers after a script runs and its work item is persisted durably. Each entry includes enough identity to locate the durable work item and must not be treated as an authoritative log of commands.
- Queue activity must be surfaced through the canonical observability contract metric families rather than through an unsupported parallel metric surface.
- Player reputation data is stored in the Social & Groups Service; this service reads those scores to drive NPC morale and aggression behavior.

## Script Lifecycle

- Scripts reside in the service database and are versioned along with other game data as described in the design-service versioning process.
- Events from Game Session and other domain services trigger script execution via gRPC. For each admitted trigger, the service executes the relevant sandboxed DSL handler synchronously, producing domain commands instead of mutating game state directly.
- The sandboxed engine limits CPU time and memory for each script to prevent runaway behavior.
- After a handler runs, the resulting script work item is persisted durably in the outbox and then indexed into `automation:queue:{tenantInstanceTag}:<entityId>` for the affected entity.
- A scheduled durable executor claims persisted `PENDING_EVALUATION` work items, evaluates the current command-emission format, and hands off commands to Game Session for tick enqueue. `automation:queue:*` remains a rebuildable pointer projection rather than the driver of authoritative execution state.
- Before persistence, explicit output budgets must cap the number of commands and serialized work-item size emitted by a single run so one admitted trigger cannot create an unbounded backlog.
- Script ticks never hold game tick locks (`tick:{tenantRegionTag}:lock:<entityId>`); they only batch and stage automation work before handing it to Game Session, which applies commands under its own tick and locking model.

## Hot Reload and Failure Handling

`NotifyScriptVersionUpdate` starts tenant-scoped readiness ingestion for `<tenantId, scriptPatchVersion>`. Instance-scoped reload happens later only when Game Session pins an already-`READY` patch for a specific runtime scope:

- `NotifyScriptVersionUpdate` is a tenant-readiness ingestion signal, not an instance activation signal. It causes the service to ingest compiled graphs and bindings for `<tenantId, scriptPatchVersion>`, then run tenant-scoped readiness checks and `onLoad` before any running instance is allowed to pin that patch.
- Tenant readiness uses the patch lifecycle `PENDING_VALIDATION -> ONLOAD_RUNNING -> READY/FAILED`, plus terminal `SUPERSEDED` when a newer publish displaces an older pending patch for the same tenant.
- Tenant `onLoad` has no game-instance, region, epoch, entity, or gameplay-effect context. It may validate graphs and prepare ephemeral or recomputable candidate-local state, but it must not emit gameplay commands or mutate instance, entity, or shared gameplay state.
- Before pin commit, Game Session may request candidate preparation or preload for the exact tenant-`READY` artifact. This state is non-authoritative and cannot admit candidate gameplay work; preparation failure leaves the current Game Session pin and epoch unchanged.
- Runtime scopes track only the Game Session pin projection `(observedPinnedScriptPatchVersion, observedScriptPinEpoch)` plus instance-scoped workflow state such as `reloadState` and rollback pause. The projection and any loaded cache are not an Automation-owned active-version authority.
- After Game Session commits a pin, leaders set `reloadState=RELOADING` for the observed exact version and epoch and pause new scheduling for that runtime scope while reconciling version-scoped timers and other derived state. Instance reload does not rerun `onLoad`.
- In-flight executions may finish evaluation against the immutable graph and pin epoch captured at admission, but any later persistence, handoff, or gameplay effect must pass the current version-and-epoch fence. New work under a displaced epoch is rejected.
- On successful reconciliation, leaders clear `reloadState=IDLE` and admit future work only with the committed Game Session version and epoch. This is convergence to the authoritative pin, not an Automation-owned active-version switch.
- If tenant readiness or candidate preparation fails before commit, the patch remains ineligible and the existing instance pin is unaffected. If exact-version loading or reconciliation fails after Game Session commits the pin, the runtime scope remains fail-closed with `reloadState=FAILED`; it does not resume against a prior locally loaded graph. Recovery requires repair or an explicit Game Session repin to a still-`READY`, base-compatible patch, which creates a new pin epoch.
- Triggers carrying an unknown, failed, mismatched, or stale `(scriptPatchVersion, scriptPinEpoch)` are rejected explicitly with audit visibility and bounded failure reasons rather than falling back or substituting another graph.

This behavior preserves one active-selection authority while allowing Automation to retain readiness, preload, backpressure, and convergence workflow state.

## Redis Roles and Prefixes

- **Coordination Redis participation**
  - Uses Automation-owned cache/rate-limit prefixes such as `automation:queue:{tenantInstanceTag}:*`, `automation:quota:<tenantId>:<scriptId>`, `automation:tenant-budget:<tenantId>:tier:<tier>`, and dry-run capacity keys. Game Session remains the only owner of `tick:{tenantRegionTag}:*` gameplay coordination prefixes.
- **Cache/Rate-Limit Redis usage**
  - Stores script quota counters and similar best-effort aggregates in Cache/Rate-Limit Redis using prefixes such as `automation:quota:<tenantId>:<scriptId>`, `automation:tenant-budget:<tenantId>:tier:<tier>`, `automation:test:capacity:<tenantId>:*`, `automation:test:capacity:cluster*`, and `automation:queue:{tenantInstanceTag}:*`.
  - Treats these keys as transient operational data; PostgreSQL remains authoritative for script definitions and long-lived automation state.
  - Quota and queue-oriented prefixes are best-effort TTL-only caches unless explicitly documented as strongly validated caches with versioned payloads and stricter invalidation semantics.

Ownership and durability expectations for Automation & Scripting prefixes:

| Prefix | Redis role | Durability / reset tolerance |
| --- | --- | --- |
| `automation:queue:{tenantInstanceTag}:*` | Cache/Rate-Limit | Reset-tolerant, best-effort cache or queue of automation work-item indexes. Loss is acceptable because admitted work items are persisted durably in PostgreSQL and can be re-driven. |
| `automation:quota:<tenantId>:<scriptId>` | Cache/Rate-Limit | Reset-tolerant, best-effort quota counters. Dropping these keys temporarily resets budgets but does not affect script correctness or long-term state. |
| `automation:tenant-budget:<tenantId>:tier:<tier>` | Cache/Rate-Limit | Reset-tolerant, best-effort tenant budget counters for live automation execution reservations. Dropping these keys temporarily relaxes tenant fairness but does not affect durable work truth. |
| `automation:test:capacity:<tenantId>:tenant`, `automation:test:capacity:<tenantId>:lease:<workItemId>`, `automation:test:capacity:cluster*` | Cache/Rate-Limit | Reset-tolerant tenant-local and cluster-wide dry-run capacity counters and leases. Leases have bounded TTLs and are released on normal completion; dropping them temporarily relaxes test-capacity fairness without affecting live work. |

Any new Automation & Scripting-specific prefixes must be added here and to the central Redis key catalogs, with a clear statement of Redis role and reset behavior.

Quota and queue-related caches are best-effort TTL-only caches unless this service doc set states otherwise. `automation:queue:*` must never be the sole source of truth for whether work has been enqueued or processed; exactly-once or at-least-once semantics are provided by durable trigger tables and idempotent domain logic, not Redis queue contents.

## Durable Script Work-Item Outbox

To avoid “DSL evaluated successfully but effects were silently dropped”, the service must persist admitted script work items durably before they are considered successful:

- Admitted triggers produce script work items which are written to a PostgreSQL-backed outbox table keyed by Trigger Identity plus sequencing fields as needed.
- Materialized `script_schedule_instances`, timer-generated work items, handoff events, and dead-letter rows preserve the same gameplay `playableStateScope` so scheduler-driven follow-up work stays in the same shared-versus-isolated state namespace as the original admitted event.
- `automation:queue:*` keys are derived coordination indexes that accelerate draining and batching, not the authoritative record of pending work.
- On restart, failover, or Cache/Rate-Limit Redis resets, the service can rebuild `automation:queue:*` indexes by scanning the outbox for pending items and re-projecting them.

This enables stage-aware outcomes in `script_event_audit`: the system can distinguish “DSL evaluation succeeded” from “handoff or enqueue succeeded”, and can re-drive delivery where appropriate without re-executing the DSL body for the same trigger.

## Redis Cluster Slotting Rules

- Automation Redis usage must never perform multi-key operations that span both `automation:*` and `tick:*` keys in a single invocation.
- Allowed examples:
  - A Redis operation that touches only `automation:queue:{tenantInstanceTag}:*` keys for one runtime scope.
  - A Redis operation that touches only Automation-owned quota or dry-run-capacity keys for one documented scope.
- Disallowed examples:
  - Any script or transaction that reads or writes both `automation:*` and `tick:{tenantRegionTag}:*` keys in one atomic Redis call.
  - Any Automation-owned key family that attempts to mirror or stage authoritative gameplay queue state outside Game Session.
- Automation work is projected under `automation:queue:*` and then handed off to Game Session via gRPC; only Game Session scripts mutate `tick:*` prefixes.

Any change to automation Redis usage or Lua scripts must follow the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) and the slotting rules above.

## PvE, Faction, and Reputation Behavior

Random encounters and environmental hazards are generated by `PveEncounterService`. Encounters are seeded so results can be reproduced during testing. The service includes an `NpcMoraleService` which adjusts an NPC’s `AggressionState` based on current health, morale, and reputation. When those values fall below configurable thresholds the NPC may become `FLEEING` or `SURRENDERED`, allowing encounters to end without a kill.
