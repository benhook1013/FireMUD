# FireMUD System Architecture: Scripting DSL Reference & Event Lifecycle

This document is the **canonical reference** for the scripting DSL’s terminology, lifecycle states, semantics, determinism rules, and author-facing behavior. It is intended for implementers and backend developers integrating with the Automation & Scripting Service, Tick System, and related infrastructure. Runtime execution flow, outbox behavior, Redis/runtime integration, and execution-state ownership now live in the sibling document `design/architecture/system-architecture-scripting-runtime-execution.md`; scheduler/timer leadership remains in `design/architecture/system-architecture-scripting-scheduler-and-timers.md`. For sandbox enforcement details (CPU, time, and memory budgets, and how failures surface in `script_event_audit`), pair these docs with `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`, which is the canonical spec for the sandbox engine itself.

Routing note:

- Use this document for DSL shape, authoring-time lifecycle, and what constitutes a valid published scripting artifact.
- Use `design/architecture/system-architecture-scripting-runtime-execution.md` for runtime admission, execution-state, and tick-time behavior.
- Use `design/architecture/system-architecture-scripting-quotas-and-operations.md` for quota enforcement and operator-facing runtime controls.

Document conflict resolution order is defined in `design/architecture/system-architecture-scripting-normative-contract-tables.md#document-precedence-normative`. This document provides DSL/runtime semantics and must align with higher-precedence contract documents.

The unified DSL and embedded/plugin lifecycle boundary is the accepted contract in [ADR 0111](./decisions/adr-0111-unified-dsl-with-distinct-embedded-script-and-plugin-lifecycles.md). Pin/epoch and transition behavior links to [ADR 0103](./decisions/adr-0103-single-authority-script-pins-with-exact-version-execution.md), [ADR 0106](./decisions/adr-0106-epoch-fenced-script-rollback-without-routine-gameplay-pause.md), [ADR 0108](./decisions/adr-0108-no-degraded-script-admission-without-authoritative-pin.md), [ADR 0109](./decisions/adr-0109-game-session-owned-script-rollout-history.md), [ADR 0110](./decisions/adr-0110-explicit-opt-in-schedule-continuity-across-script-transitions.md), and [ADR 0107](./decisions/adr-0107-stage-aware-script-dead-letter-recovery.md); these links explain rationale while this document owns the current lifecycle contract.

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

## Implementation Status

This document is target-state canonical for DSL shape and lifecycle semantics. The target pre-DSL trigger lifecycle is `PENDING_EVALUATION` -> `EXECUTING` -> `EVALUATED_COMMITTED`; executor acceptance, the execution-start charge marker, and the fenced capacity lease commit before DSL evaluation. Current runtime boundaries are recorded in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#current-implementation-status): the live handoff supports one target entity, while `targetEntityIds[]` multi-target fan-out remains target-state pending persisted deterministic per-target identity, scope validation, and deduplication. The current Automation claim boundary is `PENDING_EVALUATION` to `EVALUATING`; lease/fencing-generation recovery and the separate evaluated descriptor/outbox boundary remain implementation gaps, while their target behavior is owned by the runtime document. Canonical scripting metrics, labels, audit, and handoff diagnostics are defined by the [Scripting & Automation Observability Contract](./system-architecture-scripting-observability-contract.md).

## Table of Contents

