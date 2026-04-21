# FireMUD System Architecture: Scripting DSL Reference & Event Lifecycle

This document is the **canonical reference** for the scripting DSL’s terminology, lifecycle states, semantics, determinism rules, and author-facing behavior. It is intended for implementers and backend developers integrating with the Automation & Scripting Service, Tick System, and related infrastructure. Runtime execution flow, outbox behavior, Redis/runtime integration, and execution-state ownership now live in the sibling document `design/architecture/system-architecture-scripting-runtime-execution.md`; scheduler/timer leadership remains in `design/architecture/system-architecture-scripting-scheduler-and-timers.md`. For sandbox enforcement details (CPU, time, and memory budgets, and how failures surface in `script_event_audit`), pair these docs with `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`, which is the canonical spec for the sandbox engine itself.

Routing note:

- Use this document for DSL shape, authoring-time lifecycle, and what constitutes a valid published scripting artifact.
- Use `design/architecture/system-architecture-scripting-runtime-execution.md` for runtime admission, execution-state, and tick-time behavior.
- Use `design/architecture/system-architecture-scripting-quotas-and-operations.md` for quota enforcement and operator-facing runtime controls.

Document conflict resolution order is defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#document-precedence-normative`. This document provides DSL/runtime semantics and must align with higher-precedence contract documents.

It is a companion to:

- `design/architecture/system-architecture-scripting.md` – hub for the overall scripting and automation framework.
- `design/architecture/system-architecture-scripting-dsl-for-designers.md` – designer-oriented overview of the DSL and visual editor.
- `design/architecture/system-architecture-scripting-quotas-and-operations.md` – quotas, circuit breakers, and operational behavior.
- `design/architecture/system-architecture-ticks.md` – tick model, idempotency rules, and replay semantics.
- `design/architecture/system-architecture-transactions.md` – transaction patterns and idempotent downstream operations.
- `design/architecture/system-architecture-versioning-runtime.md` – script-only patch versions and runtime configuration.
- `design/architecture/system-architecture-scripting-runtime-execution.md` – runtime execution flow, outbox behavior, Redis/runtime integration, and execution-state ownership.
- `design/architecture/microservices/automation-scripting-service/README.md` – service-level design and implementation details.

For a higher-level routing guide to all scripting and automation docs, see the **Who Should Read What** and **Where to Find Details** sections in `design/architecture/system-architecture-scripting.md`.

## Table of Contents

