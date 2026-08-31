# FireMUD System Architecture: Scripting DSL for Game Designers

This document describes the scripting DSL from a **game designer and content author** point of view. It focuses on what you can express in scripts, how events and timers behave conceptually, and how validation and loop safety show up in the visual editor.

It is a companion to:

- `design/architecture/system-architecture-scripting.md` – hub for the overall scripting and automation framework.
- `design/architecture/system-architecture-scripting-examples-and-patterns.md` – worked examples and common behaviors.
- `design/architecture/system-architecture-scripting-quotas-and-operations.md` – quotas, safety limits, and operational behavior.
- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md` – detailed execution semantics and lifecycle for implementers and backend developers.

If you are unsure where to start, you can also read the **Who Should Read What** and **Where to Find Details** sections in `design/architecture/system-architecture-scripting.md` for the broader scripting & automation overview.

For the scripting editor UX and how graphs are created and managed, see:

- `design/architecture/microservices/game-design-service/web-visual-interface.md`
- `design/architecture/microservices/game-design-service/world-editing-tools.md`

## Table of Contents

- [Audience](#audience)
- [Implementation Status](#implementation-status)
- [What the Scripting DSL Is](#what-the-scripting-dsl-is)
- [Core Concepts for Designers](#core-concepts-for-designers)
- [Building Scripts in the Visual Editor](#building-scripts-in-the-visual-editor)
- [Control Flow: Branches, Timers, and Counters](#control-flow-branches-timers-and-counters)
- [Validation, Loop Safety, and Errors](#validation-loop-safety-and-errors)
- [How Scripts Run Over Time](#how-scripts-run-over-time)
- [Where to Go for More Detail](#where-to-go-for-more-detail)

---

## Implementation Status

The ADR 0114 command-plan preview endpoint is currently unimplemented and unavailable. The current `TriggerScriptEvent(isDryRun=true)` path is a legacy materialized dry run: it retains shared sandbox and validation limits, authorization, a separate live/test Trigger Identity namespace, and dedicated rate and budget controls, but does not establish the target preview's caller-supplied fenced snapshot or fixture provenance, isolated preview result/audit and capacity guarantees, exact command-plan response, or zero-live-work boundary. The target preview and this legacy path are therefore distinct; privileged test tools remain subject to their own rate limits and budgets.

The target runtime uses `PENDING_EVALUATION` -> `EXECUTING` -> `EVALUATED_COMMITTED` for the pre-DSL trigger lifecycle; executor acceptance, the execution-start charge marker, and the fenced capacity lease commit before DSL evaluation. The current runtime provides only bounded per-observation timer catch-up and does not yet prove one durable `resumeWindowId` reused across repeated observations and leader takeovers within a single `regionEpoch`, nor the required epoch-transition behavior that fences the prior window before creating a new stable window for the new epoch. Unresolved current-live `EVALUATING` work remains fail-closed and active, and the live runtime lacks an `EVALUATED_COMMITTED` descriptor-replay layer. Retry-before-`EVALUATED_COMMITTED` and descriptor recovery without DSL re-entry remain target-state behavior. See [ADR 0072](./decisions/adr-0072-class-specific-timer-durability-and-recovery.md), the [automation and scheduler runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status), and the [normative timer semantics matrix](./system-architecture-scripting-normative-contract-tables.md#table-3-timer-semantics-matrix).

---

## Audience

- **Game designers and content authors**
  - Use this document to understand what scripts can express, how events and timers are modeled, and how publish and hot-reload behave conceptually.
  - Pair with `design/architecture/system-architecture-scripting-examples-and-patterns.md` for concrete patterns.
  - For the scripting editor UX and how graphs are created and managed, see:
    - `design/architecture/microservices/game-design-service/web-visual-interface.md`
    - `design/architecture/microservices/game-design-service/world-editing-tools.md`

If you need the exact terms, data flows, and runtime guarantees used by services, see `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`.

---

## What the Scripting DSL Is

FireMUD uses one component-based DSL for both ordinary embedded scripts and linked plugins. An embedded script is game-owned content released with a game version or script-only patch; a linked plugin is an independently versioned and instance-activated bundle that uses the same language, sandbox, quotas, and output limits. Plugin packaging or marketplace/source provenance does not create a separate language or automatic trust tier. Packages containing ordinary base-game DML are materialized into a Game Design Draft and republished rather than layered as a runtime plugin. See [DSL Reference & Lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md#one-dsl-distinct-artifact-and-lifecycle-roles).

The scripting DSL lets you define behavior by **wiring together predefined components** rather than writing general-purpose code:

- Scripts are authored as **structured graphs** of components in the visual editor.
- Each component maps to a **safe, well-defined operation** (for example, check a condition, perform an action, start a timer, increment a counter).
- The editor exports structured data that the **Automation & Scripting Service** compiles into execution units.
- There is **no raw Lua or arbitrary scripting language**; behavior is limited to capabilities exposed by the platform so scripts stay analyzable, deterministic, and safe.

At a high level:

- **Events and triggers** start a script run (for example, a player enters a region, an NPC spawns, or a custom game event fires).
- The engine walks the **graph of nodes** you built in the editor, following branches and timers.
- The script produces **commands** that the tick system applies to the game world.

For the precise lifecycle of events, IDs, and queues, see `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md` and `design/architecture/system-architecture-ticks.md`.

---

## Core Concepts for Designers

When designing scripts, it helps to think in terms of a few core ideas:

- **Events and triggers**
  - A **trigger** is a concrete event such as `onEnterRegion`, `onSpawn`, `onCommand`, or a custom event emitted by a service.
  - Each trigger starts a **single run** of your script’s graph for a specific context (for example, a particular NPC or region).
  - Common triggers are surfaced as **event source nodes** in the visual editor.
  - There is also a script-level lifecycle event, `onLoad`, which runs once per script version for a tenant as a readiness gate before that patch can go live there; per-entity setup should still use events like `onSpawn` and `onEnterRegion`. From a designer’s perspective:
    - `onLoad` is a **gate**: if it fails for a tenant (for example, misconfiguration or sandbox errors), the new patch will **not** go live there and the previous patch continues to run instead.
    - You fix `onLoad` failures by correcting the script or configuration and publishing a new patch; there is no automatic retry for logical failures.
    - Tooling in the Game Design and Logging & Admin services surfaces `onLoad` status per tenant (for example, `READY` vs `FAILED`), and runtime failures appear in `script_event_audit`. See the reference doc for the full `onLoad` lifecycle semantics and the quotas/operations doc for where to inspect audit records.
    - `onLoad` is only for **ephemeral readiness work** such as validating configuration and warming recomputable caches. It must not create durable records, Redis structures, or other shared artifacts that outlive the current process, because the current architecture does not define an `onUnload` cleanup hook.

- **Conditions and actions**
  - **Condition nodes** check world state or inputs, then branch via labeled outputs such as `onTrue` / `onFalse` or `onBelowThreshold` / `onAboveThreshold`.
  - **Action nodes** perform work, such as spawning NPCs, changing formation, modifying morale, or sending text to a player, by emitting commands to the game’s tick system.
  - Complex logic is built by chaining condition and action nodes rather than embedding code.

- **Timers and intervals**
  - **Timer nodes** schedule work using an authored scheduler clock and recovery class. Tick/game time is the only default clock (for example, every N ticks); an explicit wall-clock timer is also allowed. Recurring timers explicitly select `SKIP_MISSED` or `COALESCE_ONE`, while a correctness-bearing one-shot explicitly selects the correctness-bearing one-shot class. Wall-clock eligibility enters the next canonical tick and remains hidden from DSL evaluation; no recovery class is inferred by default. See the [scripting scheduler and timer lifecycle](./system-architecture-scripting-scheduler-and-timers.md#script-timers-vs-tick-timers).
  - Timers create **new triggers** when they fire; from your perspective, they “wake up” parts of your graph at the right times.

- **Counters and bounded loops**
  - **Counter nodes** track how many times something has happened in a run (for example, “try up to 3 times”).
  - Loops must always be **bounded** (for example, controlled by a counter or a timer); unbounded cycles are rejected at validation time.

These concepts show up as node types and connections in the editor rather than as direct code.

---

## Building Scripts in the Visual Editor

Scripts are built entirely in the **Game Design Service visual editor**:

- You start from **event source nodes** corresponding to events enabled for the current game.
- You drag **condition, action, timer, and counter components** from the palette and connect them into a graph.
- Under the hood, bindings from events to scripts are stored as structured configuration, but as a designer you work only with the visual graph.

Key behaviors:

- The visual DSL exposes **event source nodes** for any event types enabled for the current game. Designers bind scripts to these nodes in the same way they do for `onSpawn` or `onCommand`.
- Each node type defines **strongly typed inputs** (attributes, thresholds, flags) and a fixed set of outputs.
- The visual editor validates connections at design time so **ill-typed or incompatible graphs never reach runtime**.

For the internal representation of these graphs and how they are compiled, see the reference doc.

---

## Control Flow: Branches, Timers, and Counters

The scripting DSL uses a **directed graph model** for control flow:

- **Nodes** represent conditions, actions, timers, and counters.
- **Edges** represent control-flow transitions such as `onTrue`, `onFalse`, `onTimeout`, `onBelowThreshold`, or `onAboveThreshold`.
- Execution walks this graph; there is **no general-purpose stack or call frame**.

Common patterns:

- **Branching**
  - Condition nodes evaluate predicates and route to different successors (for example, `HealthCheck` followed by `ReputationCheck`, then an `AllOf` or `AnyOf` node).
  - Complex predicates such as “if reputation < X and HP < Y” are modeled as small subgraphs that compose simpler condition nodes.
  - The visual editor enforces these patterns so predicates stay declarative and analyzable.

- **Timers and recurring behavior**
  - Timer nodes reschedule parts of your graph to run later, allowing you to express behaviors like “every N ticks, check if the player is still nearby” without writing loops.
  - Tick/game time is the default clock. Explicit wall-clock timers become eligible through the scheduler and enter the next canonical tick; wall-clock values remain hidden from the DSL. See the [scripting scheduler and timer lifecycle](./system-architecture-scripting-scheduler-and-timers.md#script-timers-vs-tick-timers) for the clock boundary.

- **Bounded loops with counters**
  - Loops are supported only as **bounded, explicit cycles** in the graph.
  - A typical pattern is a **counter** that decrements and branches while a limit remains; when the count is exhausted, control flows to a different part of the graph.
  - Loops without such a guard are rejected at validation time.

The engine also enforces per-run iteration limits at runtime; these safeguards are described in more detail in the reference doc and the Automation & Scripting Service sandbox runtime design.

---

## Validation, Loop Safety, and Errors

The platform performs **extensive validation** at design time and at publish time so unsafe graphs never run:

- **Graph validation in the editor**
  - The Game Design Service highlights invalid wiring (for example, missing connections, incompatible types, or unbounded cycles) directly in the visual editor.
  - Errors point to the specific nodes and edges that must change before publish, including loops rejected by the loop safety analysis.
  - Scripts with routinely classified `UNSAFE` components appear in a dedicated “requires migration” view. This migration-required / new-use-blocked class blocks future publish/readiness while leaving already-`READY` and pinned behavior unchanged until explicit replacement or rollback.
  - A critical sandbox escape, arbitrary-execution, cross-tenant, or private-data risk may instead receive emergency revocation through the audited platform-security action in [ADR 0116](./decisions/adr-0116-routine-component-migration-and-explicit-emergency-revocation.md). Emergency revocation follows the [canonical revocation workflow](./system-architecture-scripting-control-plane-operations.md#emergency-component-revocation-workflow-required): before discovery, its accepted admission fence blocks new triggers and `PENDING_EVALUATION` for every scope not yet proven unaffected while discovery evidence is stale, contradictory, or unavailable, and remains until authoritative discovery and normal pause or safe-target rollback/disable convergence; with no safe target, affected Automation remains unavailable while unrelated gameplay continues.
  - Routine reclassification is not an implicit live rollout. Creator tooling must distinguish migration work from emergency security containment rather than presenting both as one generic unsafe-component state.

- **Loop safety rules**
  - Loops must always be bounded: use timers and counters to express “repeat every N ticks” or “do this up to N times,” rather than wiring a pure cycle with no guard.
  - A loop is considered safe only if there is at least one **bounded guard node**, such as a `Counter` node with a finite `maxIterations`, in the strongly connected part of the graph.
  - If a graph would create an unsafe loop, the editor surfaces a clear validation error pointing at the offending nodes; fix the wiring and re-run validation before publishing.

- **Runtime outcomes you may see**
  - When a script misbehaves at runtime, the Automation & Scripting Service records stage-aware outcome fields in `script_event_audit` (`finalStage`, `finalOutcome`, `finalReason`) so you can distinguish “rejected before evaluation” from “failed during evaluation” from “never accepted into tick queues”.
  - Common outcomes surfaced via tooling include `quota_denied`, `sandbox_error`, `disabled_due_to_errors`, `skipped_reloading`, `rollback_paused`, and `version_unavailable`.
  - Use the canonical outcome taxonomy in `design/architecture/system-architecture-scripting-normative-contract-tables.md#canonical-finaloutcome-values-normative` as the source of truth for names and meanings.
  - Administrative disables and throttling (for example, `runtimeStatus=DISABLED` or `DISABLE_AFTER_DRAIN`) are reflected in script metadata and surfaced through the Game Design and Logging & Admin tools so you can see which scripts are paused, why they were disabled, and when they can be safely re-enabled.

