# Automation & Scripting Service

## Overview

The Automation & Scripting Service drives non-player character (NPC) behavior and world automation. It executes custom scripts and AI routines so worlds stay alive even when no players are online.

### Responsibilities

- Executes sandboxed scripts triggered by world and player events
- Provides backend APIs and a sandboxed engine for the visual DSL editor in the Game Design Service
- Stores persistent NPC memory and automation queues
- Integrates with Game Session and World Management services for real-time updates

For details on how scripts are authored, how standard and custom events are modeled, and how they execute safely, see:

- [System Architecture: Scripting & Automation](../../system-architecture-scripting.md#tldr-flow) for the high-level flow and service interactions.
- [Scripting DSL Reference & Event Lifecycle](../../system-architecture-scripting-dsl-reference-and-lifecycle.md#supported-script-events) for event types and lifecycle.
- [Custom and Service-Specific Events](../../system-architecture-scripting-dsl-reference-and-lifecycle.md#custom-and-service-specific-events) for how non-standard events are versioned and ordered.

An OpenAPI specification for the REST endpoints is available at `src/main/resources/openapi.yaml` in the service repository.

### Audience Guide

- **For operators and SREs**
  - Focus on **Environment Variables**, **Fairness Quotas**, and the metrics list under **Additional Details**.
  - Pair this README with:
    - `design/architecture/system-architecture-scripting-quotas-and-operations.md` for quotas, budgets, and rollback flows.
    - `design/architecture/system-architecture-logging-monitoring.md` for metrics and alerting.
    - `design/architecture/system-architecture-redis.md` for Redis behavior.
- **For backend developers**
  - Focus on **Architecture / Design Notes**, **Saga Participation**, **Redis Role and Prefixes**, and **REST & gRPC Endpoints**.
  - Pair this README with:
    - `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md` for scripting semantics and contracts.
    - `design/architecture/system-architecture-transactions.md` for idempotency and Saga patterns.

## Architecture / Design Notes

- Executes scripts in response to world or player events received via **gRPC callbacks** from the Game Session Service and other domain services. Standard lifecycle events (`onSpawn`, `onEnterRegion`, `onCommand`, etc.) are delivered as unary gRPC calls via `TriggerScriptEvent`, while tick-derived scheduling signals (for example, “every N ticks”) are driven by a **gRPC streaming tick heartbeat** originating from the Game Session Service. See [Supported Script Events](../../system-architecture-scripting-dsl-reference-and-lifecycle.md#supported-script-events) and [Tick System and Runtime Design](../../system-architecture-ticks.md#tick-events--heartbeat-stream) for event and heartbeat details.
- Scripts run inside a sandboxed engine to prevent malicious behavior.
- Scripts are authored in a **component-based DSL** using a visual editor so
  designers can build behaviors without coding.
- AI computations are optimized for large worlds by evaluating scripts on a separate schedule and batching the resulting commands before handing them to the tick system.
- Script definitions are versioned and can be hot reloaded without downtime as
  described in [System Architecture: Scripting & Automation](../../system-architecture-scripting.md).
  See also the detailed sandbox and loop safety design in
  [Script Sandbox & Resource Limits](./sandbox-runtime-design.md).
- The service listens for a `NotifyScriptVersionUpdate` event and reloads the
  specified patch into tenant-readiness state, validating compatibility and
  running `onLoad` before any running instance is allowed to switch to it.
  Tenant readiness is single-pending per tenant: if a newer publish arrives
  before the current pending patch reaches a terminal state, the older pending
  patch becomes `SUPERSEDED` and cannot later advance to `READY`. See
  [Hot Reload & Failure Handling](#hot-reload--failure-handling) for how
  tenant readiness and instance-scoped `activePatchVersion`,
  `pendingPatchVersion`, and `reloadState` are managed.
  Example: if `P21` is in `ONLOAD_RUNNING` and `P22` is then published for the
  same tenant, `P21` becomes `SUPERSEDED`, any not-yet-started `P21` `onLoad`
  work is canceled, and only `P22` remains eligible to become `READY`.
- Runtime execution is instance-aware even when patch readiness is tenant-scoped: a tenant-level `READY` patch is only eligible for pinning, while admission, timer scheduling, rollback pause, and plugin activation are evaluated per `<tenantId, gameInstanceId>`.
  Runtime patch state therefore cannot rely on one mutable tenant-wide `activePatchVersion`; implementations must track `activePatchVersion`, `pendingPatchVersion`, and `reloadState` per instance or per explicit pin cohort.
- Authoritative gameplay reads within one handler-scoped run must share the same runtime-issued snapshot token captured at admission. In practice, a `TriggerScriptEvent`-style ingress may carry an opaque `readSnapshotToken` equivalent to `<tenantId=T1, gameInstanceId=G7, regionId=R2, regionEpoch=14, tickId=981223>`, and every downstream gameplay-affecting read made during that run must forward the same token rather than silently reading a fresher tick midway through evaluation.
- Direct script upload/update APIs (for example `UpdateScript`) are limited to bootstrap/dev tooling and must not be used as a production runtime publish path. Production patch rollout uses the Game Design-driven publish Saga and `NotifyScriptVersionUpdate` lifecycle (`PENDING_VALIDATION` -> `ONLOAD_RUNNING` -> `READY`/`FAILED`, with terminal `SUPERSEDED` for an older pending patch displaced by a newer publish) so all runtime gating, audit, and rollback contracts are preserved.
- Each game's scripts live in tables keyed by `tenantId`, ensuring automation for
  one game cannot access another's data. Derived Redis coordination/index keys must preserve runtime instance isolation as well, not just tenant isolation; see [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

### Saga Participation

The Automation & Scripting Service uses the shared Saga library as follows:

- **Script patch consumption** – when a script-only patch version is published,
  the Game Design Service drives the Saga; this service participates as a
  consumer via `NotifyScriptVersionUpdate`, ingesting the patch for tenant
  readiness without coordinating its own Saga steps. Running instances reload
  only after a later pin change to that tenant-`READY` patch.
- **Bootstrap/dev-only upload path** – if `UpdateScript` exists in an environment, it must be explicitly marked non-production and must not bypass patch lifecycle gates, readiness checks, or control-plane events.

Tick-driven automation and event handling never use Sagas; they follow the
Redis and tick contracts described in
[System Architecture: Scripting & Automation](../../system-architecture-scripting.md)
and [Tick System and Runtime Design](../../system-architecture-ticks.md).

### Digest Input Manifest Requirements

This service is a required digest participant for full publishes and script-patch publishes. It must expose `GetDraftDesignDigest` with a typed scope selector (`oneof {versionId, scriptPatchVersion}`) and maintain a service-local digest input manifest with:

- Included objects (for example version/patch-scoped script graphs, bindings, and publish-critical metadata that affect runtime execution for the scoped publish type).
- Excluded objects (for example runtime queues, audit/event logs, quota counters, and other non-launchability operational state).
- Canonicalization rules (stable ordering, normalized serialization, and deterministic default/null handling before hashing).
- `digestSchemaVersion` bump criteria (any include/exclude/canonicalization change requires an explicit schema bump and digest migration/re-record workflow).

Publish gating must fail closed when this service cannot attest a digest under its documented manifest for the reported `digestSchemaVersion`.

### Redis Role and Prefixes

- **Coordination Redis participation**
  - Uses automation-specific coordination prefixes owned by the Game Session Service’s Lua registry, such as `automation:tick:{tenantInstanceScriptTag}:lock`, `automation:tick:{tenantInstanceScriptTag}:queue`, and `automation:tick:{tenantInstanceScriptTag}:pending`, as described in [Redis Architecture](../../system-architecture-redis.md#key-format-examples) and [Redis Lua Patterns](../../system-architecture-redis-lua-patterns.md).
  - Automation scripts are registered as **single-hash-slot** Lua scripts that operate only on `automation:tick:{tenantInstanceScriptTag}:*` keys; they never mix `automation:*` and `tick:*` prefixes in a single script invocation to avoid `CROSSSLOT` issues in Redis Cluster.
- **Cache/Rate-Limit Redis usage**
  - Stores script quota counters and similar best-effort aggregates in **Cache/Rate-Limit Redis** using prefixes such as `automation:quota:<tenantId>:<scriptId>` and `automation:queue:{tenantInstanceTag}:*`, following the cache key naming and isolation rules in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md).
  - Treats these keys as transient operational data; PostgreSQL remains authoritative for script definitions and long-lived automation state. Quota and queue-oriented prefixes are treated as **best-effort TTL-only caches** unless explicitly documented as strongly validated caches with versioned payloads and stricter invalidation semantics.

The central reset policies and cache behavior for these prefixes are defined in:

- [Redis Reset & Recovery – Reset Policy Matrix](../../system-architecture-redis-reset-and-recovery.md#reset-policy-matrix-prefix-summary)
- [Redis Cache & Rate Limiting – Cache/Rate-Limit Key Catalog](../../system-architecture-redis-cache.md#cache-rate-limit-key-catalog)

Ownership and durability expectations for Automation & Scripting–related prefixes:

| Prefix | Redis role | Durability / reset tolerance |
| --- | --- | --- |
| `automation:tick:{tenantInstanceScriptTag}:lock` | Coordination | Reset-tolerant; locks are volatile coordination state and can be dropped and reacquired after a coordination reset. |
| `automation:tick:{tenantInstanceScriptTag}:queue` | Coordination | Reset-tolerant; in-flight automation tick queues are rebuilt from PostgreSQL and fresh events. Dropping these keys may cause some automation work to be skipped within the accepted tail-loss envelope. |
| `automation:tick:{tenantInstanceScriptTag}:pending` | Coordination | Reset-tolerant; staged automation effects are coordinated with the main tick system and are replayed or discarded according to the same idempotency rules as tick `pending` entries. |
| `automation:queue:{tenantInstanceTag}:*` | Cache/Rate-Limit | Reset-tolerant, best-effort cache/queue of automation work item *indexes*. Loss is acceptable because admitted work items are persisted durably in PostgreSQL (outbox) and can be re-driven; this prefix is not an authoritative log. |
| `automation:quota:<tenantId>:<scriptId>` | Cache/Rate-Limit | Reset-tolerant, best-effort quota counters. Dropping these keys temporarily resets budgets but does not affect script correctness or long-term state. |

Any new Automation & Scripting–specific prefixes must be added to this table and to the central Redis key catalogs, with a clear statement of which Redis role they use and whether they are reset-tolerant, reset-sensitive, or reset-forbidden.

> If you change Redis usage for this service (new prefixes, Lua scripts, or cache adoption), you must read and apply:
>
> - [Redis Architecture](../../system-architecture-redis.md)
> - [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
> - [FireMUD Redis Lua Patterns](../../system-architecture-redis-lua-patterns.md)
> - [Redis Operations & Migrations](../../system-architecture-redis-operations.md)

- Quota and queue-related caches are treated as **best-effort TTL-only caches** unless this README states otherwise; any future strongly validated caches must document their version fields and invalidation strategy explicitly, in line with the Redis cache design.
  In particular, `automation:queue:*` must never be the sole source of truth for whether work has been enqueued or processed; exactly-once or at-least-once semantics are provided by durable trigger tables and idempotent domain logic, not by Redis queue contents.
  Cache metrics for `automation:queue:*` / `automation:quota:*` should either follow the `cache.automation_queue_*` patterns in `system-architecture-redis-cache.md` or be clearly mapped to the Automation & Scripting metrics already defined in this README (for example `automation_script_queue_delay_seconds`, `automation_tick_events_enqueued_total`, and `script_quota_*` counters) so queue and quota behavior are observable.

#### Durable Script Work Item Outbox (Required)

To avoid “DSL evaluated successfully but effects were silently dropped”, the Automation & Scripting Service must persist admitted script work items durably before they are considered successful:

- Admitted triggers produce **script work items** which are written to a PostgreSQL-backed outbox table keyed by Trigger Identity (plus sequencing fields as needed).
- `automation:queue:*` keys are derived coordination indexes that accelerate draining/batching, not the authoritative record of pending work.
- On restart, failover, or Cache/Rate-Limit Redis resets, the service can rebuild `automation:queue:*` indexes by scanning the outbox for pending items and re-projecting them.

This enables stage-aware outcomes in `script_event_audit`: the system can distinguish “DSL evaluation succeeded” from “handoff/enqueue succeeded”, and can re-drive delivery where appropriate without re-executing the DSL body for the same trigger.

Stage names and required audit fields (`finalStage`, `finalOutcome`, `finalReason`, optional per-stage breakdown) are defined in `design/architecture/system-architecture-scripting-observability-contract.md`.

#### Redis Cluster Slotting Rules for Automation

- Automation Lua scripts must never perform multi-key operations that span both `automation:*` and `tick:*` keys in a single invocation:
  - **Allowed examples**
    - A script that touches only `automation:tick:{tenantInstanceScriptTag}:queue` and `automation:tick:{tenantInstanceScriptTag}:pending` for a single `<tenantId>` + `<gameInstanceId>` + `<scriptId>`.
    - A script that touches only `automation:queue:{tenantInstanceTag}:*` keys for a single runtime scope.
  - **Disallowed examples**
    - A script that reads or writes both `automation:tick:{tenantInstanceScriptTag}:*` and `tick:{tenantRegionTag}:*` keys in one `EVALSHA` call.
    - A script that mixes `automation:tick:{tenantInstanceScriptTag}:*` with `automation:tick:{otherTenantInstanceScriptTag}:*` keys.
- Automation work is staged under `automation:tick:*` and `automation:queue:*` and then handed off to Game Session via gRPC; only Game Session scripts mutate `tick:*` prefixes. This keeps automation scripts shard-local and avoids `CROSSSLOT` errors in Redis Cluster.
- Any change to automation Redis usage or Lua scripts must follow the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) and the automation slotting rules above.

## Key Features

- Scriptable quests and event triggers
- Persistent NPC memory and dynamic reactions
- Timers and delayed actions for asynchronous events
- Script evaluation occurs outside the tick system. Results are queued as commands that run during tick cycles, ensuring fair scheduling without blocking gameplay.
- Faction reputation influences NPC aggression states. NPCs may become **FLEEING** or **SURRENDERED** when low on health or morale, allowing players to resolve encounters non-lethally.
- Web UI for creating and testing scripts using a component-based DSL.
- Advanced AI modules support formations, squads, and complex behaviors.
- Procedural population hooks populate rooms with NPCs and loot based on biome and depth by emitting idempotent, tick-driven commands; scripts do not persist world topology and do not directly mutate World Management instance rows.
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
- `automation:queue` keys in Redis buffer **work-item indexes/pointers** after a script runs and its work item is persisted durably (outbox). Each entry includes enough identity to locate the durable work item (for example an outbox ID) and must not be treated as an authoritative log of commands. These indexes must be instance-aware (for example `automation:queue:{tenantInstanceTag}:<entityId>`), not just tenant-aware. The normative outbox and pointer contract is defined in `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md#work-item-outbox-contract-normative`.
- Internal automation tick staging uses a dedicated namespace:
  - `automation:tick:{tenantInstanceScriptTag}:queue` – per-instance, per-script queue of work items being staged into tick-compatible commands.
  - `automation:tick:{tenantInstanceScriptTag}:pending` – per-instance, per-script pending list of work items currently being applied.
  - `automation:tick:{tenantInstanceScriptTag}:lock` – per-instance, per-script lock ensuring only one automation tick for a `<tenantId>` + `<gameInstanceId>` + `<scriptId>` scope runs at a time.
  These keys are separate from the game tick keys (`tick:{tenantRegionTag}:...`) used by the Game Session Service and are only touched by the Automation & Scripting Service’s own Lua scripts. Script ticks never acquire `tick:{tenantRegionTag}:lock:<entityId>`; they stage commands for later execution by the Game Session Service’s tick loop.
- Queue activity must be surfaced through the canonical observability contract metric families (for example `automation_tick_events_enqueued_total`, `automation_script_queue_delay_seconds`, `automation_queue_orphaned_entries_total`) rather than introducing a parallel unsupported metric surface.
- The staging Lua script processes only a limited number of events each tick
  (controlled by `AUTOMATION_TICK_MAX_EVENTS`) to keep automation work
  predictable.
- Player reputation data is stored in the Social & Groups Service; see its
  [data model](../social-groups-service/README.md#data-model) for the
  `faction` and `faction_standing` tables.

### Script Lifecycle

- Scripts reside in the Automation & Scripting Service database and are versioned along with other game data as described in the design service versioning process.
- Events from the Game Session Service and other domain services trigger script execution via gRPC. For each admitted trigger, the service executes the relevant sandboxed DSL handler **synchronously**, producing domain commands instead of mutating game state directly.
- The sandboxed engine limits CPU time and memory for each script to prevent runaway behavior.
- After a handler runs, the resulting script work item is persisted durably (outbox) and then indexed into `automation:queue:{tenantInstanceTag}:<entityId>` for the affected entity. `ScriptTickService` drains these indexes, stages under `automation:tick:{tenantInstanceScriptTag}:*`, and hands off commands to Game Session for tick enqueue.
  Before persistence, explicit output budgets must cap the number of commands and serialized work-item size emitted by a single run so one admitted trigger cannot create an unbounded backlog.
  Script ticks never hold the game tick locks (`tick:{tenantRegionTag}:lock:<entityId>`); they only batch and stage automation work before handing it to the Game Session Service, which applies commands under its own tick and locking model. See [Tick System and Runtime Design](../../system-architecture-ticks.md) for how queued commands are processed once they enter the per-entity tick queues.

### Hot Reload & Failure Handling

Script definitions are updated via `NotifyScriptVersionUpdate` from the Game Design Service. This signal starts tenant-scoped readiness ingestion for `<tenantId, scriptPatchVersion>`; instance-scoped reload happens later only when Game Session pins an already-`READY` patch for a specific runtime scope:

- `NotifyScriptVersionUpdate` is a **tenant-readiness ingestion** signal, not an instance activation signal. It causes Automation & Scripting to ingest compiled graphs and bindings for `<tenantId, scriptPatchVersion>`, then run tenant-scoped readiness checks and `onLoad` before any running instance is allowed to pin that patch.
- Tenant readiness is tracked separately from runtime-scope pin observation:
  - Tenant readiness uses the patch lifecycle `PENDING_VALIDATION -> ONLOAD_RUNNING -> READY/FAILED`, plus terminal `SUPERSEDED` when a newer publish displaces an older pending patch for the same tenant.
  - Runtime scopes track only the patch observed as pinned for `<tenantId, gameInstanceId>` plus instance-scoped admission state such as `reloadState` and rollback pause.
- When Game Session later pins a tenant-`READY` patch for a specific `<tenantId, gameInstanceId>`, leaders set runtime-scope `pendingPatchVersion` and `reloadState=RELOADING` while keeping the previously observed `activePatchVersion` unchanged. Scheduling is paused for that runtime scope: in-flight executions complete under the existing patch, but **new triggers are not admitted** for the affected `<tenantId, gameInstanceId>` while reload is in progress.
- Instance reload does **not** rerun `onLoad`. Instead, leaders load the already-validated tenant-`READY` definitions for the newly observed pin, reconcile version-scoped timers and other derived scheduler state, then atomically switch the runtime scope to the new observed patch and clear `reloadState=IDLE`.
- If tenant readiness fails, the patch remains `FAILED` for that tenant and never becomes eligible for pinning. If instance reload of an already-`READY` patch fails, the service keeps `activePatchVersion` on the prior observed patch, marks the runtime scope `reloadState=FAILED`, discards partially loaded derived state, and resumes scheduling using the last known good configuration. The failure is reported back through the normal patch-status and rollout-status surfaces.
- Triggers pinned to a failed or unknown `scriptPatchVersion` are rejected explicitly. The service records an audit entry with `finalStage=ADMISSION` and a non-success `finalOutcome` (for example, `finalOutcome=version_unavailable` or `finalOutcome=skipped_reloading`) and increments a drop metric such as `automation_script_triggers_dropped_total{reason=\"version_unavailable\"}` or `automation_script_triggers_dropped_total{reason=\"reloading\"}` instead of silently falling back to the previous patch or allowing unbounded queuing during reload.

This behavior ensures that a script patch either becomes the new active version for the targeted runtime scope or fails cleanly without affecting unrelated live automation behavior.

### gRPC APIs

- `UpdateScript` – bootstrap/dev-only script upload path. Not part of the production runtime publish contract; production rollout uses `PublishScriptPatchVersion` + `NotifyScriptVersionUpdate` lifecycle gates.
- `GetScriptStatus` – queries whether a script is queued or running for a given
  entity.
- `NotifyScriptVersionUpdate` – informs the service that a new `script_patch_version`
  is available for tenant-readiness ingestion; the service validates and stages
  the patch for `PENDING_VALIDATION -> ONLOAD_RUNNING -> READY/FAILED/SUPERSEDED`, while
  running instances reload only after a later pin change to that tenant-`READY`
  patch.
- **Event ingress RPCs** – domain services such as the Game Session Service and Game Logic Service call event-ingress methods (for example, `TriggerScriptEvent` or a batch equivalent defined in `automation_scripting_service.proto`) to deliver script events. These RPCs carry:
  - `tenantId`, `gameInstanceId`, `regionId`, and `entityId` for the target context.
  - `regionEpoch` for gameplay/runtime triggers and scheduler triggers so Trigger Identity is fenced across scoped coordination resets (see the normative Trigger Identity table in `design/architecture/system-architecture-scripting-normative-contract-tables.md`).
  - `scriptEventId` as an idempotency identifier following endpoint ownership rules.
  - `isDryRun` so live and dry-run/test traffic are always in separate idempotency namespaces.
  - `eventType` and versioning metadata such as `scriptPatchVersion`.
  - An envelope for the event payload, including any domain-specific fields.
  Event ingress RPCs are **idempotent** with respect to Trigger Identity (including `scriptEventId`) and the script that handles the event: repeated calls with the same Trigger Identity must not cause the DSL body to run twice. The Automation & Scripting Service implements this in accordance with the `scriptEventId` lifecycle and deduplication rules described in `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md#scripteventid-lifecycle-and-deduplication`.
  Admission must also enforce pin consistency for `<tenantId, gameInstanceId>`:
  - if the request patch is not `READY` for the tenant, reject with `finalOutcome=version_unavailable`;
  - if local pin state is stale beyond max age and cannot be refreshed, reject with `finalOutcome=pin_state_unavailable`;
  - if the request patch is `READY` but differs from the observed pinned patch for the instance, reject with `finalOutcome=version_unavailable` and a bounded mismatch reason rather than silently substituting a version.
  Custom/service-specific events must additionally be validated against a canonical event registry so only authorized producer services can emit a given `eventType` and schema version.

### Idempotency & Retries

`scriptEventId` ownership is endpoint-specific:

- Live external ingress (`TriggerScriptEvent`): caller must supply `scriptEventId` and reuse it on retries.
- Scheduler/timer ingress (`onInterval`, `onTimerExpire`): scheduler generates deterministic `scriptEventId` from due-point identity, including `gameInstanceId`.
- Dry-run/test ingress: service generates by default; caller-supplied IDs are optional and must pass dry-run namespace collision validation.

These identifiers serve as the canonical idempotency keys for event ingress:

- Any RPC that accepts `scriptEventId` as part of its request (for example, `TriggerScriptEvent` and timer-driven internal scheduling) is **idempotent with respect to Trigger Identity**:
  - For entity-scoped external events, the idempotency key is at least `<tenantId, gameInstanceId, regionId, regionEpoch, entityId, scriptId, eventType, scriptPatchVersion, scriptEventId, isDryRun>` for gameplay/runtime triggers.
  - For scheduler events, the idempotency key also includes a due point (`dueTickId` / `dueAt`) in the deterministic `scriptEventId` derivation.
  - Re-sending the same request with the same idempotency key must not cause the DSL body to run twice.
  - The service records at most one `script_event_audit` row per handler-scoped idempotency key, meaning one row per resolved Trigger Identity after fan-out to a specific `scriptId` or plugin handler. One inbound event may therefore still produce multiple audit rows when multiple handlers are resolved.
- Downstream calls made from DSL components (for example, to Game Logic or World Management) must also carry a **stable idempotency token** derived from Trigger Identity plus tick context when applicable (for example including `entityId`, `eventType`, `scriptPatchVersion`, `scriptEventId`, and optionally `tickId`/`regionEpoch`) so infrastructure-level retries do not duplicate side effects. See `design/architecture/system-architecture-transactions.md` for recommended patterns.

Transport-level retries:

- Unary event ingress calls are safe to retry at the gRPC transport layer **only if** they reuse the same `scriptEventId`.
- Timer and scheduler internals may retry infrastructure operations (for example, Redis writes) but never re-execute the DSL body for the same `scriptEventId`; they replay only idempotent downstream operations.

### Reload Backpressure and Retry Contract

During `reloadState=RELOADING`, this service must return explicit backpressure signals on event-ingress calls so callers can decide whether to retry:

- `TriggerScriptEventResponse.admitted=false`
- `TriggerScriptEventResponse.admission_outcome=TRIGGER_ADMISSION_OUTCOME_BACKPRESSURE_RELOADING`
- `TriggerScriptEventResponse.admission_reason="reloading"` (or equivalent)

In addition, the service records `script_event_audit.finalStage=ADMISSION` with `finalOutcome=skipped_reloading` and `finalReason=reloading` for correlation and operator visibility.

During operator rollback pause (`PAUSED_FOR_ROLLBACK`), ingress must return an explicit rollback backpressure outcome and audit record:

- `script_event_audit.finalStage=ADMISSION`
- `script_event_audit.finalOutcome=skipped_rollback_pause`
- `script_event_audit.finalReason=rollback_pause`

- For low-rate external events, callers may retry with the same `scriptEventId` using bounded exponential backoff and jitter with explicit limits (`maxAttempts`, `maxElapsedMs`) and non-zero jitter.
- Backpressure responses should include `retryAfterMs` so clients can avoid thundering-herd retries during reload.
- For timer-derived scheduler events, best-effort timer semantics apply; triggers not admitted during reload are not backfilled unless explicitly covered by a bounded catch-up rule.
- Event-ingress response fields (`admitted`, `admissionOutcome`, `admissionReason`, `retryAfterMs`) and enum values are normative API contract and must align with `design/architecture/system-architecture-scripting-control-plane-api.md`.
- These ingress response fields are **event-scope** only. A successful ingress admission means the request was accepted for handler resolution; it does not mean every resolved script/plugin handler later succeeded. Per-handler outcomes remain authoritative in `script_event_audit`.
  For a concrete mixed fan-out example of one admitted event producing divergent handler outcomes, see `design/architecture/system-architecture-scripting-examples-and-patterns.md#mixed-fan-out-example`.

See `design/architecture/system-architecture-scripting-contracts.md#7-reload-backpressure-contract`.

### Pinned Version Visibility Consistency

Admission and scheduler decisions must use a bounded-staleness view of pinned script patch/plugin versions:

- A local cache populated by control-plane events is allowed, but it must enforce a configured max-age.
- If pin data for a scope is stale beyond max-age, the service must refresh from authoritative control-plane APIs/events before admitting new work.
- If fresh authoritative pin data cannot be obtained, admission must fail closed with an explicit non-success outcome and audit visibility rather than speculatively running with stale pin state.
- The canonical failure contract for this case is `finalStage=ADMISSION`, `finalOutcome=pin_state_unavailable`, and a bounded `finalReason` (for example `pin_cache_stale_source_unreachable`).
- If fresh authoritative pin data is available and does not match the request's `scriptPatchVersion` / plugin version, the request must still fail closed. For scripts, use `finalOutcome=version_unavailable` with a bounded mismatch reason; for plugins, reject against the instance's active plugin state with the corresponding plugin-version failure outcome.
- Any override of this fail-closed behavior must be explicit, time-bounded, and operator-audited (`controlPlaneRequestId`, actor, reason, scope), and must auto-expire back to fail-closed mode.

Plugin signer-policy admission follows the same fail-closed principle:

- If signer policy for scope is stale beyond max-age and cannot be refreshed from authoritative policy sources, plugin admission must fail closed with `finalStage=ADMISSION`, `finalOutcome=signer_policy_unavailable`, and a bounded `finalReason`.
- Any degraded override permitting admission while signer policy is unavailable must be explicit, time-bounded, and operator-audited, with automatic expiry back to fail-closed mode.

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
  - World Management Service receives world-state update commands from scripts (for example door/weather toggles) via tick-driven effects scoped by `RoomInstanceRef` and guarded by `EffectId`.
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

For day-to-day operations, environment variables fall into three broad categories:

- **Stable operator knobs** – part of the supported operational surface and expected to remain compatible across minor releases.
- **Advanced/experimental** – powerful tuning knobs that should be changed only with guidance from maintainers.
- **Internal implementation details** – not intended for direct use; may change or be removed without notice.

Additional variables tune the scripting engine:

| Variable | Purpose | Default | Class |
| -------- | ------- | ------- | ----- |
| `SCRIPT_QUOTA_LIMIT` | Number of events a script may process per window | `50` | Stable operator knob |
| `SCRIPT_QUOTA_WINDOWSECONDS` | Length of the quota window in seconds | `60` | Stable operator knob |
| `AUTOMATION_TICK_DURATION_MS` | Duration of a processing tick in milliseconds | `1000` | Stable operator knob |
| `AUTOMATION_TICK_MAX_EVENTS` | Max events staged from the automation queue each tick | `50` | Stable operator knob |
| `AUTOMATION_TICK_BUDGET_MS` | Soft execution budget for a script tick in milliseconds | `100` | Advanced/experimental |
| `SCRIPT_EVENT_AUDIT_RETENTION_DAYS` | Number of days to retain script audit records before cleanup | `30` | Stable operator knob |
| `SCRIPT_EVENT_AUDIT_MAX_ROWS` | Maximum number of rows to keep in the script audit store before truncation | `1000000` | Stable operator knob |
| `SCRIPT_TEST_MAX_RUNS_PER_MINUTE` | Maximum dry-run/test executions allowed per tenant per minute | `60` | Stable operator knob |
| `SCRIPT_TEST_MAX_RUNS_PER_MINUTE_PER_PRINCIPAL` | Maximum dry-run/test executions allowed per principal per tenant per minute | `30` | Stable operator knob |
| `SCRIPT_TEST_MAX_CONCURRENCY` | Maximum concurrent dry-run/test executions per tenant or cluster | `10` | Stable operator knob |
| `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME` | Maximum synthetic catch-up timer firings admitted per resume window | `200` | Stable operator knob |
| `SCRIPT_DEAD_LETTER_MAX_ROWS` | Maximum dead-lettered automation work items retained before cleanup | `100000` | Stable operator knob |
| `SCRIPT_DEAD_LETTER_MAX_AGE_SECONDS` | Maximum age for dead-lettered work items | `604800` | Stable operator knob |
| `SCRIPT_DEAD_LETTER_CLEANUP_INTERVAL_SECONDS` | Cleanup sweep interval for dead-lettered work items | `300` | Stable operator knob |
| `SCRIPT_DEAD_LETTER_ALERT_THRESHOLD_ROWS` | Alert threshold for dead-letter store growth | `80000` | Stable operator knob |

Any additional, less common tuning variables should be documented alongside their introduction and clearly marked as advanced or internal. Operational runbooks should treat only **stable operator knobs** as supported surface for routine adjustments; changes to advanced or internal settings should go through code review and coordinated rollout.

### Script Patch Management APIs

In addition to event-handling and test endpoints, the Automation & Scripting Service exposes control-plane APIs for script patch visibility and plugin lifecycle management. Script patch pin authority remains in Game Session/Logging & Admin; plugin runtime lifecycle authority lives in Automation & Scripting and is orchestrated by Logging & Admin.

- `GetScriptPatchStatus(tenantId, scriptPatchVersion)` – returns the current lifecycle state (`PENDING_VALIDATION`, `ONLOAD_RUNNING`, `READY`, `FAILED`, `SUPERSEDED`), `baseVersionId`, `abilitySchemaDigest`, timestamps, and any last-error details for the given `<tenantId, scriptPatchVersion>`.
- `ListScriptPatchStatuses(tenantId, status?, changedAfter?, changedBefore?)` – lists known script patches and their status for a tenant so operators and tools can see which patches are eligible to be pinned.
- `ScriptPatchTenantStatusChanged` event – emitted whenever `<tenantId, scriptPatchVersion>` transitions between tenant readiness lifecycle states.
- `ScriptPatchInstanceRolloutChanged` event – consumed as the authoritative instance rollout history stream produced by Game Session (`PINNED`, `ROLLED_BACK`, `REPINNED`) and projected into read APIs.
- `GetScriptPatchInstanceRolloutStatus(tenantId, gameInstanceId, scriptPatchVersion)` and `ListScriptPatchInstanceRollouts(...)` – read APIs for instance-scoped rollout history and UI/operator correlation.
- `GetAutomationPinConvergence(tenantId, gameInstanceId)` – reports the latest pinned patch observation (`observedPinnedScriptPatchVersion`, `lastObservedControlPlaneRequestId`, `observedAt`) used by admission/scheduler logic so rollback orchestration can gate resume on convergence.
- `GetSignerPolicyConvergence(...)` – reports observed signer-policy version, refresh lag, and enforcement mode so plugin revocation rollouts can be verified end-to-end.
- `GetPluginStatus(tenantId, gameInstanceId, pluginId)` – returns plugin runtime state (`ENABLED`, `DISABLED`, `DRAINING`, `RELOADING`, `FAILED`) and active/pending version IDs.
- `SetPluginActiveVersion`, `DisablePlugin`, and `DrainPlugin` – idempotent plugin lifecycle operations used by Logging & Admin to promote, disable, or drain plugin versions per `<tenantId, gameInstanceId, pluginId>`.
- `SignerPolicyVersionObserved` / `SignerRevocationApplied` events – operator-facing signer-policy propagation and revocation-enforcement events used to prove key-revocation convergence timing.

Consumption rules for patch-status events:

- Use `ScriptPatchTenantStatusChanged` for tenant readiness gates and publish-validation UX (`READY`, `FAILED`, and `SUPERSEDED` history).
- Use `ScriptPatchInstanceRolloutChanged` for instance rollout progression and rollback history.
- Read-model ownership for rollout status is Game Session pin mutations projected into query APIs via idempotent, replayable events keyed by `controlPlaneRequestId`.

Game Session and Logging & Admin use script patch visibility APIs and events to decide which `scriptPatchVersion` values may be passed to the runtime. Mutating operations that change the pinned patch for a running game instance are defined on the Game Session control-plane surface (and orchestrated by Logging & Admin) and must follow the API and event contracts in `design/architecture/system-architecture-scripting-control-plane-api.md` (for example `SetPinnedScriptPatchVersion` / `RollbackScriptPatchVersion` and the `ScriptPatchPinChanged` event). The Automation & Scripting Service uses pin-change events for visibility and admission alignment, but it does not become the source of truth for the pin; it enforces that incoming triggers reference patches that are `READY` for the tenant and records lifecycle changes that authoritative control-plane services request. Pinning must also satisfy base-version cohesion (`patch.baseVersionId == runtimeVersionId` for the instance) to prevent cross-version patch activation.

Rollback orchestration must treat convergence waiting as bounded: if `GetAutomationPinConvergence` + Game Session convergence checks do not match the expected `controlPlaneRequestId` before the configured timeout, rollback enters terminal timeout state (`ROLLBACK_CONVERGENCE_TIMEOUT`) and admission/ticks remain paused until explicit operator action. Timeout transition must emit `ScriptRollbackConvergenceTimedOut` (Game Session producer-of-record) and increment `automation_rollback_convergence_timeout_total{tenantId, gameInstanceId, reason}`.
Rollback orchestration should be implemented as an explicit durable state machine (`PAUSING`, `REPINNING`, `CANCELING`, `PURGING`, `CONVERGING`, `DRAINING`, `RESUMING`, `COMPLETED`, terminal `TIMED_OUT`) so partial failures can resume from last durable state instead of restarting or accidentally unpausing. `DRAINING` remains active until `GetAutomationDrainStatus` confirms that the current rollback-scope `admissionEpoch` has no active pre-pause executions and no remaining cancelable outbox work.

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
- [User Journeys – Add Automation & Scripting](../../user-journeys-creators.md#3-add-automation--scripting)
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

### Dry-Run / Test Execution

In addition to live event handling, the Automation & Scripting Service exposes a **non-committing test path** used by the Game Design and Logging & Admin tools:

- Test runs execute handlers in the same sandbox and with the same loop-safety and resource limits as production runs.
- Instead of persisting and indexing work items (or handing off to tick queues), test runs return the would-be commands to the caller for inspection.
- Test executions are recorded in `script_event_audit` with `isDryRun=true` and the normal `eventType` for the event being exercised (for example, `onEnterRegion` or `onInterval`) so they can be distinguished from live traffic while still being grouped by logical event.
- Dry-run/test requests must use an idempotency namespace that is separate from live traffic (for example include `isDryRun=true` in Trigger Identity) so test calls cannot dedupe, suppress, or overwrite live trigger records.
- Dry-run/test APIs should use server-generated `scriptEventId` values by default. If tooling passes a caller-supplied value, the service must enforce namespace validation and reject identity collisions deterministically.
- By default, dry runs **do not consume ScriptQuotaService windows or tenant automation budgets**, and they must not increment live-traffic error counters. Sandbox failures observed during tests are emitted via dry-run/test-only metric families (for example `automation_script_test_sandbox_failures_total`) so production SLO dashboards do not conflate privileged tooling with live automation reliability.
- By default, dry runs must not contribute to failure-rate circuit breakers that can disable live scripts (`runtimeStatus=DISABLED_DUE_TO_ERRORS`). If an environment chooses to gate live enablement on dry-run results, that gating must be explicit and isolated (separate breaker or opt-in policy) so privileged tooling cannot accidentally disable production automation.
- Separate **dry-run budgets** cap how much test traffic a tenant or principal can generate (for example, max runs per minute and max concurrent dry-runs) so test tools cannot overload the automation cluster even though they bypass mainline quotas.
- Dry-run/test work must execute on isolated capacity (for example a separate worker pool, reserved worker share, or equivalent scheduler partition) so privileged tooling cannot consume the last available live automation workers.
- Dry-run/test execution must require explicit authorization scope/role (for example `automation.dryrun.execute`) and must persist the calling principal in audit metadata so privileged usage is attributable.
- Dry-run/test authorization and budget failures must be returned as deterministic application-level outcomes (for example `DRY_RUN_UNAUTHORIZED`, `DRY_RUN_RATE_LIMITED`) rather than transport errors.

This test facility is intended for pre-production validation and privileged diagnostics; it should be exposed only to appropriately authorized principals, protected by additional per-tenant/per-principal rate limits at the API gateway or Logging & Admin layer, and is not a general-purpose bypass for quotas or budgets.

### Fairness Quotas

`ScriptQuotaService` limits how many times a script may execute within a
configurable window. Counters are stored in Redis using keys of the form
`automation:quota:<tenantId>:<scriptId>`. When the quota is exceeded the event is
ignored and `script_quota_denied_total{tenantId, scriptId, reason}` is incremented. Saga orchestration emits separate Saga-specific metrics (for example `sagas.active`) and must not be conflated with quota enforcement.

Non-normative metric examples (the authoritative contract is `design/architecture/system-architecture-scripting-observability-contract.md`) include:

- `automation_script_triggers_total`, `automation_script_skips_total`, and `automation_script_triggers_dropped_total` for scheduler activity and drops.
- `automation_script_queue_delay_seconds` and `automation_script_leadership_changes_total` for queue latency and leader stability.
- `automation_script_timer_catchup_truncated_total` for catch-up firings intentionally truncated by resume-window limits.
- `automation_script_tenant_budget_seconds{tenantId, tier}` for per-tenant automation budgets.
- `script_quota_allowed_total`, `script_quota_denied_total`, and `automation_tick_events_enqueued_total` for quota enforcement and tick integration.
- `automation_script_sandbox_failures_total{tenantId, scriptId, reason}`, `automation_script_errors_total{tenantId, scriptId, reason}`, and `automation_script_runtime_seconds{tenantId, scriptId, eventType}` for sandbox and runtime health.

See [Logging & Monitoring](../../system-architecture-logging-monitoring.md) and `design/architecture/system-architecture-scripting-observability-contract.md` for how these metrics map to audit records, are scraped, and are used for alerting.

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