- [Audience](#audience)
- [Implementation Status](#implementation-status)
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

- **`{tenantRegionTag}` hash tag** – an opaque placeholder produced by shared Redis key builders from the complete `<tenantId, gameInstanceId, regionId>` scope documented in [Redis Architecture](./system-architecture-redis.md#key-naming-and-shard-discipline). It exists for shard locality across region-scoped keys; it is not a replacement for those identity fields and does not define a competing schedule or tenant key family. `playableStateScope` is intentionally not part of this existing tag topology; it remains in durable Trigger Identity and command/effect identity and must not be inferred from the tag.
- **`{tenantInstanceTag}` hash tag** – an opaque placeholder produced by shared Redis key builders from `<tenantId, gameInstanceId>`. It scopes instance-wide automation projections and cross-region coordination hints such as `automation:queue:{tenantInstanceTag}:<entityId>` and `remote:{tenantInstanceTag}:<entityId>`; it must not be substituted for the region-scoped `{tenantRegionTag}` where a region timeline or lease fence applies.
- **Game tick** – a region-scoped tick in the Game Session Service. Each `<tenantId, gameInstanceId, regionId>` advances through a monotonic `tickId` stream; game ticks are authoritative for gameplay state changes and use `tick:{tenantRegionTag}:...` keys and locks as described in [Tick System and Runtime Design](./system-architecture-ticks.md).
- **Automation execution loop** – the durable executor path inside the Automation & Scripting Service. It claims persisted **script work items** from the outbox, evaluates the current command-emission format, and hands resulting commands to the Game Session Service so Game Session can enqueue **tick commands** into per-entity tick queues for later execution by game ticks. `automation:queue:{tenantInstanceTag}:<entityId>` remains a reset-tolerant derived pointer index for visibility and rebuildable coordination, not an authoritative execution log.
- **Automation queue** – an instance-aware, per-entity Redis queue (`automation:queue:{tenantInstanceTag}:<entityId>`) that holds **derived work-item indexes/pointers** after sandboxed DSL execution and durable persistence. It is reset-tolerant and rebuildable from the durable outbox; it must not be treated as an authoritative log of pending work.
- **Tick heartbeat** – a **gRPC streaming feed** produced by the Game Session Service that reports `(regionEpoch, tickId)` progression per `<tenantId, gameInstanceId, regionId>`. The script scheduler consumes this heartbeat over a long-lived gRPC stream to count “every N ticks” intervals and align `onInterval` triggers with the canonical game tick timeline without owning tick execution itself. See [Tick Events & Heartbeat Stream](./system-architecture-ticks.md#tick-events--heartbeat-stream) for transport details and the `(regionEpoch, tickId)` coordination timeline.

---

## Versioning Terms

These definitions summarize how common versioning concepts are used in scripting; the full model lives in [System Architecture: Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md). For the per-tenant lifecycle of script patches after publish, see [Script Patch Lifecycle](#script-patch-lifecycle).

- **`scriptPatchVersion`** – a logical script-only patch identifier tracked per tenant and selected by Game Session for an instance. It is one component of the exact `(scriptPatchVersion, scriptPinEpoch)` pin tuple; version alone is not runtime authority for triggers or timers.
- **`scriptPinEpoch`** – the monotonic Game Session-owned selection epoch paired with `scriptPatchVersion` for one `(tenantId, gameInstanceId)`. Every pin change, rollback, and repin-to-the-same-version advances it; exact `(scriptPatchVersion, scriptPinEpoch)` is the runtime execution identity.
- **`versionId`** – an internal identifier for a concrete compiled script or component version. `versionId` values distinguish individual revisions within a `scriptPatchVersion` and are used by the Automation & Scripting Service to load the exact behavior that should run for a given trigger.
- **`runtimeStatus`** – the current runtime state of a script as seen by the scheduler (for example, `ENABLED`, `DISABLE_AFTER_DRAIN`, `DISABLED`, `DISABLED_DUE_TO_ERRORS`). `runtimeStatus` controls whether new triggers are accepted, drained, or skipped and is updated by hot reload flows and administrative actions.

### One DSL, Distinct Artifact and Lifecycle Roles

FireMUD has one component-based DSL, compiler, validator, sandbox, and execution runtime for game-authored automation. An embedded script is a game-owned DSL graph or handler entrypoint materialized in the Game Design revision model and released with an immutable game version or script-only patch; it follows that version's publication, tenant-readiness, pinning, and rollback lifecycle. A linked plugin is an immutable independently versioned bundle containing graphs in the same DSL, bindings, bounded configuration, and optional plugin-owned assets; it retains stable `pluginId` and exact `pluginVersionId` provenance and has its own instance-scoped enable, drain, disable, update, and rollback lifecycle. Plugin activation and updates are not folded into the Game Session script-patch pin tuple: each activation/update resolves a `PUBLISHED`, compatibility- and policy-admitted exact plugin/binding set, and runtime work carries the current Game Session `(scriptPatchVersion, scriptPinEpoch)` plus exact `(pluginId, pluginVersionId, bindingId)` provenance. Distribution source, signatures, marketplace status, or local-file provenance do not create a second language or a weaker sandbox.

Packages containing ordinary base-version DML cannot become a layered runtime plugin: Game Design materializes that content into a Draft and republishes a new game version. Both roles use the same capability grants, quotas, output bounds, dry-run isolation, audit, and domain-command authorization; plugin packaging is not an automatic trust tier.

---

## Script Execution Lifecycle

The scripting pipeline uses a small set of terms repeatedly; the table below summarizes them and how they relate:

The required Trigger Identity fields and target-scope exceptions are defined in the [normative contract tables](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields). The lifecycle table below uses that full applicable identity without redefining it.

| Step | Term | Description | Stored as / example |
| --- | --- | --- | --- |
| 1 | **Trigger** | A concrete event such as `onEnterRegion`, `onCommand`, or a custom event emitted by a service. | gRPC `TriggerScriptEvent` call, tick heartbeat, or internal scheduler event. |
| 2 | **DSL run** | Execution of a script handler in the sandboxed DSL for a single trigger. Produces domain commands, not direct state changes. | In-memory execution in the Automation & Scripting Service; results summarized as script work items. |
| 3 | **Script work item** | The target-state post-DSL, per-trigger evaluated command descriptor/outbox contains one immutable descriptor per emitted command, the applicable Trigger Identity defined in the [normative contract tables](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields), and version metadata. The pre-DSL trigger state is a separate durable role; current implementation boundaries are recorded by the runtime owner. | Indexed via `automation:queue:{tenantInstanceTag}:<entityId>` when the evaluated work targets an entity, and later claimed by Automation's durable executor for Game Session handoff. |
| 4 | **Tick command** | A concrete command that the Game Session Service executes during game ticks under its normal locking and idempotency rules. | Enqueued into `tick:{tenantRegionTag}:queue:<entityId>` for consumption by the tick loop. |

Triggers lead to DSL runs, which produce durable script work items plus queue-pointer projection entries, and Automation's execution loop turns those work items into tick commands for the Game Session Service.

---

## Work Item Outbox Contract (Normative)

The authoritative outbox, queue-pointer contract, and drain/handoff semantics now live in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#work-item-outbox-contract-normative). This DSL reference keeps the anchor so existing readers can jump to the runtime owner without losing the lifecycle overview.

The firing transition leaves one durable handler-level `script_event_audit` row for the applicable Trigger Identity; stage and outcome semantics are defined in the [normative audit table](./system-architecture-scripting-normative-contract-tables.md#table-2-script_event_audit-stages-and-outcomes). Command-level outcomes remain separate from that handler row. Handoff identity and retry rules follow the [cross-service scripting contracts](./system-architecture-scripting-contracts.md#2-script-work-item-vs-tick-command-boundary). Current runtime implementation boundaries, including the live Game Session handoff shape and diagnostic fallback, are recorded in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#current-implementation-status).

---

## `scriptEventId` Lifecycle and Deduplication

`scriptEventId` remains the canonical identifier for a single script trigger/run, but it is only one field of the composite Trigger Identity. The runtime ownership, downstream propagation, and deduplication contract now live in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#scripteventid-lifecycle-and-deduplication). Use this DSL reference for event meaning and author-facing lifecycle, and the runtime doc for queueing, handoff, and idempotent replay behavior.

---

## Supported Script Events

The DSL supports a variety of **built-in lifecycle events** and **custom events**. The exact set of events and their payload schemas are defined in the Automation & Scripting Service and domain service contracts; this section summarizes the main categories and how they behave.

- **Script lifecycle events**
  - `onLoad` is a **script-level lifecycle event** that runs once per canonical readiness identity while that patch is becoming tenant-`READY`.

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
  - These events are admitted under the exact Game Session `(scriptPatchVersion, scriptPinEpoch)` tuple captured in the durable schedule or firing claim; firing never refreshes the event to the latest script configuration.

See the Automation & Scripting Service README and service protos for the full, up-to-date list of event types and schemas.

---

### `onLoad` Semantics

`onLoad` is a **script-level lifecycle event**, not an entity-level event. It runs without an entity context and executes once per script definition and script patch for a tenant, not once per NPC or player.

- **When it fires**
- The Automation readiness worker/workflow enqueues an `onLoad` trigger exactly once per canonical readiness Trigger Identity while that patch is the **pending** patch for the tenant, before it becomes eligible for an explicit instance-scoped exact pin by Game Session. The gameplay scheduler does not own readiness enqueue or recovery. In practice this means:
  - When a script first becomes part of the tenant’s pending script set under a given `scriptPatchVersion` (lifecycle `PENDING_VALIDATION` → `ONLOAD_RUNNING`), and
  - After a successful hot reload that introduces a new pending patch for that tenant, `onLoad` fires once for each script in that pending patch.
  - If reload or validation fails and the patch never reaches `READY`, no Game Session pin changes and no additional `onLoad` events are generated for that patch.

- **Per-script vs per-entity**
  - `onLoad` runs **without an entity context** and uses the tenant-readiness identity defined in the [normative contract tables](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields).
  - Scripts that need per-entity initialization (for example, setting up patrol state when an NPC enters the world) should use `onSpawn`, `onEnterRegion`, or other entity-scoped events instead of relying on `onLoad`.

- **Interaction with reloads and recovery**
  - The Automation & Scripting Service treats `onLoad` as **at-most-once per canonical readiness Trigger Identity**, even across process restarts and readiness-worker leadership changes. Completed or terminalized load state is tracked in persistent metadata so that simply restarting a readiness worker instance does not re-fire a completed execution.
  - `onLoad` triggers are enqueued only while the patch is tracked as `pendingPatchVersion` with lifecycle `ONLOAD_RUNNING`; tenant readiness remains separate from any instance's exact Game Session pin. Scripts never run `onLoad` against a patch that is already pinned for the target instance.
  - `onLoad` may not emit gameplay or tick commands and may not create durable shared effects. It is limited to configuration/runtime metadata validation and ephemeral or trivially recomputable initialization.
  - **Target state only (not implemented/proven in the current runtime):** A stale `ONLOAD_RUNNING` execution is fenced by its publication/readiness generation and terminalized by Automation's recovery owner as an audited `finalStage=DSL_EVAL`, `finalOutcome=canceled`, `finalReason=stale_execution_fenced` result. It blocks `READY`; the same canonical readiness identity is never re-entered. Publication/readiness generations are fencing metadata, not execution identity. After the stale terminalization and audit are durable, retry requires republishing as a new immutable `scriptPatchVersion`; it must not mint a replacement onLoad execution identity for the stale patch.
  - Tenant readiness allows only **one pending patch per tenant** at a time. If Game Design publishes a newer patch while an older patch is still `PENDING_VALIDATION` or `ONLOAD_RUNNING`, the older patch is transitioned deterministically to `SUPERSEDED` with a bounded reason such as `superseded_by_newer_patch`, any not-yet-started `onLoad` work for that older patch is canceled, and any in-flight `onLoad` executions for it must be prevented from later advancing the patch to `READY`.
  - A `SUPERSEDED` patch is terminal for readiness purposes: it remains queryable for audit/history, but it is no longer eligible for pinning and must not emit further `onLoad` work after the superseding publish is accepted.
- The readiness worker persists one execution/audit record per canonical readiness identity. Its deterministic ID generation, retry restriction, and absent runtime fields are defined in the [normative Trigger Identity table](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields). Publication/readiness generation is a separate fencing value, not an execution identity; after stale terminalization, only a newly republished immutable `scriptPatchVersion` may create a new onLoad identity.
- Each `onLoad` run is recorded in `script_event_audit` with `eventType=onLoad`, `tenantId`, `scriptId`, and the target `scriptPatchVersion`; the [normative audit table](./system-architecture-scripting-normative-contract-tables.md#table-2-script_event_audit-stages-and-outcomes) defines its stage-aware outcome. Successful readiness contributes to patch lifecycle state (`READY`) rather than tick handoff.

Concrete supersession example:

- Patch `P21` is published for tenant `7b3b074e-d597-4e9b-b96f-4f5946d26120` and enters `ONLOAD_RUNNING`.
- Before all `P21` `onLoad` handlers finish, Game Design publishes `P22` for the same tenant and the publish is accepted for readiness ingestion.
- Automation & Scripting transitions `P21` to `SUPERSEDED` with `statusReason=superseded_by_newer_patch` and records `supersededByScriptPatchVersion=P22` in patch-status surfaces.
- Any not-yet-started `onLoad` work for `P21` is canceled. If an already-running `P21` `onLoad` handler finishes later, that completion may be recorded in audit history for its own Trigger Identity but must not advance patch `P21` back to `READY`.
- Only `P22` remains eligible to progress through `ONLOAD_RUNNING` to `READY`.

## `scheduleDefinitionId` Reconciliation Example

Implementers should treat explicit continuity declarations plus the stable logical key as the canonical answer to "should this schedule continue?" `scheduleDefinitionId` alone is not sufficient:

- If patch `P21` contains a patrol interval with stable key `{SCRIPT, patrol, patrol.main.v1, ENTITY, guard-9}` and patch `P22` declares compatible continuity for that same key, the scheduler may preserve the row only after validating the prior `(scriptPatchVersion, scriptPinEpoch)` tuple and the exact target tuple, along with typed cadence, interval-kind, target-binding, and playable-scope checks. It rewrites ownership, persists the target patch provenance and exact epoch, and applies the normative resume calculation before admission resumes; continuity does not require source and target tuples to be equal, and `scheduleSemanticsHash` is diagnostic evidence only.
- If either side omits continuity, explicitly resets it, changes the key/cadence/target, or the target schedule is absent, the scheduler retires/fences P21. When no validated target definition exists, it creates no P22 row, due state, firing claim, or `scriptEventId`; when a validated target definition exists on the reset/incompatible path, it creates P22 with fresh due state. A distinct combat-alert timer uses a new key and fresh state.
- Rollback applies exactly the same opt-in matrix. A matching `scheduleDefinitionId` or historical similarity never carries due state by itself, and a one-shot timer is not migrated by this interval rule.

---

## Event Fan-Out and Handler Ordering

An entity may have **multiple handlers bound to the same event**, including both core scripts and plugin handlers. The Game Design and plugin registries store these bindings as ordered lists per `{entityId, eventType}`.

When an event fires, the Automation & Scripting Service evaluates bound handlers in a **single deterministic order** sorted by:

1. `orderIndex ASC`
2. `handlerType ASC` (`SCRIPT` before `PLUGIN` unless policy overrides are explicitly configured and documented)
3. `handlerId ASC` (`scriptId` for core scripts, `(pluginId, bindingId)` for plugin bindings)

This ordering is stable across deployments so that the same binding set produces the same command sequence for a given event.

Failures are isolated per handler by default. If one handler fails (for example, quota denial, sandbox exception, or compilation error), the scheduler records the failure and continues to the next handler unless the failing binding is marked as requiring exclusive handling. Exclusive handling (`requiresExclusiveEvent=true`) short-circuits remaining handlers regardless of whether they are scripts or plugins. Quota checks (`ScriptQuotaService`) remain per handler either way.

Ingress admission and handler execution are intentionally distinct. Endpoint ownership, handler identity, retry, and audit rules are defined in the [normative contract tables](./system-architecture-scripting-normative-contract-tables.md#table-1a-event-ingress-scripteventid-ownership-matrix). Locally, `admitted=true` means only that the request was accepted for handler resolution; per-handler outcomes remain asynchronous in `script_event_audit` and related status/event surfaces.

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

Custom events must follow the same determinism and idempotency rules as built-in events; the applicable identity and ingress requirements are defined in the [normative contract tables](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields).

Custom events also require an explicit trust and ownership contract:

- Every custom `eventType` must be registered in the [canonical event registry](./system-architecture-scripting-event-registry.md), including the owning service, payload schema/version, required identity fields, allowed producer principals/services, quota class, replay semantics, snapshot authority, and consistency class.
- Input authority must state which owner-versioned values and causal floor a handler manifest must contain. A producer may supply an owner-specific `readSnapshotToken`, or Automation may capture the owner read/version at admission, but neither is a universal snapshot authority. For authoritative gameplay-affecting handlers, the registry must identify the source timeline and required owner-version fields, such as `gameInstanceId`, `regionId`, `regionEpoch`, and `tickId` or an equivalent immutable read version. For a registry entry explicitly declared `snapshotAuthority=NONE`, the manifest may omit gameplay snapshot inputs, but handlers for that event cannot perform authoritative snapshot-dependent reads or outputs.
- Event ingress must authenticate producer identity via the service-to-service auth layer and reject unregistered or unauthorized `eventType` values deterministically at admission. The canonical `sourceService` is derived from that authenticated producer/workload identity and is not caller-selected.
- `sourceService` participates in event-scope dedupe and the same derived value must be persisted unchanged in `script_event_ingress_audit` and each resolved-handler `script_event_audit` record for custom events, so operators can diagnose spoofing, routing errors, and unexpected fan-out.
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
  - Drives a **publication and readiness workflow** when a script version is published:
    - Writes the final, validated script graphs and bindings into its own tables.
    - Validates that referenced runtime assets such as abilities and actions are compatible with the target game version; if mismatches are detected, publication fails and the affected `scriptPatchVersion` is never eligible to become `READY` for that tenant.
    - Publishes the immutable design-time patch and calls `NotifyScriptVersionUpdate` so Automation & Scripting ingests the compiled definitions, event bindings, and any world-generation hooks for the target `<tenantId, scriptPatchVersion>`.
    - Automation & Scripting starts or reuses the durable Temporal `script-patch-readiness` workflow, which tracks validation and `onLoad` processing to terminal readiness without conflating publication with runtime admission.
  - On readiness failure, the patch remains published but ineligible for runtime use, and the prior exact Game Session pin remains in use for the game.

- **Automation & Scripting Service**
  - Owns the **compiled graph schema and runtime registry**: it stores compiled DSL graphs, per-tenant script metadata, and runtime flags (`runtimeStatus`, quotas, priorities) in its own database.
  - Enforces **runtime behavior**: sandbox execution, loop safety, per-script and per-tenant quotas (`ScriptQuotaService`), durable work-item execution, and scheduler/timer leadership.
  - Maintains **auditability and observability** for script execution via the `script_event_audit` feed and automation metrics (for example, `automation_script_triggers_total`, `automation_script_skips_total`, `automation_script_triggers_dropped_total`, `script_quota_allowed_total`, `script_quota_denied_total`).
  - Implements hot reload and failure handling for exact instance pin/epoch transitions, including `pendingPatchVersion` and `reloadState` as described in [Scripting Scheduler and Timer Lifecycle](./system-architecture-scripting-scheduler-and-timers.md#hot-reload--resume-behavior). Local active/latest values are observations only.

Because script definitions are stored in the Automation & Scripting Service database and loaded via `scriptPatchVersion`, designers can roll out script-only updates without redeploying the service binary; the service reloads definitions in place using its versioning and hot-reload flow.

### Runtime Version Behavior

- Script definitions are stored in the **Automation & Scripting Service** database and versioned alongside other game assets. Publishing updates from the Game Design Service is supported.
- Designers can deploy updated scripts without redeploying code. The Automation & Scripting Service validates readiness, but live execution retrieves only the exact Game Session-pinned `(scriptPatchVersion, scriptPinEpoch)` tuple; it does not select a current/latest local version.
- Script-only patches create a `scriptPatchVersion` (the logical/API name) tied to a `baseVersionId` so new behaviors can be loaded on the fly. In the Game Session Service database this is persisted as `script_patch_version`. See [System Architecture: Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md#script-only-patch-versions) for how these patch versions work.
- The Game Session Service stores the authoritative `(scriptPatchVersion, scriptPinEpoch)` for each running game. When a new patch is published, the Game Design Service calls `NotifyScriptVersionUpdate`, allowing Automation to ingest and validate it for tenant readiness before any later explicit pin-driven instance transition.
- Timer events and scheduled evaluations always reference the exact version and pin epoch committed by Game Session at the moment they are admitted.
- Older versions remain in the database for auditing or rollback, but only the exact pinned tuple is executed.

### Version Authority & Consistency

At a high level:

- The **Game Design Service** owns the *authoring* view of versions and drives the durable `publish` workflow.
- The **Automation & Scripting Service** owns the *runtime* view of script patch readiness per tenant (for example, whether a patch is `READY` or `FAILED`).
- The **Game Session Service** owns the pinned `(scriptPatchVersion, scriptPinEpoch)` for each game and is responsible for including both exact fields in gameplay/runtime and scheduler events sent to the Automation & Scripting Service; control-plane events follow their own contract and do not acquire an instance epoch requirement by implication.

The intended invariants are:

- A script patch may be pinned for a game only after Automation has loaded and validated that exact patch for the tenant and marked it `READY` as part of the readiness workflow.
- When Game Session emits gameplay/runtime or scheduler events, it includes the exact currently pinned version and epoch. Automation must **not** silently substitute a different version or epoch; unknown, failed, stale, or mismatched authority rejects that trigger. Control-plane events lacking an epoch remain governed by their own control-plane contract rather than being rejected for that omission.

From the Automation & Scripting Service’s point of view, each `<tenantId, scriptPatchVersion>` follows the readiness lifecycle described in [Script Patch Lifecycle](#script-patch-lifecycle). Runtime script-event audit entries include the effective exact `(scriptPatchVersion, scriptPinEpoch)` tuple at evaluation so operators can correlate failures with the authoritative instance pin; tenant `onLoad` readiness records have no instance pin epoch.

---

## Script Patch Lifecycle

Script patches have two related but distinct lifecycle views:

- A tenant patch lifecycle owned by the Automation & Scripting Service and tracked per `<tenantId, scriptPatchVersion>`.
- A per-instance committed pin state owned by Game Session, consisting of the exact `(scriptPatchVersion, scriptPinEpoch)` tuple for `<tenantId, gameInstanceId>`, plus a separate Game Session-owned append-only rollout history scoped to `<tenantId, gameInstanceId>` and keyed by `controlPlaneRequestId`. The history records successful and unsuccessful `SET`, `ROLLBACK`, and `REPIN` attempts.

The tenant lifecycle governs patch readiness and eligibility. The instance lifecycle governs rollout/rollback for running games.

Runtime execution must still remain **instance-aware** even though patch readiness is tenant-scoped:

- A tenant-scoped `READY` state means a patch is eligible to be pinned by instances in that tenant; it does not imply that every running instance must pause, reload, or switch together.
- Admission, timer scheduling, and runtime work/effect fencing must evaluate the effective runtime scope as `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch>`, even if implementations batch internal work by tenant. Pin observation and pin convergence are instance-wide control-plane state carrying the exact `<scriptPatchVersion, scriptPinEpoch>` tuple. Rollback admission pause is separate instance-wide control-plane state keyed by `<tenantId, gameInstanceId, controlPlaneRequestId>` with the existing admission epoch as state/fence; the exact pin tuple is convergence and execution-fence evidence, not pause identity. Neither control-plane state splits by `playableStateScope`. Plugin activation/configuration remains materialized per `<tenantId, gameInstanceId, pluginId>`; plugin trigger identity and plugin-owned derived schedule/work state still carry the effective `playableStateScope`. Region and epoch fields remain required for region/tick-aligned runtime timelines.
- If a deployment wants stronger coupling, it must explicitly declare the invariant that all instances in a tenant share one exact pinned script tuple. Absent that declaration, instance isolation is the normative behavior.
- Instance-scoped runtime state tracks **pin observation and pin-convergence control** for the exact tuple an instance is trying to run (for example `observedPinnedScriptPatchVersion`, `observedScriptPinEpoch`, `reloadState`, and convergence checkpoints). The separate admission, schedule, work, and effect fences apply the resolved `playableStateScope` and region/epoch to trigger-derived state. Instance pin state does **not** rerun tenant patch readiness or `onLoad`.
- A single tenant-wide mutable `activePatchVersion` inside Automation & Scripting is therefore not sufficient. The service must keep tenant-scoped patch readiness separate from instance-scoped pin observation and scheduling state.

Side-by-side lifecycle view:

| Concern | Scope | Owner | Canonical states | What advances it |
| --- | --- | --- | --- | --- |
| Patch readiness | `<tenantId, scriptPatchVersion>` | Automation & Scripting | `PENDING_VALIDATION` -> `ONLOAD_RUNNING` -> `READY` / `FAILED`, terminal `SUPERSEDED` | Publish ingestion, static validation, tenant-scoped `onLoad`, newer accepted publish supersession |
| Runtime pin observation | `<tenantId, gameInstanceId, scriptPatchVersion, scriptPinEpoch>` | Game Session pin plus Automation observation | Observed exact tuple, `reloadState=RELOADING`, observed new tuple, `reloadState=FAILED`, scoped admission/convergence checkpoints | `SetPinnedScriptPatchVersion`, `RollbackScriptPatchVersion`, pin-change events, reload reconciliation, rollback orchestration |
| Runtime admission, schedule, work, and effect fencing | `<tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch>` | Automation and domain runtime owners | Admission, due-point, work-item, command, and effect fences | Resolved trigger scope, region ownership, epoch changes, reload/rollback barriers |
| Plugin activation | `<tenantId, gameInstanceId, pluginId>` | Automation & Scripting runtime registry | Active, pending, draining, disabled plugin version state | Operator activation/disable/drain actions; plugin trigger-derived state retains `pluginVersionId`, `bindingId`, and `playableStateScope` |

Interpretation rules:

- A tenant patch reaching `READY` means only that it is eligible to be pinned by instances in that tenant.
- An instance switching pins does not rerun tenant readiness or `onLoad`; it consumes already-`READY` definitions and reconciles runtime-scoped derived state.
- `SUPERSEDED` exists only on the tenant readiness side. A runtime scope may still be executing an older observed pin while a newer pending patch supersedes an older readiness candidate.

The canonical states are:

- `PENDING_VALIDATION` – the Game Design Service has published a script-only patch version and the Automation & Scripting Service has accepted the compiled graphs and bindings, but `onLoad` initialization has not yet completed for the tenant.
- `ONLOAD_RUNNING` – `onLoad` handlers for scripts in the patch are executing for the tenant. These at-most-once DSL executions are keyed by the full applicable onLoad identity, `<tenantId, scriptId, eventSchemaVersion, scriptPatchVersion, eventType=onLoad, scriptEventId, isDryRun=false>`; publication/readiness generation fences stale execution but is not an additional execution identity; only independently idempotent external infrastructure steps may be retried.
- `READY` – all `onLoad` handlers for the patch have completed successfully for the tenant. The patch is eligible for instance-scoped exact pinning by games in that tenant; `READY` does not create or mutate a tenant-wide pin, require all instances to switch, or replace an instance's observed tuple. Game Session may explicitly pin it as the `scriptPatchVersion` component for an individual instance, paired with a new `scriptPinEpoch`.
- `FAILED` – one or more `onLoad` handlers for the patch have failed for the tenant with a logical or sandbox error, or an independently idempotent external infrastructure step has exhausted its bounded retries. The at-most-once DSL evaluation is not retried. The previous instance-observed pin remains in use for running games, and the failed patch is not eligible to be pinned.
- `SUPERSEDED` – a newer publish for the same tenant was accepted while this patch was still non-terminal (`PENDING_VALIDATION` or `ONLOAD_RUNNING`). The superseded patch remains visible for audit/history but is no longer eligible for pinning or further readiness progression.

Typical transitions are:

1. `PENDING_VALIDATION → ONLOAD_RUNNING` when Automation & Scripting begins `onLoad` initialization for the tenant after successfully ingesting a published patch from Game Design. Patches whose durable publish workflow fails in Game Design (for example, ability schema mismatches) never enter this lifecycle; from Automation’s perspective they do not exist or remain invisible runtime-only.
2. `ONLOAD_RUNNING → READY` when all `onLoad` executions for scripts in the patch succeed for the tenant.
3. `ONLOAD_RUNNING → FAILED` when any `onLoad` execution fails fatally or an independently idempotent external infrastructure step exhausts its bounded retries; the DSL evaluation itself is not retried, and running instances continue using their previously pinned patch.
4. `PENDING_VALIDATION|ONLOAD_RUNNING → SUPERSEDED` when a newer publish is accepted for the same tenant before the older patch reaches a terminal readiness state. `SUPERSEDED` is terminal and must be emitted before the newer patch begins readiness work.

Per-instance rollout state consists of Game Session's committed exact pin tuple `(scriptPatchVersion, scriptPinEpoch)` and its append-only history of successful and unsuccessful `SET`, `ROLLBACK`, and `REPIN` attempts keyed by `controlPlaneRequestId`; an accepted deterministic validation/preparation failure records equal previous and resulting exact pin tuples without mutating the pin or advancing the epoch. The `SetPinnedScriptPatchVersion` and `RollbackScriptPatchVersion` APIs create the authoritative state/history attempts; `ScriptPatchPinChanged` is only a notification for successful committed tuple changes. An instance rollback does not imply tenant patch state transition away from `READY`.

Automation & Scripting exposes this lifecycle to other services via:

- A read-only API such as `GetScriptPatchStatus(tenantId, scriptPatchVersion)` that returns the current state and relevant timestamps.
- Tenant readiness events (`ScriptPatchTenantStatusChanged`) emitted when `<tenantId, scriptPatchVersion>` transitions between readiness states.
- Automation does not author a competing rollout event/history projection. Operators read Game Session's bounded authoritative history and compose it with Automation's readiness/convergence projection.

When an instance-scoped gameplay/runtime trigger arrives at the Automation & Scripting Service, the following pin and `READY` gates apply. Tenant-readiness `onLoad` is explicitly excluded: it remains pre-pin validation with the tenant-readiness Trigger Identity exception and does not require an instance tuple, instance, region, or gameplay `READY` admission.

- If the supplied `scriptPatchVersion` is `READY` for the tenant, the service must additionally compare the supplied exact `(scriptPatchVersion, scriptPinEpoch)` tuple to a fresh-enough observed Game Session tuple for `<tenantId, gameInstanceId>`. Admission proceeds only when both fields match Game Session authority.
- If the instance's owner workflow is in terminal `ROLLBACK_CONVERGENCE_TIMEOUT`, pre-handler admission fails closed with `admissionOutcome=TRIGGER_ADMISSION_OUTCOME_VERSION_UNAVAILABLE`, `admissionReason=rollback_convergence_timeout`, and no `retryAfterMs`; no handler-scoped final outcome is created for this terminal gate.
- If pin visibility for the instance is stale beyond its configured max age and fresh control-plane state cannot be obtained, admission fails closed with `pin_state_unavailable` and a bounded reason in the event-scope ingress audit record. If the failure happens after handler resolution, the handler-scoped `script_event_audit` row uses `finalStage=ADMISSION`, `finalOutcome=pin_state_unavailable`.
- If the supplied `scriptPatchVersion` is `READY` for the tenant but either field in the supplied exact tuple does not match the observed Game Session tuple for `<tenantId, gameInstanceId>`, admission is rejected with `version_unavailable` and a bounded mismatch reason such as `pin_state_mismatch_requested_vs_observed`. Automation & Scripting must not speculate or silently substitute either field.
- If the patch is unknown or in a non-ready state (for example, `PENDING_VALIDATION`, `ONLOAD_RUNNING`, `FAILED`), the trigger is rejected at admission with `version_unavailable` (or a specific bounded reason such as `onload_failed`). Pre-resolution rejections are recorded in ingress audit; handler-scoped rejections use `script_event_audit.finalStage=ADMISSION`. A drop metric like `automation_script_triggers_dropped_total{reason="version_unavailable"}` is incremented.
- Automation & Scripting never silently falls back to an older patch for that trigger; callers must fix the pinned version, repin explicitly, or republish.

---

## Determinism & Allowed Non-Determinism

Scripts are designed to behave **deterministically for authoritative gameplay behavior and emitted commands for a given game configuration and event**, so that both the original execution and any offline replay in tools or tests produce the same authoritative result. Logs and metrics are explicitly outside this replay-determinism guarantee and may remain non-replayable side channels. The Automation & Scripting Service enforces gameplay determinism by constraining how randomness and time are exposed to DSL components:

- All **pseudo-random behavior** (for example, “pick a random waypoint”, “roll for loot”, or encounter selection) flows through curated components that read from a **seeded RNG** supplied by the runtime. The seed is derived from the complete applicable Trigger Identity, including the applicable plugin binding identity (`pluginId`, `pluginVersionId`, `bindingId`) for plugin handlers and scheduler identity (`scheduleDefinitionId`, `triggerMode`, and exactly one due-point field, `dueTickId` or `dueAt`) for scheduler/timer handlers, so re-evaluating the same trigger with the same inputs produces the **same sequence of random values**. Components must not call process-wide RNG APIs directly; they receive a scoped RNG instance from the sandbox.
- Seeds are derived from the full applicable identity tuple primarily so offline replay tools and test harnesses can reproduce behavior for a given event stream; production tick replays never re-enter the DSL for the same full applicable Trigger Identity.
- **Wall-clock time is not exposed** to scripts. DSL components see only **derived game time** sourced from the tick and session model (for example, `tickId`, region-local “world time” counters, or effect durations computed by Game Logic). This ensures that replaying the same tick timeline yields the same time values from the script’s perspective, independent of real-world clock drift.
- Any component that introduces variability must either:
  - be implemented in terms of the seeded RNG and tick-based time described above, or
  - be explicitly documented as **non-replayable** and confined to side channels such as logging and metrics where non-determinism does not affect gameplay state or authoritative decisions.

For authoritative gameplay outputs and commands, the immutable deterministic input is the complete applicable Trigger Identity, the bounded event payload, the pinned script/artifact references, the durable handler-scoped input manifest, and the applicable tick context. The manifest is sealed for each resolved handler, including `onLoad`, and is reused byte-for-byte by retries and recovery. Logs and metrics are excluded from replay determinism and remain non-authoritative side channels. Command-level handoff identity and fan-out rules follow the [cross-service scripting contracts](./system-architecture-scripting-contracts.md#2-script-work-item-vs-tick-command-boundary).

### Read Consistency Contract

Determinism depends on one immutable **handler input manifest**, not on a universal snapshot token. The manifest is a durable, bounded, digest-bound record created for each resolved handler at admission:

- It records the full applicable Trigger Identity and bounded trigger facts, the event payload, pinned script/configuration and compiled-artifact references, and each authoritative input value together with its owning service, schema/version, and immutable read/version evidence.
- It records the applicable causal floor (for example the source commit, tick, or owner sequence that the handler may observe) and a `seedVersion` plus the derived scoped seed. `componentCostRegistryDigest` and `artifactRuntimeCapDigest` are included when output budgeting depends on them.
- Every authoritative DSL read must resolve from a manifest entry at or above its declared causal floor. A handler must not fetch newer state during evaluation or mix fresh and old values because wall-clock time advanced between calls. An owner-specific `readSnapshotToken` may be retained as one manifest input or correlation value when that owner contract uses one; it is not universal authority for other owners.
- The event registry declares which owner inputs and consistency class are required. A missing or contradictory owner-versioned input rejects the handler at the correct ingress or handler stage; `snapshotAuthority=NONE` means no authoritative gameplay-dependent input may be read for that event.
- `onLoad` receives a tenant-readiness manifest containing only configuration/runtime metadata and recomputable-cache versions, never mutable gameplay state. Dry-run/test execution uses an explicit tooling selector or records the server-selected owner versions in its returned/audited manifest.
- The manifest, its digest, and its causal floor are persisted before evaluation (or in the atomic pre-DSL claim) and are reused by retries and recovery. Tick replay consumes persisted descriptors and never re-runs the DSL or fetches newer inputs.

This manifest contract is runtime semantics, not an implementation detail. Services backing DSL read components must expose owner-versioned reads or accept the owner-specific selector required by each manifest entry. The current implementation still carries a narrower ingress/remote `readSnapshotToken` field and does not yet seal the complete durable handler manifest; that is an implementation gap, not an alternate target contract.

Illustrative manifest shape:

```text
handlerInputManifest/v1 {
  triggerFacts: { complete Trigger Identity, bounded event payload },
  artifacts: [{ artifactId, artifactVersion, artifactDigest, componentCostRegistryDigest, artifactRuntimeCapDigest }],
  inputs: [{ ownerService, schemaVersion, boundedValue, ownerVersion, ownerReadToken? }],
  causalFloor: [{ ownerService, ownerVersion }],
  seedVersion: "seed/v1",
  derivedSeed: "<scoped bytes>"
}
```

Crucially, **post-evaluation descriptor recovery and tick replay do not re-execute script handlers**. A persisted evaluated descriptor/work item is retried without re-entering the DSL graph. An evaluation-stage retry before `EVALUATED_COMMITTED` may invoke the DSL evaluator again only for eligible gameplay/runtime work, preserving the original Trigger Identity, frozen manifest/input evidence, and exact admitted immutable graph; tenant-readiness `onLoad` remains at-most-once and is excluded from evaluation replay. Handoff retry and tick-effect idempotency follow the [cross-service scripting contracts](./system-architecture-scripting-contracts.md#2-script-work-item-vs-tick-command-boundary) and the tick idempotency rules; the current live diagnostic fallback is recorded in [Scripting Runtime Execution](./system-architecture-scripting-runtime-execution.md#current-implementation-status). Authoritative gameplay determinism therefore depends on the complete immutable input set defined above, the **"no re-execution after `EVALUATED_COMMITTED`"** guarantee, seeded RNG, and time constraints; logs and metrics remain outside this guarantee.

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