- **Where to look when debugging**
  - For **editor-time issues**, fix the graph based on validation errors in the Game Design Service UI and re-run validation before publishing.
  - For **runtime issues**, start from the script’s recent entries in `script_event_audit` and the associated metrics in `design/architecture/system-architecture-scripting-quotas-and-operations.md`, then adjust quotas or disable/throttle the script using the operational flows described there.
  - For **safe test runs**, the ADR 0114 command-plan preview endpoint is the target-state path for the Game Design / Logging & Admin UIs. Its current implementation boundary is summarized in [Implementation Status](#implementation-status). Once implemented, it will be a separate non-committing path that exercises the same sandbox and validation logic without live work or tick-queue handoff. Test tools are **privileged** and subject to their own rate limits and budgets so they cannot overload the scripting cluster; avoid running unbounded batches of previews against production tenants.
  - When working with support or operators, use `scriptEventId` as a correlation ticket at the correct lifecycle layer after the applicable ingress has validated a supplied ID or allocated one, never by itself. A pre-resolution event-scope ticket or ingress-audit record carries the applicable fields available at that layer: `tenantId`, `eventType`, `eventSchemaVersion`, `isDryRun`, the exact `scriptPatchVersion`/`scriptPinEpoch` pair only after authoritative pin evidence is available, `scriptEventId` after the event-scope claim validates a supplied ID or allocates one, and, for gameplay/runtime events, `gameInstanceId`, `playableStateNamespaceId`, `playableStateScope`, `regionId`, and `regionEpoch`; it also carries producer-derived `sourceService` where applicable. A fail-closed pre-admission audit may omit the pin pair when authority is unavailable, but retains a caller-supplied `scriptEventId` once the event-scope claim has validated it, including on a pre-handler denial. An unfired internally owned timer due candidate, including a `reload_failed` candidate, creates no `scriptEventId` and instead carries its event-scope `scheduleCandidateId`. The ticket does not invent ordinary handler-resolution fields. Owner-known exceptions are tenant-readiness `onLoad`, which includes its `scriptId`, and scheduler/timer candidates, which include schedule-owned `scriptId`, plugin provenance `(pluginId, pluginVersionId, bindingId)` when the durable schedule already owns that binding, and `pluginActivationEpoch` for plugin-owned candidate identity; `resumeWindowId` appears for `CATCH_UP` candidates, while captured `lifecycleRevision` travels as non-identity fence evidence. These are candidate/event identity fields, not resolved handler fields. After handler resolution, the ticket carries the complete applicable [Trigger Identity](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields), including `entityId` when entity-scoped and the resolved plugin/binding fields. Scheduler triggers additionally carry `triggerMode`, `scheduleDefinitionId`, `targetScopeType`/`targetScopeId`, plus exactly one applicable due point (`dueTickId` or `dueAt`). Tenant-readiness `onLoad` tickets explicitly omit `scriptPinEpoch`, `gameInstanceId`, `playableStateNamespaceId`, `playableStateScope`, `regionId`, `regionEpoch`, and `entityId` because they are pre-instance readiness work. Include command-level `outboxWorkItemId`, `automationDispatchId`, and `commandOrdinal` when a handoff child is in scope; those command-level fields are target-state until the Game Session handoff carries them end to end.
    The Game Design and Logging & Admin audit/log views (for example `script_event_audit` queries and trace/log search) should support filtering by these fields so a single trigger can be followed end-to-end without ambiguity. Use the `AS-1.1` and `AS-1.5` entries in the [automation and scheduler runtime tracker](../project-management/implementation-tracking/automation-and-scheduler-runtime.md#capability-status) to distinguish the live identity/read surfaces from target-state command-handoff coverage.

For the detailed loop safety algorithm and runtime budget enforcement, see:

- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
- `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md`

---

## How Scripts Run Over Time

From a designer’s perspective, script execution over time works like this:

- A game event (for example, `onEnterRegion`, `onSpawn`, or a timer) creates a **trigger** for a particular context.
- The Automation & Scripting Service runs your script graph once for that trigger, following the branches, timers, and counters you configured.
- The script produces **commands** that are enqueued into the same tick-based queues used by player commands.
- The **tick system** applies those commands during subsequent game ticks, using its normal fairness, ordering, and retry rules.

Key properties:

- Scripts **do not execute inside the tick loop itself**; they run in the Automation & Scripting Service and contribute commands into the tick system.
- The combination of tick-based scheduling and validation rules ensures that scripts **cannot hot-loop or starve other work**.
- Determinism and replay semantics are defined in `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md` and `design/architecture/system-architecture-ticks.md`; as a designer you can assume that given the same configuration and events, your script behaves predictably.

### Timers and Reliability (Target State)

This section is target-state only; current timer and recovery limitations are summarized in [Implementation Status](#implementation-status).

Recurring and advisory timer handlers such as `onInterval` (or an advisory `onTimerExpire`) are **best-effort** and produce at most one logical durable firing per Trigger Identity. A physical evaluation attempt may retry before its descriptor commit boundary, but it reuses that identity and converges on the same work item rather than creating another logical firing. A correctness-bearing one-shot timer is not best-effort: its intent is durably recorded outside Redis before acknowledgement; physical execution may be at least once and replay-safe, while recovery under the same identity converges to one logical terminal outcome under [ADR 0072](./decisions/adr-0072-class-specific-timer-durability-and-recovery.md).

- Each authored timer declares its clock and recovery class. Tick/game time is the only default clock; no recovery class is inferred by default. Recurring timers explicitly select one recovery policy: `SKIP_MISSED` advances to the next valid future occurrence without firing missed occurrences, while `COALESCE_ONE` permits one bounded synthetic firing for missed time. A correctness-bearing one-shot explicitly selects the correctness-bearing one-shot class. See the [canonical timer semantics matrix](./system-architecture-scripting-normative-contract-tables.md#table-3-timer-semantics-matrix) for the recovery-class contract.
- When a recurring or advisory timer becomes due, the scheduler tries to fire it subject to per-script quotas, per-tenant budgets, and cluster ceilings. Under heavy load or when limits are reached, individual firings may be **skipped** and are not automatically replayed later, even if the timer continues to run at its configured cadence.
- After downtime or leader failover, a recurring `COALESCE_ONE` timer may emit at most one coalesced firing for each complete stable schedule-instance identity in one Automation-owned durable `resumeWindowId` identified by `<tenantId, gameInstanceId, playableStateNamespaceId, regionId, regionEpoch, isDryRun, resumeGeneration>`; the server-derived `playableStateScope` is retained as immutable policy/routing/authorization/fence evidence and is exact-validated alongside that identity. It never replays one firing per cadence boundary. Same-window retries and takeovers reuse that mode-specific ID, while a later recovery episode receives a new generation only after the prior window's selected and cap-excluded outcomes are durable. The tenant-local `SCRIPT_TIMER_CATCH_UP_MAX_FIRINGS_PER_RESUME` cap is evaluated separately inside each such full runtime-identity-and-mode `resumeWindowId` (that one-tenant, one-mode ID): tenant-local describes the window's tenant owner, not a tenant-wide pool, so a schedule in another game instance, playable-state namespace, region, or epoch cannot consume this window's cap. Selected candidates still pass through tenant-first shared scheduler/cluster admission; a tenant or cluster capacity denial is a durable terminal skip for that catch-up candidate and is not retried, deferred, backfilled, or reminted, while its next ordinary future cadence remains eligible. Candidates excluded by the catch-up cap are likewise dropped and audited rather than deferred as an unbounded backlog. Recurring `SKIP_MISSED` timers emit no catch-up firing.
- Infrastructure hiccups (for example, Redis or gRPC outages) do not promise that a recurring or advisory cadence firing will eventually run. Before the `EVALUATED_COMMITTED` descriptor-commit boundary, an admitted evaluation may rerun only under the same full Trigger Identity and must converge on the same durable work-item and child identities. After `EVALUATED_COMMITTED`, recovery replays durable descriptors and never re-enters the DSL; the audit `finalStage` and outcome remain tied to the last confirmed durable stage. Correctness-bearing one-shot timers instead retry or terminalize under their durable intent identity and must not duplicate the effect.
- As a result, recurring timer callbacks should be treated as **bounded scheduling signals**, not per-cadence ledgers. Design timer handlers so they can tolerate missed or delayed firings and recompute from current world state instead of assuming that every interval has executed exactly once.

Example pattern:

- Instead of designing “every 10 ticks, apply exactly 5 damage because this firing must always happen,” design “every 10 ticks, recompute whether the target is still inside the hazard area and, if so, emit the current hazard effect.”
- Instead of assuming a patrol timer will visit every waypoint exactly once on schedule, store the patrol’s logical destination or mode in normal game state and let each firing compute the next valid move from the entity’s current position.

These patterns keep timer-driven behavior correct even when an individual firing is skipped, delayed, or fenced during reload/rollback.

---

## Where to Go for More Detail

Use the following docs depending on what you need:

- For **overall context and related systems**, start from:
  - `design/architecture/system-architecture-scripting.md`
  - `design/architecture/system-architecture-ticks.md`
  - `design/architecture/system-architecture-versioning-runtime.md`

- For **examples and common patterns**, see:
  - `design/architecture/system-architecture-scripting-examples-and-patterns.md`
  - `design/architecture/system-architecture-scripting-quotas-and-operations.md`

- For **implementation details, data flows, and formal guarantees**, see:
  - `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md`
  - `design/architecture/microservices/automation-scripting-service/README.md`
  - `design/architecture/system-architecture-transactions.md`