- [Audience](#audience)
- [Terminology Glossary](#terminology-glossary)
- [Versioning Terms](#versioning-terms)
- [Script Execution Lifecycle](#script-execution-lifecycle)
- [Work Item Outbox Contract](#work-item-outbox-contract-normative)
- [`scriptEventId` Lifecycle and Deduplication](#scripteventid-lifecycle-and-deduplication)
- [Supported Script Events](#supported-script-events)
- [Event Fan-Out and Handler Ordering](#event-fan-out-and-handler-ordering)
- [Custom and Service-Specific Events](#custom-and-service-specific-events)
- [Scripting DSL Semantics](#scripting-dsl-semantics)
- [Deployment & Versioning](#deployment--versioning)
- [Determinism & Allowed Non-Determinism](#determinism--allowed-non-determinism)
- [Integration with Game Logic & Tick System](#integration-with-game-logic--tick-system)
- [Related Runtime Lifecycle Contracts](#related-runtime-lifecycle-contracts)
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
- **Automation/script tick** – a batching cycle inside the Automation & Scripting Service. `ScriptTickService` drains **script work items** from Redis-backed queues such as `automation:queue:{tenantInstanceTag}:<entityId>`, stages them under `automation:tick:{tenantInstanceScriptTag}:...`, and hands the resulting commands to the Game Session Service so Game Session can enqueue **tick commands** into per-entity tick queues for later execution by game ticks. Automation ticks control script-side quotas and batching, not authoritative game state.
- **Automation queue** – an instance-aware, per-entity Redis queue (`automation:queue:{tenantInstanceTag}:<entityId>`) that holds **derived work-item indexes/pointers** after sandboxed DSL execution and durable persistence. It is reset-tolerant and rebuildable from the durable outbox; it must not be treated as an authoritative log of pending work.
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
| 3 | **Script work item** | A post-DSL, per-entity descriptor of what should happen (domain commands + `scriptEventId`, `scriptId`, version metadata) persisted durably (outbox). | Indexed via `automation:queue:{tenantInstanceTag}:<entityId>` and staged under `automation:tick:{tenantInstanceScriptTag}:...`. |
| 4 | **Tick command** | A concrete command that the Game Session Service executes during game ticks under its normal locking and idempotency rules. | Enqueued into `tick:{tenantRegionTag}:queue:<entityId>` for consumption by the tick loop. |

Triggers lead to DSL runs, which produce script work items in the automation queues, which automation ticks turn into tick commands for the Game Session Service.

---

## Work Item Outbox Contract (Normative)

The authoritative outbox, queue-pointer contract, and drain/handoff semantics now live in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#work-item-outbox-contract-normative). This DSL reference keeps the anchor so existing readers can jump to the runtime owner without losing the lifecycle overview.

---

## `scriptEventId` Lifecycle and Deduplication

`scriptEventId` remains the canonical identifier for a single script trigger/run, but the runtime ownership, downstream propagation, and deduplication contract now live in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#scripteventid-lifecycle-and-deduplication). Use this DSL reference for event meaning and author-facing lifecycle, and the runtime doc for queueing, handoff, and idempotent replay behavior.

---

## Supported Script Events

The DSL supports a variety of **built-in lifecycle events** and **custom events**. The exact set of events and their payload schemas are defined in the Automation & Scripting Service and domain service contracts; this section summarizes the main categories and how they behave.

- **Script lifecycle events**
  - `onLoad` is a **script-level lifecycle event** that runs once per `<tenantId, scriptId, scriptPatchVersion>` while that patch is becoming tenant-`READY`. In the first implementation slice it is limited to ephemeral readiness work (for example, validating configuration and warming recomputable in-process caches) rather than per-entity setup or durable shared-state creation.

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
  - Tenant readiness allows only **one pending patch per tenant** at a time. If Game Design publishes a newer patch while an older patch is still `PENDING_VALIDATION` or `ONLOAD_RUNNING`, the older patch is transitioned deterministically to `SUPERSEDED` with a bounded reason such as `superseded_by_newer_patch`, any not-yet-started `onLoad` work for that older patch is canceled, and any in-flight `onLoad` executions for it must be prevented from later advancing the patch to `READY`.
  - A `SUPERSEDED` patch is terminal for readiness purposes: it remains queryable for audit/history, but it is no longer eligible for pinning and must not emit further `onLoad` work after the superseding publish is accepted.
- Each `onLoad` trigger uses the tenant-readiness identity defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields`: `<tenantId, scriptId, scriptPatchVersion, eventType=onLoad, scriptEventId, isDryRun=false>`, with no `gameInstanceId`, `regionId`, `regionEpoch`, or `entityId`. Automation & Scripting generates `scriptEventId` deterministically from that tuple and reuses it for bounded infrastructure retries.
- Each `onLoad` trigger is recorded in `script_event_audit` with `eventType=onLoad`, `tenantId`, `scriptId`, the target `scriptPatchVersion`, and stage-aware outcome fields (`finalStage`, `finalOutcome`, `finalReason`, plus any per-stage breakdown) so operators can verify that initialization ran for a given script and patch and see exactly where it failed. Because `onLoad` must not persist gameplay effects or hand off tick commands, successful readiness uses `finalStage=DSL_EVAL`, `finalOutcome=readiness_success`, and is separately reflected in patch lifecycle state (`READY`) rather than by live `finalOutcome=success`.

Concrete supersession example:

- Patch `P21` is published for tenant `T1` and enters `ONLOAD_RUNNING`.
- Before all `P21` `onLoad` handlers finish, Game Design publishes `P22` for the same tenant and the publish is accepted for readiness ingestion.
- Automation & Scripting transitions `P21` to `SUPERSEDED` with `statusReason=superseded_by_newer_patch` and records `supersededByScriptPatchVersion=P22` in patch-status surfaces.
- Any not-yet-started `onLoad` work for `P21` is canceled. If an already-running `P21` `onLoad` handler finishes later, that completion may be recorded in audit history for its own Trigger Identity but must not advance patch `P21` back to `READY`.
- Only `P22` remains eligible to progress through `ONLOAD_RUNNING` to `READY`.

### `scheduleDefinitionId` Reconciliation Example

Implementers should treat `scheduleDefinitionId` as the canonical answer to "is this the same logical schedule?":

- If patch `P21` contains a patrol interval compiled to `scheduleDefinitionId=patrol.main.v1` and patch `P22` keeps the same logical timer while only changing unrelated dialogue nodes, the scheduler preserves that timer row and its due state across the patch transition.
- If patch `P22` instead changes the patrol logic into a distinct combat-alert timer compiled to `scheduleDefinitionId=patrol.alert.v1`, the previous timer row is tombstoned and a new timer row is created with fresh due state.
- Rollback uses the same rule. A timer is preserved only when the rollback target exposes the same `scheduleDefinitionId`; otherwise rollback must recreate the old logical schedule rather than trying to reinterpret the newer timer row.

---

## Event Fan-Out and Handler Ordering

An entity may have **multiple handlers bound to the same event**, including both core scripts and plugin handlers. The Game Design and plugin registries store these bindings as ordered lists per `{entityId, eventType}`.

When an event fires, the Automation & Scripting Service evaluates bound handlers in a **single deterministic order** sorted by:

1. `orderIndex ASC`
2. `handlerType ASC` (`SCRIPT` before `PLUGIN` unless policy overrides are explicitly configured and documented)
3. `handlerId ASC` (`scriptId` for core scripts, `(pluginId, bindingId)` for plugin bindings)

This ordering is stable across deployments so that the same binding set produces the same command sequence for a given event.

Failures are isolated per handler by default. If one handler fails (for example, quota denial, sandbox exception, or compilation error), the scheduler records the failure and continues to the next handler unless the failing binding is marked as requiring exclusive handling. Exclusive handling (`requiresExclusiveEvent=true`) short-circuits remaining handlers regardless of whether they are scripts or plugins. Quota checks (`ScriptQuotaService`) remain per handler either way.

Ingress admission and handler execution are intentionally distinct:

- A single inbound event-ingress request (for example `TriggerScriptEvent`) receives exactly one **event-scope** admission decision that covers only ingress-time fences such as auth, pin visibility, patch/plugin availability, and rollback/reload backpressure.
- Once the request is accepted for handler resolution, the Automation & Scripting Service creates zero or more **handler-scoped** Trigger Identities, one per resolved `<scriptId>` or plugin binding. Plugin handler identities must include `pluginId`, `pluginVersionId`, and `bindingId` because one plugin version can contribute multiple handlers to the same event. These handler-scoped identities are the units used for dedupe, `script_event_audit`, quotas, and downstream execution.
- An event-scope `admitted=true` result therefore does **not** mean every resolved handler will succeed. Some handlers may still end with `quota_denied`, `sandbox_error`, `script_disabled`, or exclusivity-policy outcomes while sibling handlers succeed.
- Caller retries are defined only by the event-scope admission result. Per-handler outcomes are observed asynchronously via `script_event_audit` rows and related status/event surfaces, not by reinterpreting the unary ingress response.

Governance requirements for ordering overrides and exclusivity:

- Ordering policy overrides (`PLUGIN` ahead of `SCRIPT`) are operator-controlled policy, not designer-level script metadata.
- At most one binding per `{tenantId, gameInstanceId, entityId, eventType}` may set `requiresExclusiveEvent=true`; conflicting bindings must be rejected at publish/enable time with deterministic validation errors.
- Plugin bindings are non-exclusive by default. Granting plugin exclusivity requires explicit operator allowlisting and must be audit-visible.
- Admission records must include explicit, bounded reasons when exclusivity policy blocks a binding so operators can distinguish policy denial from quota/sandbox failures.

---

## Custom and Service-Specific Events

Domain services can define **custom events** that feed into the scripting pipeline:

- The visual DSL exposes event source nodes for any event types enabled for the current game. Under the hood, bindings are stored as `<tenantId, eventTypeKey, eventSchemaVersion, scriptId>`.
- Service-specific events follow the same trigger → DSL run → automation queue → tick command flow as built-in events.
- Event schemas are versioned so scripts can be migrated when payloads change.

Custom events must follow the same determinism and idempotency rules as built-in events; they are keyed by Trigger Identity plus tick context when producing commands (for example including `entityId`, `eventType`, `scriptPatchVersion`, `scriptEventId`, and `tickId`/`regionEpoch` where applicable).

Custom events also require an explicit trust and ownership contract:

- Every custom `eventType` must be registered in a canonical event registry that defines the owning service, payload schema/version, required identity fields, allowed producer principals/services, quota class, replay semantics, snapshot authority, and consistency class.
- Snapshot authority must state whether the producer supplies a `readSnapshotToken`, Automation & Scripting captures the latest committed snapshot at admission, or the event is explicitly non-authoritative and may run without a gameplay snapshot. For authoritative gameplay-affecting events, the registry must identify the source timeline and required token fields, such as `gameInstanceId`, `regionId`, `regionEpoch`, and `tickId` or an equivalent immutable read version.
- Event ingress must authenticate producer identity via the service-to-service auth layer and reject unregistered or unauthorized `eventType` values deterministically at admission.
- `script_event_audit` must record the producing service identity (for example `sourceService`) for custom events so operators can diagnose spoofing, routing errors, and unexpected fan-out.
- The Game Design Service may expose only event types present in this registry for the selected game/runtime scope; an event being "enabled" must be backed by a concrete contract, not just UI configuration.

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
    - On success, marks the version as `PUBLISHED` and calls `NotifyScriptVersionUpdate` so the Automation & Scripting Service starts tenant-readiness ingestion for that patch.
    - On failure, rolls back or marks the publish as `FAILED`, keeping the prior `scriptPatchVersion` as the active one for that game.

- **Automation & Scripting Service**
  - Owns the **compiled graph schema and runtime registry**: it stores compiled DSL graphs, per-tenant script metadata, and runtime flags (`runtimeStatus`, quotas, priorities) in its own database.
  - Enforces **runtime behavior**: sandbox execution, loop safety, per-script and per-tenant quotas (`ScriptQuotaService`), tick integration, and leadership leases over automation ticks.
  - Maintains **auditability and observability** for script execution via the `script_event_audit` feed and automation metrics (for example, `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`).
  - Implements **hot reload and failure handling** for script patches, including `activePatchVersion`, `pendingPatchVersion`, and `reloadState` as described in [Scripting Scheduler and Timer Lifecycle](./system-architecture-scripting-scheduler-and-timers.md#hot-reload--resume-behavior).

Because script definitions are stored in the Automation & Scripting Service database and loaded via `scriptPatchVersion`, designers can roll out script-only updates without redeploying the service binary; the service reloads definitions in place using its versioning and hot-reload flow.

### Runtime Version Behavior

- Script definitions are stored in the **Automation & Scripting Service** database and versioned alongside other game assets. Publishing updates from the Game Design Service is supported.
- Designers can deploy updated scripts without redeploying code. The Automation & Scripting Service retrieves the current live versions as needed.
- Script-only patches create a `scriptPatchVersion` (the logical/API name) tied to a `baseVersionId` so new behaviors can be loaded on the fly. In the Game Session Service database this is persisted as `script_patch_version`. See [System Architecture: Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#script-only-patch-versions) for how these patch versions work.
- The Game Session Service stores the active `scriptPatchVersion` for each running game. When a new patch is published, the Game Design Service calls `NotifyScriptVersionUpdate`, allowing the Automation & Scripting Service to ingest and validate the patch for tenant readiness before any later pin-driven instance reload.
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

Runtime execution must still remain **instance-aware** even though patch readiness is tenant-scoped:

- A tenant-scoped `READY` state means a patch is eligible to be pinned by instances in that tenant; it does not imply that every running instance must pause, reload, or switch together.
- Admission, timer scheduling, rollback pause, convergence checks, and plugin activation must evaluate the effective runtime scope as `<tenantId, gameInstanceId>`, even if implementations batch internal work by tenant.
- If a deployment wants stronger coupling, it must explicitly declare the invariant that all instances in a tenant share one active script patch. Absent that declaration, instance isolation is the normative behavior.
- Instance-scoped runtime state tracks only **pin observation and admission control** for the patch that an instance is trying to run (for example `observedPinnedScriptPatchVersion`, `reloadState`, convergence checkpoints, and rollback pause). It does **not** rerun tenant patch readiness or `onLoad`.
- A single tenant-wide mutable `activePatchVersion` inside Automation & Scripting is therefore not sufficient. The service must keep tenant-scoped patch readiness separate from instance-scoped pin observation and scheduling state.

Side-by-side lifecycle view:

| Concern | Scope | Owner | Canonical states | What advances it |
| --- | --- | --- | --- | --- |
| Patch readiness | `<tenantId, scriptPatchVersion>` | Automation & Scripting | `PENDING_VALIDATION` -> `ONLOAD_RUNNING` -> `READY` / `FAILED`, terminal `SUPERSEDED` | Publish ingestion, static validation, tenant-scoped `onLoad`, newer accepted publish supersession |
| Runtime pin observation | `<tenantId, gameInstanceId, scriptPatchVersion>` | Game Session pin plus Automation observation | Observed previous pin, `reloadState=RELOADING`, observed new pin, `reloadState=FAILED`, rollback pause / convergence checkpoints | `SetPinnedScriptPatchVersion`, `RollbackScriptPatchVersion`, pin-change events, reload reconciliation, rollback orchestration |

Interpretation rules:

- A tenant patch reaching `READY` means only that it is eligible to be pinned by instances in that tenant.
- An instance switching pins does not rerun tenant readiness or `onLoad`; it consumes already-`READY` definitions and reconciles runtime-scoped derived state.
- `SUPERSEDED` exists only on the tenant readiness side. A runtime scope may still be executing an older observed pin while a newer pending patch supersedes an older readiness candidate.

The canonical states are:

- `PENDING_VALIDATION` – the Game Design Service has published a script-only patch version and the Automation & Scripting Service has accepted the compiled graphs and bindings, but `onLoad` initialization has not yet completed for the tenant.
- `ONLOAD_RUNNING` – `onLoad` handlers for scripts in the patch are executing for the tenant. These executions are keyed by `<tenantId, scriptId, scriptPatchVersion>` and must be idempotent.
- `READY` – all `onLoad` handlers for the patch have completed successfully for the tenant. The patch is eligible to become the `activePatchVersion` for games in that tenant, and Game Session may pin it as the current `scriptPatchVersion`.
- `FAILED` – one or more `onLoad` handlers for the patch have failed for the tenant with a logical, sandbox, or infrastructure error after retries are exhausted. The previous instance-observed pin remains in use for running games, and the failed patch is not eligible to be pinned.
- `SUPERSEDED` – a newer publish for the same tenant was accepted while this patch was still non-terminal (`PENDING_VALIDATION` or `ONLOAD_RUNNING`). The superseded patch remains visible for audit/history but is no longer eligible for pinning or further readiness progression.

Typical transitions are:

1. `PENDING_VALIDATION → ONLOAD_RUNNING` when Automation & Scripting begins `onLoad` initialization for the tenant after successfully ingesting a published patch from Game Design. Patches whose publish Saga fails in Game Design (for example, ability schema mismatches) never enter this lifecycle; from Automation’s perspective they do not exist or remain invisible runtime-only.
2. `ONLOAD_RUNNING → READY` when all `onLoad` executions for scripts in the patch succeed for the tenant.
3. `ONLOAD_RUNNING → FAILED` when any `onLoad` execution fails fatally after bounded retries; running instances continue using their previously pinned patch.
4. `PENDING_VALIDATION|ONLOAD_RUNNING → SUPERSEDED` when a newer publish is accepted for the same tenant before the older patch reaches a terminal readiness state. `SUPERSEDED` is terminal and must be emitted before the newer patch begins readiness work.

Per-instance rollout state is tracked separately (for example `PINNED`, `ROLLED_BACK`, `REPINNED`) and is driven by control-plane APIs/events (`SetPinnedScriptPatchVersion`, `RollbackScriptPatchVersion`, `ScriptPatchPinChanged`). An instance rollback does not imply tenant patch state transition away from `READY`.

Automation & Scripting exposes this lifecycle to other services via:

- A read-only API such as `GetScriptPatchStatus(tenantId, scriptPatchVersion)` that returns the current state and relevant timestamps.
- Tenant readiness events (`ScriptPatchTenantStatusChanged`) emitted when `<tenantId, scriptPatchVersion>` transitions between readiness states.
- Instance rollout events (`ScriptPatchInstanceRolloutChanged`) consumed from Game Session pin-change control-plane events and projected into read APIs when `<tenantId, gameInstanceId, scriptPatchVersion>` rollout history changes (for example `PINNED` / `ROLLED_BACK` / `REPINNED`).

When a trigger arrives at the Automation & Scripting Service:

- If the supplied `scriptPatchVersion` is `READY` for the tenant, the service must additionally compare it to a fresh-enough observed pin for `<tenantId, gameInstanceId>`. Admission proceeds only when the request patch matches the observed pinned patch for that instance.
- If pin visibility for the instance is stale beyond its configured max age and fresh control-plane state cannot be obtained, admission fails closed with `pin_state_unavailable` and a bounded reason in the event-scope ingress audit record. If the failure happens after handler resolution, the handler-scoped `script_event_audit` row uses `finalStage=ADMISSION`, `finalOutcome=pin_state_unavailable`.
- If the supplied `scriptPatchVersion` is `READY` for the tenant but does not match the observed pinned patch for `<tenantId, gameInstanceId>`, admission is rejected with `version_unavailable` and a bounded mismatch reason such as `pin_state_mismatch_requested_vs_observed`. Automation & Scripting must not speculate or silently substitute either version.
- If the patch is unknown or in a non-ready state (for example, `PENDING_VALIDATION`, `ONLOAD_RUNNING`, `FAILED`), the trigger is rejected at admission with `version_unavailable` (or a specific bounded reason such as `onload_failed`). Pre-resolution rejections are recorded in ingress audit; handler-scoped rejections use `script_event_audit.finalStage=ADMISSION`. A drop metric like `automation_script_triggers_dropped_total{reason="version_unavailable"}` is incremented.
- Automation & Scripting never silently falls back to an older patch for that trigger; callers must fix the pinned version, repin explicitly, or republish.

---

## Determinism & Allowed Non-Determinism

Scripts are designed to behave **deterministically for a given game configuration and event**, so that both the original execution and any offline replay in tools or tests produce the same observable behavior. The Automation & Scripting Service enforces this by constraining how randomness and time are exposed to DSL components:

- All **pseudo-random behavior** (for example, “pick a random waypoint”, “roll for loot”, or encounter selection) flows through curated components that read from a **seeded RNG** supplied by the runtime. The seed is derived from stable identifiers such as `<tenantId, gameInstanceId, regionId, entityId, scriptId, eventType, scriptEventId, tickId, scriptPatchVersion[, regionEpoch, pluginId, pluginVersionId]>` so that re-evaluating the same trigger with the same inputs produces the **same sequence of random values**. Components must not call process-wide RNG APIs directly; they receive a scoped RNG instance from the sandbox.
  - Seeds are derived from this tuple primarily so offline replay tools and test harnesses can reproduce behavior for a given event stream; production tick replays never re-enter the DSL for the same `scriptEventId`.
- **Wall-clock time is not exposed** to scripts. DSL components see only **derived game time** sourced from the tick and session model (for example, `tickId`, region-local “world time” counters, or effect durations computed by Game Logic). This ensures that replaying the same tick timeline yields the same time values from the script’s perspective, independent of real-world clock drift.
- Any component that introduces variability must either:
  - be implemented in terms of the seeded RNG and tick-based time described above, or
  - be explicitly documented as **non-replayable** and confined to side channels such as logging and metrics where non-determinism does not affect gameplay state or authoritative decisions.

Under these rules, the combination of Trigger Identity plus tick context (for example `tickId` and `regionEpoch` when applicable) fully determines the observable behavior of a script run that contributes commands to the tick system.

### Read Consistency Contract

Determinism depends not only on stable RNG/time, but also on a stable **read snapshot** for all gameplay-affecting data exposed to the DSL:

- Every live handler-scoped run must execute against a runtime-issued **read snapshot token** captured at admission. For gameplay/runtime triggers, that token must be anchored to the committed source timeline for the trigger, including at minimum `<tenantId, gameInstanceId, regionId, regionEpoch>` plus a source consistency point such as `tickId` or an equivalent read version.
- All DSL component reads that influence authoritative branching or emitted commands must use that same snapshot token for the duration of the run. A single run must not silently mix fresher and older committed values for the same gameplay state just because wall-clock time advanced between gRPC reads.
- If the runtime exposes cross-region, tenant-global, or non-tick-owned data to scripts, the component contract must declare the consistency class explicitly. Data that can materially change authoritative gameplay decisions must either:
  - be versioned by the same run snapshot token, or
  - carry its own immutable version/read token captured at admission and reused for the whole run.
- For custom and service-specific events, the event registry is the source of truth for snapshot authority and consistency class. Ingress must reject an event whose payload omits a registry-required snapshot token or whose token scope does not match the required Trigger Identity fields.
- Eventually consistent or best-effort operational views may be exposed only to components whose outputs are non-authoritative (for example logging/diagnostics) or whose contract explicitly states that they do not affect gameplay branching.
- `onLoad` and dry-run/test execution must also declare their snapshot source. In the first implementation slice:
  - `onLoad` may read only configuration/runtime metadata and recomputable caches using a tenant-scoped readiness snapshot, not mutable gameplay state.
  - dry-run/test runs must either accept an explicit snapshot selector from tooling or record the server-chosen latest committed snapshot token in the returned/audited result so the run is reproducible.

This snapshot contract is part of the runtime semantics, not an implementation detail. Services backing DSL read components must therefore accept and honor the snapshot/read-version token required by the component contract.

Concrete transport shape example:

- For a gameplay trigger emitted immediately after tick commit, the ingress payload may carry an opaque `readSnapshotToken` whose decoded contents are equivalent to `<tenantId=T1, gameInstanceId=G7, regionId=R2, regionEpoch=14, tickId=981223>`.
- DSL components that query region-local world state, inventory, or nearby entities must pass that same token on every downstream read call for the lifetime of the run.
- Downstream services may expose the token either as an opaque envelope or as explicit fields, but the semantics are the same: the run sees one committed snapshot and does not silently upgrade to tick `981224` midway through evaluation.

Illustrative transport example:

```protobuf
message TriggerScriptEventRequest {
  string tenant_id = 1;
  string game_instance_id = 2;
  string region_id = 3;
  int64 region_epoch = 4;
  string entity_id = 5;
  string script_patch_version = 6;
  string script_event_id = 7;
  string event_type = 8;
  bytes read_snapshot_token = 9;
}

message GetNearbyEntitiesRequest {
  string tenant_id = 1;
  string game_instance_id = 2;
  string region_id = 3;
  string entity_id = 4;
  bytes read_snapshot_token = 5;
}
```

In this shape, Automation captures `read_snapshot_token` once from ingress and forwards the same byte-for-byte token on every authoritative read made during that handler-scoped run. A downstream service may decode it internally into fields such as `regionEpoch=14` and `tickId=981223`, but the calling contract remains "one run, one committed snapshot."

Crucially, **script handlers are not re-executed during tick replay or recovery**. The Automation & Scripting Service evaluates each trigger at most once, produces a set of commands annotated with `scriptEventId`, and hands those commands to the tick system. Tick-level crash recovery and retries reapply those commands idempotently in the Game Session and domain services without re-entering the DSL graph for the same trigger. Determinism for scripting therefore depends on this **“no re-execution per trigger”** guarantee plus the seeded RNG and time constraints.

Script executions are treated as **at-most-once per trigger** at the scheduler level, but the resulting commands participate in the same **idempotent replay model** as other tick actions:

- Script-generated commands must be **idempotent with respect to the region-scoped tick timeline and Trigger Identity**: `(regionEpoch, tickId)` and `scriptEventId`. These identifiers travel with the command payload and are recorded alongside `scriptId` and `tenantId` in `script_event_audit` records and logs so operators can correlate replays and ensure side effects remain consistent even when ticks are retried or a reset bumps `regionEpoch`.
- When commands cause database writes or cross-service calls, domain services should treat `<tenantId, regionId, regionEpoch, tickId, scriptEventId>` as an idempotency token, either directly or via a stable `effectId` derived from it, following the patterns in `design/architecture/system-architecture-transactions.md` and the tick idempotency rules described in `design/architecture/system-architecture-ticks.md#domain-idempotency-rules-region-epoch--tickid-in-postgresql`.
- Conceptually, `scriptEventId` plays the same role for script-originated work that `effectKey` plays in tick-driven effects:
  - For purely tick-driven logic, idempotency guards are keyed by `(tenantId, regionId, regionEpoch, tickId, effectKey)`.
  - For script-originated logic, guards may instead use `(tenantId, regionId, regionEpoch, tickId, scriptEventId)` or `(tenantId, regionId, regionEpoch, tickId, effectKey)` where `effectKey` is derived from `scriptEventId` plus additional context (for example, target entity or aggregate).

---

## Integration with Game Logic & Tick System

Runtime integration, output budgeting, command ordering, and Redis key ownership now live in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#runtime-execution-flow). This DSL reference keeps the heading as a stable entry point because readers often arrive here first when tracing event semantics into runtime behavior.

---

## Related Runtime Lifecycle Contracts

The detailed timer, scheduler, and reload lifecycle contracts now live in a focused sibling doc:

- [Scripting Scheduler and Timer Lifecycle](./system-architecture-scripting-scheduler-and-timers.md) defines script timers versus tick timers, the timer resume rule, end-to-end `onInterval` lifecycle, scheduler leadership and coordination, and hot reload/resume behavior.

---

## Failure Modes and Error Handling

Runtime failure taxonomy, version fencing, timer failure handling, and `onLoad` retry/rollback behavior now live in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#failure-modes-and-error-handling). The DSL reference keeps the anchor so event/lifecycle readers can still navigate directly into the runtime owner for the operational half of the contract.
