# FireMUD System Architecture: Scripting DSL Reference & Event Lifecycle

This document is the **canonical reference** for the scripting DSL’s terminology, execution lifecycle, semantics, and failure modes. It is intended for implementers and backend developers integrating with the Automation & Scripting Service, Tick System, and related infrastructure. For sandbox enforcement details (CPU, time, and memory budgets, and how failures surface in `script_event_audit`), pair this document with `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`, which is the canonical spec for the sandbox engine itself.

Document conflict resolution order is defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#document-precedence-normative`. This document provides DSL/runtime semantics and must align with higher-precedence contract documents.

It is a companion to:

- `design/architecture/system-architecture-scripting.md` – hub for the overall scripting and automation framework.
- `design/architecture/system-architecture-scripting-dsl-for-designers.md` – designer-oriented overview of the DSL and visual editor.
- `design/architecture/system-architecture-scripting-quotas-and-operations.md` – quotas, circuit breakers, and operational behavior.
- `design/architecture/system-architecture-ticks.md` – tick model, idempotency rules, and replay semantics.
- `design/architecture/system-architecture-transactions.md` – transaction patterns and idempotent downstream operations.
- `design/architecture/system-architecture-versioning-runtime.md` – script-only patch versions and runtime configuration.
- `design/architecture/microservices/automation-scripting-service/README.md` – service-level design and implementation details.

For a higher-level routing guide to all scripting and automation docs, see the **Who Should Read What** and **Where to Find Details** sections in `design/architecture/system-architecture-scripting.md`.

## Table of Contents

- [Audience](#audience)
- [Terminology Glossary](#terminology-glossary)
- [Versioning Terms](#versioning-terms)
- [Script Execution Lifecycle](#script-execution-lifecycle)
- [`scriptEventId` Lifecycle and Deduplication](#scripteventid-lifecycle-and-deduplication)
- [Supported Script Events](#supported-script-events)
- [Event Fan-Out and Handler Ordering](#event-fan-out-and-handler-ordering)
- [Custom and Service-Specific Events](#custom-and-service-specific-events)
- [Scripting DSL Semantics](#scripting-dsl-semantics)
- [Deployment & Versioning](#deployment--versioning)
- [Determinism & Allowed Non-Determinism](#determinism--allowed-non-determinism)
- [Integration with Game Logic & Tick System](#integration-with-game-logic--tick-system)
- [Script Timers vs Tick Timers](#script-timers-vs-tick-timers)
- [End-to-End `onInterval` Timer Lifecycle](#end-to-end-oninterval-timer-lifecycle)
- [Scheduler Leadership & Coordination](#scheduler-leadership--coordination)
- [Hot Reload & Resume Behavior](#hot-reload--resume-behavior)
- [Failure Modes and Error Handling](#failure-modes-and-error-handling)

---

## Audience

- **Implementers and backend developers**
  - Use this document as the reference for terminology, event lifecycles, DSL semantics, determinism rules, and scheduler behavior.
  - Pair with `design/architecture/system-architecture-ticks.md` and `design/architecture/system-architecture-transactions.md` for cross-cutting concerns.

For designer-oriented guidance on building and debugging scripts in the visual editor, see `design/architecture/system-architecture-scripting-dsl-for-designers.md`.

---

## Terminology Glossary

- **Game tick** – a region-scoped tick in the Game Session Service. Each `<tenantId, regionId>` advances through a monotonic `tickId` stream; game ticks are authoritative for gameplay state changes and use `tick:{tenantRegionTag}:...` keys and locks as described in [Tick System and Runtime Design](./system-architecture-ticks.md).
- **Automation/script tick** – a batching cycle inside the Automation & Scripting Service. `ScriptTickService` drains **script work items** from Redis-backed queues such as `automation:queue:<tenantId>:<entityId>`, stages them under `automation:tick:{tenantScriptTag}:...`, and hands the resulting commands to the Game Session Service so Game Session can enqueue **tick commands** into per-entity tick queues for later execution by game ticks. Automation ticks control script-side quotas and batching, not authoritative game state.
- **Automation queue** – a per-tenant, per-entity Redis queue (`automation:queue:<tenantId>:<entityId>`) that holds **derived work-item indexes/pointers** after sandboxed DSL execution and durable persistence. It is reset-tolerant and rebuildable from the durable outbox; it must not be treated as an authoritative log of pending work.
- **Tick heartbeat** – a **gRPC streaming feed** produced by the Game Session Service that reports `(regionEpoch, tickId)` progression per `<tenantId, regionId>`. The script scheduler consumes this heartbeat over a long-lived gRPC stream to count “every N ticks” intervals and align `onInterval` triggers with the canonical game tick timeline without owning tick execution itself. See [Tick Events & Heartbeat Stream](./system-architecture-ticks.md#tick-events--heartbeat-stream) for transport details and the `(regionEpoch, tickId)` coordination timeline.

---

## Versioning Terms

These definitions summarize how common versioning concepts are used in scripting; the full model lives in [System Architecture: Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md). For the per-tenant lifecycle of script patches after publish, see [Script Patch Lifecycle](#script-patch-lifecycle).

- **`scriptPatchVersion`** – a logical script-only patch identifier tracked per tenant/game (for example in the Game Session Service as `script_patch_version`). It pins which published script set is considered active at runtime so all triggers and timers execute against a consistent script configuration.
- **`versionId`** – an internal identifier for a concrete compiled script or component version. `versionId` values distinguish individual revisions within a `scriptPatchVersion` and are used by the Automation & Scripting Service to load the exact behavior that should run for a given trigger.
- **`runtimeStatus`** – the current runtime state of a script as seen by the scheduler (for example, `ENABLED`, `DISABLE_AFTER_DRAIN`, `DISABLED`, `DISABLED_DUE_TO_ERRORS`). `runtimeStatus` controls whether new triggers are accepted, drained, or skipped and is updated by hot reload flows and administrative actions.

---

## Script Execution Lifecycle

The scripting pipeline uses a small set of terms repeatedly; the table below summarizes them and how they relate:

Normative Trigger Identity required fields (including `gameInstanceId` and when `regionEpoch` is required) are defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields`.

| Step | Term | Description | Stored as / example |
| --- | --- | --- | --- |
| 1 | **Trigger** | A concrete event such as `onEnterRegion`, `onCommand`, or a custom event emitted by a service. | gRPC `TriggerScriptEvent` call, tick heartbeat, or internal scheduler event. |
| 2 | **DSL run** | Execution of a script handler in the sandboxed DSL for a single trigger. Produces domain commands, not direct state changes. | In-memory execution in the Automation & Scripting Service; results summarized as script work items. |
| 3 | **Script work item** | A post-DSL, per-entity descriptor of what should happen (domain commands + `scriptEventId`, `scriptId`, version metadata) persisted durably (outbox). | Indexed via `automation:queue:<tenantId>:<entityId>` and staged under `automation:tick:{tenantScriptTag}:...`. |
| 4 | **Tick command** | A concrete command that the Game Session Service executes during game ticks under its normal locking and idempotency rules. | Enqueued into `tick:{tenantRegionTag}:queue:<entityId>` for consumption by the tick loop. |

Triggers lead to DSL runs, which produce script work items in the automation queues, which automation ticks turn into tick commands for the Game Session Service.

---

## Work Item Outbox Contract (Normative)

The Automation & Scripting Service must treat Redis automation queues (`automation:queue:*`) as derived indexes/pointers only. The authoritative record of admitted post-DSL work is the durable work item outbox.

This section defines the minimum contract that makes “persist → index → drain → handoff” interoperable and rollback-safe across services and operational tooling.

### Minimum Outbox Record Fields

Each persisted script work item must include:

- Trigger Identity fields from `design/architecture/system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields` (including `gameInstanceId` and, for gameplay/runtime triggers, `regionEpoch`).
- `outboxWorkItemId` (stable unique identifier).
- `createdAt` and `updatedAt`.
- `workItemStatus` (see below).
- `commands` (the domain commands payload, stored durably).
- `commandCount` (for budgeting/inspection).
- `cancelReason` (nullable; required when canceled).

### Minimum Status Model

Statuses are a target-state contract; implementations may use different internal names as long as they are mapped 1:1:

- `PENDING` – persisted, eligible for indexing and draining.
- `INDEXED` – a pointer/index has been published into `automation:queue:*` (best-effort; may be rederived).
- `HANDOFF_IN_FLIGHT` – being handed off to Game Session (idempotent retries allowed).
- `HANDED_OFF` – Game Session has accepted the corresponding tick commands into tick queues (`script_event_audit.finalStage=TICK_HANDOFF` is now eligible for `finalOutcome=success`).
- `CANCELED` – permanently canceled by control plane (for example rollback, disable, or operator purge).
- `DEAD_LETTERED` – permanently non-progressing due to repeated infrastructure failures; bounded retention and operator visibility are required.

### Pointer Payload Contract for `automation:queue:*`

Entries in `automation:queue:<tenantId>:<entityId>` must contain enough information to locate and safely process the durable outbox record:

- `outboxWorkItemId`
- A minimal identity checksum (for example `scriptPatchVersion`, optional `pluginVersionId`) so rebuild/drain logic can detect version-fence mismatches early without reading full payloads.

The pointer/index format must be forward-compatible (versioned envelope) so it can evolve without requiring out-of-band Redis migrations.

### Rebuild and Deduplication Rules

- Rebuilding `automation:queue:*` from the outbox must be safe to run repeatedly and concurrently (idempotent projection).
- `ScriptTickService` must dedupe drain/handoff by `outboxWorkItemId` (not by Redis list position) so queue resets, re-indexing, and retries do not cause double-handoff.
- `CancelPendingWorkItemsForPatch` and `CancelPendingWorkItemsForPluginVersion` must be implemented as outbox state transitions (`workItemStatus=CANCELED`) so cancellation is durable even if Redis is reset. Cancellation must be reflected in `script_event_audit` stage-aware outcomes (for example `finalStage=ADMISSION` with a cancel outcome/reason for newly arriving triggers, and non-success outcomes for already persisted work that is canceled before handoff).

### Operational Constraints

- Outbox scanning for rebuild and cancellation must be bounded and backpressured (pagination, time windows, per-tenant limits) so it cannot become an unbounded full-table scan on large tenants.
- Outbox retention must be explicitly defined for `HANDED_OFF`, `CANCELED`, and `DEAD_LETTERED` records, and must preserve enough history for rollback diagnosis and audit queries.

---

## `scriptEventId` Lifecycle and Deduplication

`scriptEventId` is the canonical identifier for a single script trigger/run; it appears on automation queue entries, tick commands, and `script_event_audit` rows so behavior can be correlated end-to-end.

- **Generation rules**
  - For **external events** (for example, `onEnterRegion`, `onSpawn`, `onCommand`, and custom service events), the **event source** that owns the trigger (typically the Game Session Service or another domain service) creates a `scriptEventId` when the event is first emitted and includes it in the `TriggerScriptEvent` payload. If the caller retries the gRPC call due to infrastructure errors, it must reuse the same `scriptEventId` so the Automation & Scripting Service can recognize the trigger as the same logical event.
  - For **scheduler-originated events** such as `onInterval` and `onTimerExpire`, the **Automation & Scripting Service scheduler** creates the `scriptEventId` when the timer or interval becomes due.
  - For **dry-run/test invocations**, the Automation & Scripting Service generates `scriptEventId` by default so test tooling does not create cross-client collisions. If caller-supplied IDs are allowed for specific tooling, they must still be validated in the dry-run namespace and rejected on collision.

- **Uniqueness scope**
  - Uniqueness is enforced over the full **Trigger Identity** field set, including `gameInstanceId` and (for gameplay/tick-aligned triggers) `regionEpoch`. The authoritative field list and required due-point rules for scheduler triggers live in `design/architecture/system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields`.
  - There is no requirement for global uniqueness across all tenants; downstream idempotency keys are derived from stable tuples such as `<tenantId, regionId, regionEpoch, entityId, scriptId, scriptEventId, tickId, scriptPatchVersion>` depending on the call path.

- **Deterministic scheduler IDs**
  - Scheduler-originated `scriptEventId` values must be deterministic so leader failover and bounded catch-up do not double-fire.
  - The specific encoding is an implementation detail, but it must be derived from stable inputs (for example `<tenantId, regionId, regionEpoch, entityId, scriptId, eventType, dueTickId|dueAt, scriptPatchVersion>`), not from process-local randomness.

- **Handling retries and duplicates**
  - The Automation & Scripting Service treats script execution as **at-most-once per Trigger Identity**, as defined in the uniqueness scope above. If it receives a duplicate delivery for the same Trigger Identity—for example, because the caller retried a gRPC call—it does **not** re-run the DSL graph for that trigger. Instead it consults existing `script_event_audit` state and treats the duplicate as a replay of an already completed or skipped trigger.
  - Duplicate delivery handling must preserve a single `script_event_audit` row per Trigger Identity with monotonic stage progression; retries update existing state and must not create parallel audit rows.
  - Downstream services and replay tools rely on stable idempotency tokens derived from Trigger Identity plus tick context when applicable (for example including `tickId` and `regionEpoch`). Commands produced by scripts must carry enough metadata to correlate retries with the original trigger without introducing new high-cardinality metric labels.

---

## Supported Script Events

The DSL supports a variety of **built-in lifecycle events** and **custom events**. The exact set of events and their payload schemas are defined in the Automation & Scripting Service and domain service contracts; this section summarizes the main categories and how they behave.

- **Script lifecycle events**
  - `onLoad` is a **script-level lifecycle event** that runs once per `<tenantId, scriptId, scriptPatchVersion>` when a script becomes active for a tenant under a given patch. It is designed for initializing script-global state (for example, loading lookups, seeding script-local caches, writing initial audit markers) rather than per-entity setup.

- **Spawn and destruction events**
  - `onSpawn` events fire when an entity (such as an NPC) is created or enters a relevant region.
  - Destruction or despawn events allow scripts to clean up state or schedule follow-up behaviors.

- **Region and movement events**
  - `onEnterRegion` and `onLeaveRegion` fire when entities cross region boundaries or enter/exit scripted areas.
  - Movement-related events can drive patrol behaviors, ambushes, or world reactions.

- **Command and interaction events**
  - `onCommand` events fire when a player issues a recognized command.
  - Interaction events such as “talk to NPC” or “interact with object” are modeled as service-specific events that feed into the same triggering path.

- **Timer and interval events**
  - `onInterval` and `onTimerExpire` events are driven by the scheduler and tick heartbeat. They are used to express “every N ticks” or “after a delay” behaviors.
  - These events always execute against the script configuration pinned by the active `scriptPatchVersion` when they fire.

See the Automation & Scripting Service README and service protos for the full, up-to-date list of event types and schemas.

---

### `onLoad` Semantics

`onLoad` is a **script-level lifecycle event**, not an entity-level event. It runs without an entity context and executes once per script definition and script patch for a tenant, not once per NPC or player.

- **When it fires**
  - The scheduler emits an `onLoad` trigger exactly once per `<tenantId, scriptId, scriptPatchVersion>` while that patch is the **pending** patch for the tenant, before it is promoted to the active `scriptPatchVersion` used by Game Session. In practice this means:
    - When a script first becomes part of the tenant’s pending script set under a given `scriptPatchVersion` (lifecycle `PENDING_VALIDATION` → `ONLOAD_RUNNING`), and
    - After a successful hot reload that introduces a new pending patch for that tenant, `onLoad` fires once for each script in that pending patch.
  - If reload or validation fails and the patch never reaches `READY`, `activePatchVersion` remains unchanged and no additional `onLoad` events are generated for that patch.

- **Per-script vs per-entity**
  - `onLoad` runs **without an entity context**; it executes once per `<tenantId, scriptId, scriptPatchVersion>`.
  - Scripts that need per-entity initialization (for example, setting up patrol state when an NPC enters the world) should use `onSpawn`, `onEnterRegion`, or other entity-scoped events instead of relying on `onLoad`.

- **Interaction with reloads and recovery**
  - The Automation & Scripting Service treats `onLoad` as **at-most-once per `<tenantId, scriptId, scriptPatchVersion>`**, even across process restarts and leader changes. Load-completion state is tracked in persistent metadata so that simply restarting a scheduler instance does not re-fire `onLoad` for a script whose patch has already been initialized for that tenant.
  - `onLoad` triggers are enqueued only while the patch is tracked as `pendingPatchVersion` with lifecycle `ONLOAD_RUNNING`; `activePatchVersion` remains on the previous patch until all `onLoad` handlers succeed and the lifecycle transitions to `READY`. Scripts never run `onLoad` against a patch that is already the active `scriptPatchVersion` for a tenant.
- Like other events, each `onLoad` trigger is recorded in `script_event_audit` with `eventType=onLoad`, `tenantId`, `scriptId`, the target `scriptPatchVersion`, and stage-aware outcome fields (`finalStage`, `finalOutcome`, `finalReason`, plus any per-stage breakdown) so operators can verify that initialization ran for a given script and patch and see exactly where it failed (admission vs DSL eval vs persistence vs tick handoff).

---

## Event Fan-Out and Handler Ordering

An entity may have **multiple handlers bound to the same event**, including both core scripts and plugin handlers. The Game Design and plugin registries store these bindings as ordered lists per `{entityId, eventType}`.

When an event fires, the Automation & Scripting Service evaluates bound handlers in a **single deterministic order** sorted by:

1. `orderIndex ASC`
2. `handlerType ASC` (`SCRIPT` before `PLUGIN` unless policy overrides are explicitly configured and documented)
3. `handlerId ASC` (`scriptId` or `pluginId`)

This ordering is stable across deployments so that the same binding set produces the same command sequence for a given event.

Failures are isolated per handler by default. If one handler fails (for example, quota denial, sandbox exception, or compilation error), the scheduler records the failure and continues to the next handler unless the failing binding is marked as requiring exclusive handling. Exclusive handling (`requiresExclusiveEvent=true`) short-circuits remaining handlers regardless of whether they are scripts or plugins. Quota checks (`ScriptQuotaService`) remain per handler either way.

---

## Custom and Service-Specific Events

Domain services can define **custom events** that feed into the scripting pipeline:

- The visual DSL exposes event source nodes for any event types enabled for the current game. Under the hood, bindings are stored as `<tenantId, eventTypeKey, eventSchemaVersion, scriptId>`.
- Service-specific events follow the same trigger → DSL run → automation queue → tick command flow as built-in events.
- Event schemas are versioned so scripts can be migrated when payloads change.

Custom events must follow the same determinism and idempotency rules as built-in events; they are keyed by Trigger Identity plus tick context when producing commands (for example including `entityId`, `eventType`, `scriptPatchVersion`, `scriptEventId`, and `tickId`/`regionEpoch` where applicable).

---

## Scripting DSL Semantics

This section captures the DSL’s formal semantics from the engine’s perspective. See `design/architecture/system-architecture-scripting-dsl-for-designers.md` for a designer-oriented explanation.

### Graph Model and Control Flow

- The DSL uses a **directed graph model**: nodes represent conditions, actions, timers, counters, and other components; edges represent control-flow transitions such as `onTrue`, `onFalse`, `onTimeout`, `onBelowThreshold`, or `onAboveThreshold`.
- Execution walks this graph starting from one or more **entry nodes** bound to an event. There is **no general-purpose stack or call frame**; instead, control flow is fully represented by explicit edges.
- **Branching** is expressed via condition nodes that evaluate predicates and route to different successors. For example:
  - A `HealthCheck` node exposes `onBelowThreshold` and `onAboveThreshold` outputs.
  - A `ReputationCheck` node exposes `onFriendly`, `onNeutral`, and `onHostile` outputs.
  - Designers combine these via aggregator nodes such as `AllOf` and `AnyOf`.

### Predicates and Node Types

- **Complex predicates** such as “if reputation < X and HP < Y” are modeled as small subgraphs that compose simpler condition nodes (for example, `HealthCheck` → `ReputationCheck` → `AllOf` / `AnyOf`).
- Each node type defines **strongly typed inputs** (attributes, thresholds, flags) and a fixed set of outputs.
- The Game Design Service validates connections in the visual editor, and the Automation & Scripting Service revalidates when compiling scripts so ill-typed or incompatible graphs never reach runtime.

### Loops and Asynchrony

- Loops are supported only as **bounded, explicit cycles** in the graph.
- Asynchronous edges—for example, timer callbacks that resume execution in a future tick—are modeled as **new triggers** and therefore **new DSL runs**; they are not part of the same-run graph for loop analysis.

### Loop Safety Analysis

Before a script is accepted, the Automation & Scripting Service runs a **loop safety analysis** over the component graph to ensure there are no unbounded cycles within a single script invocation:

- The compiler builds a **reduced graph** for analysis that includes only **same-run edges**. Asynchronous edges (for example, timer callbacks that fire in a future tick) are treated as new invocations and are excluded from this graph so they do not count as busy loops.
- It then computes **strongly connected components (SCCs)** on the reduced graph. Any SCC with more than one node, or a self-loop, is treated as a candidate loop.
- A loop is considered **safe** only if the SCC contains at least one **bounded guard node**, such as a `Counter` node with a finite `maxIterations`.
- Loops without such a guard are rejected at validation time with a descriptive error that points to the participating nodes and is surfaced in the Game Design Service UI so designers see which connections must change before the script can be published.

In addition to static checks, the runtime enforces a **per-run iteration budget**. If a bug or future change allows an unsafe loop to slip through static analysis, the engine aborts the run with a `sandbox_error` (for example, `reason=iteration_budget_exceeded`) before it can spin indefinitely. See `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md` for details on runtime safeguards.

---

## Deployment & Versioning

Script deployment and versioning align with the platform-wide model described in `design/architecture/system-architecture-versioning-runtime.md`.

### Game Design vs Automation & Scripting Responsibilities

Two services collaborate to deliver scripting and automation:

- **Game Design Service**
  - Owns the **authoring UX**: the visual DSL editor, component palettes, world-generation triggers, and per-tenant configuration screens.
  - Manages **draft and published configurations** for scripts, event bindings, and world-generation presets inside its own schema, including version history and “upgrade available” hints when components change.
  - Controls the **publish lifecycle** for scripts and component graphs: designers edit drafts, run validations, and then publish a new `scriptPatchVersion` tied to a `baseVersionId` as described in [System Architecture: Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#script-only-patch-versions).
  - Drives a **cross-service Saga** when a script version is published:
    - Writes the final, validated script graphs and bindings into its own tables.
    - Validates that referenced runtime assets such as abilities and actions are compatible with the target game version; if mismatches are detected, the Saga marks the publish as `FAILED` and the affected `scriptPatchVersion` is never eligible to become `READY` for that tenant.
    - Starts a Saga that upserts the compiled script definitions, event bindings, and any world-generation hooks into the Automation & Scripting Service schema for the target `<tenantId, scriptPatchVersion>`.
    - On success, marks the version as `PUBLISHED` and calls `NotifyScriptVersionUpdate` so the Automation & Scripting Service reloads runtime state.
    - On failure, rolls back or marks the publish as `FAILED`, keeping the prior `scriptPatchVersion` as the active one for that game.

- **Automation & Scripting Service**
  - Owns the **compiled graph schema and runtime registry**: it stores compiled DSL graphs, per-tenant script metadata, and runtime flags (`runtimeStatus`, quotas, priorities) in its own database.
  - Enforces **runtime behavior**: sandbox execution, loop safety, per-script and per-tenant quotas (`ScriptQuotaService`), tick integration, and leadership leases over automation ticks.
  - Maintains **auditability and observability** for script execution via the `script_event_audit` feed and automation metrics (for example, `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`).
  - Implements **hot reload and failure handling** for script patches, including `activePatchVersion`, `pendingPatchVersion`, and `reloadState` as described in [Hot Reload & Resume Behavior](#hot-reload--resume-behavior).

Because script definitions are stored in the Automation & Scripting Service database and loaded via `scriptPatchVersion`, designers can roll out script-only updates without redeploying the service binary; the service reloads definitions in place using its versioning and hot-reload flow.

### Runtime Version Behavior

- Script definitions are stored in the **Automation & Scripting Service** database and versioned alongside other game assets. Publishing updates from the Game Design Service is supported.
- Designers can deploy updated scripts without redeploying code. The Automation & Scripting Service retrieves the current live versions as needed.
- Script-only patches create a `scriptPatchVersion` (the logical/API name) tied to a `baseVersionId` so new behaviors can be loaded on the fly. In the Game Session Service database this is persisted as `script_patch_version`. See [System Architecture: Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#script-only-patch-versions) for how these patch versions work.
- The Game Session Service stores the active `scriptPatchVersion` for each running game. When a new patch is published, the Game Design Service calls `NotifyScriptVersionUpdate`, allowing the Automation & Scripting Service to reload updated scripts via its versioning and hot-reload flow without downtime.
- Timer events and scheduled evaluations always reference the version pinned by the Game Session Service at the moment they run.
- Older versions remain in the database for auditing or rollback, but only the pinned version is executed.

### Version Authority & Consistency

At a high level:

- The **Game Design Service** owns the *authoring* view of versions and drives the publish Saga.
- The **Automation & Scripting Service** owns the *runtime* view of script patch readiness per tenant (for example, whether a patch is `READY` or `FAILED`).
- The **Game Session Service** owns the *pinned* `scriptPatchVersion` for each game and is responsible for including that version in events sent to the Automation & Scripting Service.

The intended invariants are:

- A script patch may be pinned as the active `scriptPatchVersion` for a game only after the Automation & Scripting Service has loaded and validated that patch for the tenant and marked it `READY` as part of the publish Saga.
- When Game Session emits events, it includes the currently pinned `scriptPatchVersion`. The Automation & Scripting Service must **not** silently substitute a different version; if the supplied patch is unknown or is marked `FAILED` for that tenant, the trigger is rejected.

From the Automation & Scripting Service’s point of view, each `<tenantId, scriptPatchVersion>` follows the lifecycle described in [Script Patch Lifecycle](#script-patch-lifecycle). All script-event audit entries include the effective `scriptPatchVersion` at the time of evaluation so operators can correlate failures with patch lifecycle and publish history.

---

## Script Patch Lifecycle

Script patches have two related but distinct lifecycle views:

- A tenant patch lifecycle owned by the Automation & Scripting Service and tracked per `<tenantId, scriptPatchVersion>`.
- A per-instance pin rollout lifecycle owned by Game Session/control-plane orchestration and tracked per `<tenantId, gameInstanceId, scriptPatchVersion>`.

The tenant lifecycle governs patch readiness and eligibility. The instance lifecycle governs rollout/rollback for running games.

The canonical states are:

- `PENDING_VALIDATION` – the Game Design Service has published a script-only patch version and the Automation & Scripting Service has accepted the compiled graphs and bindings, but `onLoad` initialization has not yet completed for the tenant.
- `ONLOAD_RUNNING` – `onLoad` handlers for scripts in the patch are executing for the tenant. These executions are keyed by `<tenantId, scriptId, scriptPatchVersion>` and must be idempotent.
- `READY` – all `onLoad` handlers for the patch have completed successfully for the tenant. The patch is eligible to become the `activePatchVersion` for games in that tenant, and Game Session may pin it as the current `scriptPatchVersion`.
- `FAILED` – one or more `onLoad` handlers for the patch have failed for the tenant with a logical, sandbox, or infrastructure error after retries are exhausted. The previous `activePatchVersion` remains in use, and the failed patch is not eligible to be pinned.

Typical transitions are:

1. `PENDING_VALIDATION → ONLOAD_RUNNING` when Automation & Scripting begins `onLoad` initialization for the tenant after successfully ingesting a published patch from Game Design. Patches whose publish Saga fails in Game Design (for example, ability schema mismatches) never enter this lifecycle; from Automation’s perspective they do not exist or remain invisible runtime-only.
2. `ONLOAD_RUNNING → READY` when all `onLoad` executions for scripts in the patch succeed for the tenant.
3. `ONLOAD_RUNNING → FAILED` when any `onLoad` execution fails fatally after bounded retries; the previous `activePatchVersion` remains in effect.

Per-instance rollout state is tracked separately (for example `PINNED`, `ROLLED_BACK`, `REPINNED`) and is driven by control-plane APIs/events (`SetPinnedScriptPatchVersion`, `RollbackScriptPatchVersion`, `ScriptPatchPinChanged`). An instance rollback does not imply tenant patch state transition away from `READY`.

Automation & Scripting exposes this lifecycle to other services via:

- A read-only API such as `GetScriptPatchStatus(tenantId, scriptPatchVersion)` that returns the current state and relevant timestamps.
- Tenant readiness events (`ScriptPatchTenantStatusChanged`) emitted when `<tenantId, scriptPatchVersion>` transitions between readiness states.
- Instance rollout events (`ScriptPatchInstanceRolloutChanged`) consumed from Game Session pin-change control-plane events and projected into read APIs when `<tenantId, gameInstanceId, scriptPatchVersion>` rollout history changes (for example `PINNED` / `ROLLED_BACK` / `REPINNED`).

When a trigger arrives at the Automation & Scripting Service:

- If the supplied `scriptPatchVersion` is `READY` for the tenant, the scheduler proceeds normally (subject to quotas, sandbox limits, and error handling).
- If the patch is unknown or in a non-ready state (for example, `PENDING_VALIDATION`, `ONLOAD_RUNNING`, `FAILED`), the trigger is rejected at admission: `script_event_audit.finalStage=ADMISSION` with `finalOutcome=version_unavailable` (or a more specific variant such as `onload_failed`) and an explicit `finalReason`. A drop metric like `automation_script_triggers_dropped_total{reason="version_unavailable"}` is incremented.
- Automation & Scripting never silently falls back to an older patch for that trigger; callers must fix the pinned version, repin explicitly, or republish.

---

## Determinism & Allowed Non-Determinism

Scripts are designed to behave **deterministically for a given game configuration and event**, so that both the original execution and any offline replay in tools or tests produce the same observable behavior. The Automation & Scripting Service enforces this by constraining how randomness and time are exposed to DSL components:

- All **pseudo-random behavior** (for example, “pick a random waypoint”, “roll for loot”, or encounter selection) flows through curated components that read from a **seeded RNG** supplied by the runtime. The seed is derived from stable identifiers such as `<tenantId, regionId, entityId, scriptId, eventType, scriptEventId, tickId, scriptPatchVersion[, regionEpoch]>` so that re-evaluating the same trigger with the same inputs produces the **same sequence of random values**. Components must not call process-wide RNG APIs directly; they receive a scoped RNG instance from the sandbox.
  - Seeds are derived from this tuple primarily so offline replay tools and test harnesses can reproduce behavior for a given event stream; production tick replays never re-enter the DSL for the same `scriptEventId`.
- **Wall-clock time is not exposed** to scripts. DSL components see only **derived game time** sourced from the tick and session model (for example, `tickId`, region-local “world time” counters, or effect durations computed by Game Logic). This ensures that replaying the same tick timeline yields the same time values from the script’s perspective, independent of real-world clock drift.
- Any component that introduces variability must either:
  - be implemented in terms of the seeded RNG and tick-based time described above, or
  - be explicitly documented as **non-replayable** and confined to side channels such as logging and metrics where non-determinism does not affect gameplay state or authoritative decisions.

Under these rules, the combination of Trigger Identity plus tick context (for example `tickId` and `regionEpoch` when applicable) fully determines the observable behavior of a script run that contributes commands to the tick system.

Crucially, **script handlers are not re-executed during tick replay or recovery**. The Automation & Scripting Service evaluates each trigger at most once, produces a set of commands annotated with `scriptEventId`, and hands those commands to the tick system. Tick-level crash recovery and retries reapply those commands idempotently in the Game Session and domain services without re-entering the DSL graph for the same trigger. Determinism for scripting therefore depends on this **“no re-execution per trigger”** guarantee plus the seeded RNG and time constraints.

Script executions are treated as **at-most-once per trigger** at the scheduler level, but the resulting commands participate in the same **idempotent replay model** as other tick actions:

- Script-generated commands must be **idempotent with respect to the region-scoped tick timeline and Trigger Identity**: `(regionEpoch, tickId)` and `scriptEventId`. These identifiers travel with the command payload and are recorded alongside `scriptId` and `tenantId` in `script_event_audit` records and logs so operators can correlate replays and ensure side effects remain consistent even when ticks are retried or a reset bumps `regionEpoch`.
- When commands cause database writes or cross-service calls, domain services should treat `<tenantId, regionId, regionEpoch, tickId, scriptEventId>` as an idempotency token, either directly or via a stable `effectId` derived from it, following the patterns in `design/architecture/system-architecture-transactions.md` and the tick idempotency rules described in `design/architecture/system-architecture-ticks.md#domain-idempotency-rules-region-epoch--tickid-in-postgresql`.
- Conceptually, `scriptEventId` plays the same role for script-originated work that `effectKey` plays in tick-driven effects:
  - For purely tick-driven logic, idempotency guards are keyed by `(tenantId, regionId, regionEpoch, tickId, effectKey)`.
  - For script-originated logic, guards may instead use `(tenantId, regionId, regionEpoch, tickId, scriptEventId)` or `(tenantId, regionId, regionEpoch, tickId, effectKey)` where `effectKey` is derived from `scriptEventId` plus additional context (for example, target entity or aggregate).

---

## Integration with Game Logic & Tick System

- **Scripts do not execute inside the tick system.** The Automation & Scripting Service evaluates scripts independently—on a schedule, via timers, or in response to events—and enqueues the resulting commands into each entity's command queue.
- These queued commands run during the **next tick cycle** via the normal Game Session and Game Logic flow, ensuring deterministic, replayable behavior that follows the tick system's fairness and retry rules.
- Script evaluation never blocks or interferes with tick execution. Scripts can still react to world events, NPC states, or timers provided by the tick system.
- Script-generated commands—like any gameplay command—may fail due to lock contention or target remote regions. These cases are automatically handled by the Game Session Service via standard tick rescheduling and cross-region routing logic.
- The Automation & Scripting Service only determines which commands to inject. It may query world state via gRPC but never mutates entity or world data directly—every action passes through the Game Session Service so tick regions remain consistent.

### Ordering Between Player and Script Commands

- Each entity has a **single authoritative command queue** in Redis (for example, `tick:{tenantRegionTag}:queue:<entityId>`) that contains both player-originated commands and script-generated commands.
- Commands are appended to this queue in the order they are accepted by the Game Session Service. Script-generated commands are accepted via internal gRPC from Automation & Scripting and then enqueued by Game Session using the same tick queue append semantics as player commands. Within a given entity’s queue, commands are therefore processed in **FIFO order**, regardless of whether they came from a player or a script.
- During tick processing, the Game Session Service:
  - Reads at most one command per entity per tick from this combined queue.
  - Applies its existing fairness and conflict-resolution rules (as described in the tick architecture) when deciding which entities to service on a given tick.
- Script-generated commands carry `scriptEventId`, `scriptId`, and (when applicable) upstream ordering tokens such as `tickId` from custom events. Combined with the per-entity FIFO queue and the monotonic `tickId` stream, this ensures that:
  - The order in which commands affect an entity is deterministic for a given event stream and configuration.
  - Automation ticks cannot “jump ahead of” or reorder already-queued player commands for the same entity; they simply contribute additional commands into the same ordered queue that ticks consume.

### Redis Key Summary for Scripting

The main Redis keys used by the Automation & Scripting Service are:

| Key pattern | Owner / service | Purpose | Hash tag / shard scope | TTL / retention expectations |
| --- | --- | --- | --- | --- |
| `automation:queue:<tenantId>:<entityId>` | Automation & Scripting | Per-tenant, per-entity queue of post-DSL script work item *indexes* awaiting automation ticks. | Single-key queue per entity; automation ticks drain these and hand off resulting commands to Game Session for enqueue into tick queues. | Reset-tolerant, best-effort derived index; authoritative pending work items are persisted durably in PostgreSQL (outbox) so this queue can be rebuilt. Loss/reset must be observable (metrics + `script_event_audit`). Any TTL is a short safety valve, not long-term storage. |
| `automation:tick:{tenantScriptTag}:lock` | Automation & Scripting (`ScriptTickService`) | Per-script automation tick lock to serialize staging for a script’s work batch. | Hash-tagged on `{tenantScriptTag}` so multi-key operations remain shard-local. | Short-lived lock; lifetime bounded by a single automation tick batch and its retry window. |
| `automation:tick:{tenantScriptTag}:queue` | Automation & Scripting (`ScriptTickService`) | Staging queue for batched script events before they are written into per-entity tick queues. | Hash-tagged on `{tenantScriptTag}` so staging, draining, and metrics are shard-local. | Short-lived staging; drained quickly by automation ticks. |
| `automation:timer:{tenantRegionTag}` | Automation & Scripting scheduler | Region-scoped index of script timers and intervals (`onTimerExpire`, `onInterval`, `intervalTicks`). | Hash-tagged on `{tenantRegionTag}` to align with tick-region keys. | Persistent while timers are active; entries are added and removed as timers are created and satisfied. |
| `script-leader:{<tenantId>}` | Automation & Scripting scheduler | Leadership lease for scheduler coordination per tenant. | Hash-tagged per tenant. | Short-lived lease refreshed by the active scheduler instance. |

For additional details and any new key patterns, see the Automation & Scripting Service README and Redis design docs.

---

## Script Timers vs Tick Timers

Script timers are layered on top of the core tick model and always express cadence in terms of the **authoritative game tick timeline**, not raw wall-clock seconds:

- Cadence for `onInterval` and other tick-based timers is configured in **ticks** (for example, “every N ticks”). Internally, schedulers may derive wall-clock hints from tick heartbeat streams, but the public contract is expressed in game ticks.
- Missed firings are handled in a **bounded, deterministic way**:
  - When leaders change, the new leader walks forward from its last persisted `tickId` to the current `tickId` and enqueues at most **one synthetic firing** for each cadence boundary crossed in that gap (see [End-to-End `onInterval` Timer Lifecycle](#end-to-end-oninterval-timer-lifecycle)).
  - Missed firings due to quotas, budgets, disabled scripts, or failed/unknown versions are **not replayed later**; they are recorded in `script_event_audit` and associated metrics as dropped or skipped triggers.

Within that model:

- The Game Session Service owns **authoritative tick progression** and tick timers, as described in `design/architecture/system-architecture-ticks.md`.
- The Automation & Scripting Service owns **script timers and intervals**, which are scheduled against tick heartbeat information but do not own ticks themselves.
- Scheduler data structures such as `automation:timer:{tenantRegionTag}` (see the service README and tick docs) are used to track when script timers should fire relative to tick progression; durable script schedules and quotas live in the Automation & Scripting Service’s PostgreSQL schema, while Redis indexes are coordination structures that can be rebuilt or reset without changing which scripts should eventually run.

From the tick system’s perspective, script timers are just another source of work that ultimately enqueues commands into tick queues. The determinism rules in this document apply equally to timer-driven triggers.

---

## End-to-End `onInterval` Timer Lifecycle

This section summarizes how a single `onInterval` timer behaves across normal operation, leader changes, and script reloads, and which Redis keys are authoritative at each step.

- **Normal operation**
  - When an NPC spawns or a script is first loaded, the scheduler creates or updates an interval entry for the `<tenantId, scriptId, entityId>` tuple in the region-scoped timer index under `automation:timer:{tenantRegionTag}`. That entry stores at least the configured cadence (for example, `intervalTicks` or equivalent) and the next due point (`nextTick` or `nextRunAt`). If a per-script index is enabled, a corresponding projection entry may be written under `automation:script:{tenantScriptTag}:timer`, but the region index remains authoritative.
  - Leaders track these interval entries alongside other automation timers, using bounded scans and the automation tick budget (for example, `AUTOMATION_TICK_DURATION_MS`, `AUTOMATION_TICK_MAX_EVENTS`, `AUTOMATION_TICK_BUDGET_MS`) to decide which `onInterval` triggers should fire in each automation tick.
  - When an interval becomes due, the scheduler creates a `scriptEventId` for the `onInterval` trigger and evaluates it using the same quota, cadence, and budgeting layers described in `design/architecture/system-architecture-scripting-quotas-and-operations.md`. If the script is outside its budgets or disabled, the trigger is skipped and recorded in both metrics and the audit feed; otherwise it is enqueued for sandbox execution and the interval entry’s next due point is advanced.

- **Leader changes**
  - Leaders advance a per-region notion of time by consuming the tick heartbeat stream and tracking how far they have progressed. In addition to the current heartbeat `(regionEpoch, tickId)`, schedulers maintain a region-scoped checkpoint such as `script-scheduler:{tenantRegionTag}:lastTickId` (see the tick and Redis design docs).
  - When leadership changes, the new leader:
    - reads `script-scheduler:{tenantRegionTag}:lastTickId` for each region it owns, interpreted in the context of the current `regionEpoch`,
    - walks forward from `lastTickId` to the current `tickId` using the heartbeat stream for that epoch, and
    - for each timer entry in the region index `automation:timer:{tenantRegionTag}`, determines which “every N ticks” boundaries were crossed during the gap. Any missed `onInterval` triggers are enqueued at most once before the leader resumes normal scheduling from the latest `(regionEpoch, tickId)`. If a per-script index is used, it is reconciled against the region index as needed; discrepancies are treated as projection bugs and corrected, not as new timers.
  - Because the authoritative schedule configuration lives in PostgreSQL and Redis holds only coordination state (timer indexes and checkpoints), leader changes do not reset cadences; they only introduce a bounded delay before the new leader catches up.

- **Script reload**
  - During reload, leaders set `reloadState=RELOADING` for the affected `<tenantId, pendingPatchVersion>` and pause new triggers, including `onInterval` firings, while they load and validate the new script definitions. Existing timer entries in the region index `automation:timer:{tenantRegionTag}` (and any derived per-script projections) remain in Redis but are treated as **pending**.
  - Once reload succeeds and `activePatchVersion` is switched, the leader:
    - re-reads its heartbeat checkpoint and the current `tickId`,
    - updates each interval entry’s next due point (`nextTick` or `nextRunAt`) in the region index as needed so the cadence resumes from the latest tick/time (rather than replaying the paused window), and
    - resumes normal scheduling for `onInterval` using the updated `activePatchVersion`. No interval runs against a partially loaded script definition.
  - If reload fails, `activePatchVersion` remains unchanged, `pendingPatchVersion` is marked failed, and the leader resumes using the existing region-index timer entries as-is. Any `onInterval` triggers that fire after a failed reload are still scheduled according to the stored cadence, but always execute under the last known good patch version.

Under this model, durable script schedules and quotas live in PostgreSQL, while `automation:timer:{tenantRegionTag}`, `script-scheduler:{tenantRegionTag}:lastTickId`, and related coordination keys form a reset-tolerant coordination layer for interval state. The combination of tick heartbeat, checkpoints, and script patch versioning preserves both correctness and determinism across failures and leader changes: losing or resetting these Redis keys may delay or slightly reshuffle timer firings within the tail-loss envelope but must not change which scripts are eventually scheduled according to their stored configurations.

---

## Scheduler Leadership & Coordination

Scheduler leadership and coordination ensure that script timers and automation ticks are processed safely in a distributed environment:

- Leadership is tracked using Redis keys such as `script-leader:{<tenantId>}` and leases described in the tick and automation docs.
- Only the **current leader** for a tenant processes that tenant’s script timers and automation queues.
- Automation ticks coordinate with tick heartbeat streams to ensure that:
  - `onInterval` triggers fire on the correct tick boundaries.
  - Per-tenant and per-script quotas are respected.
  - Work is sharded in a way that keeps multi-key operations hash-tag-local.

See:

- `design/architecture/system-architecture-ticks.md`
- `design/architecture/microservices/automation-scripting-service/README.md`

for the current leadership and sharding model.

---

## Hot Reload & Resume Behavior

- Scripts are versioned and published via the Game Design Service; the Game Session Service pins an active `scriptPatchVersion` per game.
- When a new script patch is published, the Automation & Scripting Service:
  - loads the new definitions and validates them,
  - updates bindings and metadata, and
  - transitions scripts through reload states (for example, `reloadState=RELOADING`) to avoid partial visibility.
- During reloads, triggers are **paused or skipped** while in-flight runs drain under existing concurrency settings:
  - New triggers for the affected tenant are not admitted; attempts to schedule additional runs receive explicit backpressure outcomes (`skipped_reloading` during reload, `skipped_rollback_pause` during rollback pause). For low-rate external events, callers may retry with the same `scriptEventId` using bounded backoff (`maxAttempts`, `maxElapsedMs`, jitter) and should honor server retry hints such as `retryAfterMs`; audit records must remain keyed by Trigger Identity and must not multiply rows per retry.
  - In-flight runs remain bounded by each script’s configured `maxConcurrent` and `concurrencyPolicy` (for example, `queue_until_free`); any short per-script waiting queues are allowed to drain, but no new entries are added while `reloadState=RELOADING`.
  - Pending timer-based triggers that became due during reload remain in the scheduler’s timer indexes and resume after reload with recalculated `nextTick` / `nextRunAt` so cadences remain coherent.
- On success, the new `scriptPatchVersion` becomes active for future triggers; on failure:
  - `activePatchVersion` remains unchanged and continues to govern live execution.
  - `pendingPatchVersion` is marked failed along with an error reason, and leaders discard any partially loaded state for that patch and resume scheduling using the existing `activePatchVersion`.
  - Triggers referencing a failed or unknown patch are rejected explicitly with `finalOutcome=version_unavailable` (with specific cause in `finalReason`) and metrics like `automation_script_triggers_dropped_total{reason="version_unavailable"}` rather than silently falling back to an older patch.

- **Timer-based triggers** such as `onInterval` and `onTimerExpire` always execute against the **currently pinned `scriptPatchVersion`** for the game at the moment they are evaluated; they do not continue running older definitions after a patch is promoted.
- **Older script versions** remain in the Automation & Scripting Service database for auditing and potential rollback, but only the **pinned active version** is used for live execution.

---

## Failure Modes and Error Handling

Script executions are treated as **at-most-once per trigger**. Combined with quotas and circuit breakers (covered in `design/architecture/system-architecture-scripting-quotas-and-operations.md`), this ensures that misbehaving scripts cannot hot-loop or consume unbounded resources.

Common outcome classes include:

- `success` – script ran and enqueued commands.
- `quota_denied` – `ScriptQuotaService` limit exceeded before execution.
- `sandbox_error` – exception in the sandboxed DSL runtime or validation failure.
- `validation_error` – static validation on inputs or script configuration failed.
- `disabled_due_to_errors` – failure-rate circuit breaker opened for the script.
- `version_unavailable` – trigger referenced a script patch version that failed reload or is unknown.
- `infrastructure_error` – transient infrastructure issues such as gRPC `UNAVAILABLE` or Redis timeouts.
- `validation_error` with `finalReason=unsafe_component` – script was refused because it depends on a DSL component version marked `UNSAFE`.

Outcome taxonomy must distinguish “DSL evaluated” from “commands were accepted into the tick system”. Do not record `success` for a trigger if the resulting commands were not accepted into the tick queues.

For audit records, outcomes are **stage-aware** (admission vs DSL evaluation vs work-item persistence vs tick handoff) and are recorded as `finalStage` + `finalOutcome` / `finalReason` (and optionally a per-stage breakdown) as specified in `design/architecture/system-architecture-scripting-observability-contract.md`.

Retry behavior:

- Logical failures (`sandbox_error`, `validation_error`, `quota_denied`, `disabled_due_to_errors`, `version_unavailable`) are treated as **final** for a trigger; the scheduler does not re-run the script body for the same `scriptEventId`.
- Backpressure outcomes (for example `finalOutcome=skipped_reloading` or `finalOutcome=skipped_rollback_pause` at `finalStage=ADMISSION`) are not treated as final for low-rate external events; callers may retry the same Trigger Identity until admitted or until their bounded retry policy expires.
- Infrastructure errors **may be retried** by lower layers following platform-wide retry policies and idempotency contracts, but those retries operate only on idempotent downstream operations, not on the DSL body.

When script components call other services over gRPC, they must pass a stable idempotency key derived from Trigger Identity plus tick context when applicable (for example including `entityId`, `eventType`, `scriptPatchVersion`, `scriptEventId`, and `tickId`/`regionEpoch`) and rely on the transaction strategies in `design/architecture/system-architecture-transactions.md` so those downstream operations can be safely retried without duplicating effects.

## Version Fencing and Rollback Safety

Rollback of a script patch must not allow previously queued work from the rolled-back patch to continue affecting gameplay.

To achieve this:

- Script work items and tick commands carry the effective `scriptPatchVersion` used to produce them.
- Game Session enforces a version fence at execution time: commands whose `scriptPatchVersion` does not match the game instance’s currently pinned value are rejected and recorded for audit/diagnosis.
- Operational rollback flows include a drain/purge step for queued automation work items and staging entries that cannot satisfy the version fence.

See `design/architecture/system-architecture-scripting-contracts.md#3-version-fencing-rollback-safety` for the non-negotiable contract.

### Timer Failure Semantics

Timer-based triggers such as `onInterval` and `onTimerExpire` are subject to the same **at-most-once per trigger** semantics as other events:

- When a timer becomes due, the scheduler attempts to admit the corresponding trigger subject to per-script quotas, per-tenant budgets, cluster ceilings, and automation tick budgets.
- If a timer trigger is skipped because of quotas or budgets (for example, tenant budget exhaustion or cluster ceilings), or because the associated patch is `FAILED` or `version_unavailable`, the scheduler records the skip in `script_event_audit` with `finalStage=ADMISSION`, a canonical `finalOutcome` (for example, `quota_denied`, `tenant_budget_exceeded`, `version_unavailable`), and an explicit `finalReason`, and updates the corresponding metrics. The skipped firing is **not automatically re-run later**, although subsequent firings based on the cadence may still occur.
- If a timer trigger fails with `infrastructure_error` (for example, Redis timeouts, gRPC transport failures) after admission, the DSL body is not re-executed for the same `scriptEventId`. Downstream operations may be retried in an idempotent fashion as part of infrastructure-level retries, but the engine does not replay the handler logic.
- The scheduler’s responsibility is to *attempt* to fire timers that fit within configured budgets and capacity; there is no guarantee of eventual execution for every individual interval or timer firing.

Operators and designers should rely on automation metrics and `script_event_audit` entries to detect missed or heavily throttled timers and adjust cadence, budgets, or script design accordingly.

---

### `onLoad` Semantics and Failure Handling

The `onLoad` lifecycle event is used to initialize shared script state for scripts in a given `<tenantId, scriptPatchVersion>` before that patch becomes active:

- `onLoad` handlers run **after** static validation and compilation succeed, but **before** the patch is marked `READY` for a tenant and before the Game Session Service pins it as the active `scriptPatchVersion` for any game in that tenant.
- Each `onLoad` execution is keyed by `<tenantId, scriptId, scriptPatchVersion>` and is treated as at-most-once; repeated attempts (for example, after transient infrastructure errors) must be idempotent.
- Handlers are expected to:
  - Use idempotent operations and stable idempotency keys when calling downstream services, following patterns from `design/architecture/system-architecture-transactions.md`.
  - Avoid irreversible side effects that cannot be safely retried or compensated.

Failure handling:

- If `onLoad` completes successfully for a tenant, the Automation & Scripting Service may mark the patch as `READY` for that tenant and allow it to become the `activePatchVersion` during the next reload transition.
- If `onLoad` fails with a logical or sandbox-level error (for example, misconfiguration, quota denial, sandbox guard), the patch is marked `FAILED` for that tenant:
  - The previous `activePatchVersion` remains in use for live execution.
  - Events referencing the failed patch are rejected explicitly at admission with `script_event_audit.finalStage=ADMISSION`, `finalOutcome=version_unavailable` (or a more specific variant such as `onload_failed`), and corresponding metrics.
  - No automatic retries of the `onLoad` handler occur; an operator or designer must fix the underlying configuration and republish.
- If `onLoad` fails with `infrastructure_error`, the service may optionally retry the initialization a bounded number of times using the same `scriptEventId` and idempotent operations. If retries are exhausted, the patch is treated as `FAILED` for that tenant as above.

All `onLoad` runs are recorded in `script_event_audit` with `eventType=onLoad`, the target `scriptPatchVersion`, and their final stage-aware outcome (`finalStage`, `finalOutcome`, `finalReason`), so operators can verify initialization state for each patch and tenant.

Patch-level readiness for `<tenantId, scriptPatchVersion>` is derived from the aggregate of per-script `onLoad` runs: a patch becomes `READY` for a tenant only after all `onLoad` handlers for scripts in that patch have completed successfully, and any script-level `onLoad` failure leaves the patch in a `FAILED` state with the previous `activePatchVersion` remaining in use.
