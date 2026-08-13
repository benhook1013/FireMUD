# FireMUD System Architecture: Scripting Runtime Execution

This document is the canonical reference for scripting runtime execution flow, event/outbox behavior, Redis/runtime integration, execution-state ownership, version fencing, and the other operational behaviors that sit behind the DSL reference. Use it together with the DSL reference, the Automation & Scripting Service README, the sandbox runtime design, and the tick architecture.

Routing note:

- Use this document for runtime execution semantics, instance-aware admission, and execution-state behavior after a script patch or plugin version is already eligible to run.
- Use `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md` for the DSL and authoring/publish lifecycle.

It is a companion to:

- `design/architecture/system-architecture-scripting-dsl-reference-and-lifecycle.md` - DSL model, lifecycle states, determinism rules, and author-facing semantics.
- `design/architecture/system-architecture-scripting.md` - high-level scripting hub and audience routing.
- `design/architecture/system-architecture-scripting-quotas-and-operations.md` - quotas, circuit breakers, and operator workflows.
- `design/architecture/system-architecture-scripting-scheduler-and-timers.md` - timer leadership, scheduling, and reload coordination.
- `design/architecture/system-architecture-ticks.md` - tick model, idempotency rules, and replay semantics.
- `design/architecture/system-architecture-transactions.md` - transaction patterns and idempotent downstream operations.
- `design/architecture/system-architecture-versioning-runtime.md` - script-only patch versions and runtime configuration.
- `design/architecture/microservices/automation-scripting-service/runtime-and-data.md` - service-level runtime state, Redis roles, and persistence.
- `design/architecture/microservices/automation-scripting-service/sandbox-runtime-design.md` - sandbox engine semantics and resource limits.

## Table of Contents

- [Scope and Ownership](#scope-and-ownership)
- [Work Item Outbox Contract](#work-item-outbox-contract-normative)
- [`scriptEventId` Lifecycle and Deduplication](#scripteventid-lifecycle-and-deduplication)
- [Runtime Deployment & Versioning](#runtime-deployment--versioning)
- [Script Patch Lifecycle](#script-patch-lifecycle)
- [Runtime Execution Flow](#runtime-execution-flow)
- [Output Budgeting and Command Fan-Out](#output-budgeting-and-command-fan-out)
- [Budget Charge Points](#budget-charge-points)
- [Ordering Between Player and Script Commands](#ordering-between-player-and-script-commands)
- [Redis Key Summary for Scripting](#redis-key-summary-for-scripting)
- [Failure Modes and Error Handling](#failure-modes-and-error-handling)
- [Version Fencing and Rollback Safety](#version-fencing-and-rollback-safety)
- [Timer Failure Semantics](#timer-failure-semantics)
- [`onLoad` Semantics and Failure Handling](#onload-semantics-and-failure-handling)

---

## Scope and Ownership

- The Automation & Scripting Service evaluates scripts, persists admitted work, and coordinates runtime reloads and queue projection.
- Game Session owns the authoritative tick queues, pin state, and execution-time version fence.
- Game Design owns authoring and publish-time validation, not runtime execution.
- Redis is a coordination and projection layer. Durable outbox rows remain the source of truth for admitted work.

When this document and the DSL reference appear to overlap, use the DSL reference for graph/model/determinism semantics and this document for runtime ownership, outbox, queue projection, and rollback behavior.

## Implementation Notes

The current runtime now includes a first durable work-item executor instead of stopping at ingress-only outbox materialization. Automation claims `PENDING_EVALUATION` rows, enforces per-script quota before execution, loads the persisted script definition, evaluates a current-boundary command-emission format, and hands emitted commands to Game Session through `EnqueueAutomationCommandIfAbsent`.

That current-boundary execution format is intentionally narrow but no longer raw-text-only: script definitions may expose `emitCommands` at the top level or under `eventHandlers.<eventType>.emitCommands`, and each emitted command may currently carry either direct `commandText` or a structured `commandAlias` plus templated `arguments`, along with optional `targetEntityId` or first multi-target `targetEntityIds[]`, optional explicit target runtime scope (`targetGameInstanceId`, `targetRegionId`, `targetRegionEpoch`), `requiresSoloTick`, and optional `dueTickId`. If neither target field is present, the command targets the triggering work item's entity; if explicit target runtime scope is absent, the command defaults to the triggering work item's owned gameplay scope. `targetEntityIds[]` expands one emitted command node into multiple gameplay handoffs that share the same rendered command text but preserve distinct deterministic ordinals under the same work item. Command nodes may also carry bounded `when` / `unless` predicate maps over durable work-item metadata and flat primitive payload fields; skipped nodes do not consume command ordinals. Template substitution is still limited to durable work-item metadata and flat primitive payload fields such as `{{payload.commandName}}`. Richer graph execution and broader DSL semantics remain target-state work above this first production evaluator path.

## Work Item Outbox Contract (Normative)

The Automation & Scripting Service must treat Redis automation queues (`automation:queue:*`) as derived indexes/pointers only. The authoritative record of admitted post-DSL work is the durable work item outbox.

This section defines the minimum contract that makes "persist -> index -> drain -> handoff" interoperable and rollback-safe across services and operational tooling.

### Minimum Outbox Record Fields

Each persisted script work item must include:

- Trigger Identity fields from `design/architecture/system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields` (including `gameInstanceId` and, for gameplay/runtime triggers, `regionEpoch`).
- `outboxWorkItemId` (stable unique identifier).
- `createdAt` and `updatedAt`.
- `workItemStatus` (see below).
- `commands` (the domain commands payload, stored durably).
- `commandCount` (for budgeting/inspection).
- `cancelReason` (nullable; required when canceled).

When a work item is handed to Game Session, the handoff identity must be explicit:

- Every gameplay command emitted from one outbox work item must derive a stable `automationDispatchId`.
- If one work item emits exactly one gameplay command, `automationDispatchId` may be derived directly from `outboxWorkItemId`.
- If one work item emits multiple gameplay commands, each emitted command must use a deterministic suffix or ordinal under the same stable parent identity (for example `<outboxWorkItemId>#<commandOrdinal>`), so duplicate handoff retries remain idempotent per gameplay command rather than only per work item.
- Game Session dedupe, stale-timeline rejection, replay/no-op outcomes, and later execution-fence reporting must key off that per-command `automationDispatchId`, while operator tooling must still be able to correlate those outcomes back to the parent `outboxWorkItemId` and Trigger Identity.

Each emitted command is also represented by one durable child dispatch row under the work item. At minimum it stores `outboxWorkItemId`, deterministic `automationDispatchId`, command ordinal and immutable digest, target scope, handoff status/result, attempt metadata, and the returned Game Session `commandId` when accepted. The durable uniqueness key is `automationDispatchId`; duplicate evaluation or delivery reads or advances the same child rather than creating another logical command. Retries select only unfinished children.

### Minimum Status Model

Statuses are a target-state contract; implementations may use different internal names as long as they are mapped 1:1:

- `PENDING` - persisted, eligible for indexing and draining.
- `INDEXED` - a pointer/index has been published into `automation:queue:*` (best-effort; may be rederived).
- `HANDOFF_IN_FLIGHT` - being handed off to Game Session (idempotent retries allowed).
- `HANDED_OFF` - Game Session has durably accepted every required child dispatch. A work item with accepted and unfinished children remains `HANDOFF_IN_FLIGHT`; a permanent required-child failure produces an explicit non-success/dead-letter aggregate rather than `HANDED_OFF`.
- `CANCELED` - permanently canceled by control plane (for example rollback, disable, or operator purge).
- `DEAD_LETTERED` - permanently non-progressing due to repeated infrastructure failures; bounded retention and operator visibility are required.

### Pointer Payload Contract for `automation:queue:*`

Entries in `automation:queue:{tenantInstanceTag}:<entityId>` must contain enough information to locate and safely process the durable outbox record:

- `outboxWorkItemId`
- A minimal identity checksum (for example `gameInstanceId`, `scriptPatchVersion`, optional `pluginVersionId`) so rebuild/drain logic can detect version-fence mismatches early without reading full payloads.

The pointer/index format must be forward-compatible (versioned envelope) so it can evolve without requiring out-of-band Redis migrations.

### Rebuild and Deduplication Rules

- Rebuilding `automation:queue:*` from the outbox must be safe to run repeatedly and concurrently (idempotent projection).
- Automation's queue-drain/rebuild path locates the parent by `outboxWorkItemId`, then deduplicates and retries each emitted command by its durable `automationDispatchId` child (never Redis list position). Queue resets and re-indexing therefore cannot resend already accepted children or invent new ordinals.
- `CancelPendingWorkItemsForPatch` and `CancelPendingWorkItemsForPluginVersion` must be implemented as outbox state transitions (`workItemStatus=CANCELED`) so cancellation is durable even if Redis is reset. Cancellation must be reflected in `script_event_audit` stage-aware outcomes (for example `finalStage=ADMISSION` with a cancel outcome/reason for newly arriving triggers, and non-success outcomes for already persisted work that is canceled before handoff).

### Operational Constraints

- Outbox scanning for rebuild and cancellation must be bounded and backpressured (pagination, time windows, per-tenant limits) so it cannot become an unbounded full-table scan on large tenants.
- Outbox retention must be explicitly defined for `HANDED_OFF`, `CANCELED`, and `DEAD_LETTERED` records. Payload compaction or deletion waits until every child is terminal and the longest downstream replay, rollback-diagnosis, command-status, and audit horizon has elapsed; retained identity/digest/disposition evidence must still prevent a duplicate logical dispatch.
- The canonical defaults are owned by [Automation & Scripting Service Configuration](./microservices/automation-scripting-service/configuration.md): `SCRIPT_OUTBOX_HANDED_OFF_RETENTION_DAYS`, `SCRIPT_OUTBOX_CANCELED_RETENTION_DAYS`, `SCRIPT_DEAD_LETTER_MAX_AGE_SECONDS`, `SCRIPT_OUTBOX_TERMINAL_CLEANUP_INTERVAL_SECONDS`, `SCRIPT_OUTBOX_QUEUE_REBUILD_INTERVAL_SECONDS`, `SCRIPT_OUTBOX_QUEUE_REBUILD_BATCH_SIZE`, `SCRIPT_OUTBOX_EXECUTION_INTERVAL_SECONDS`, and `SCRIPT_OUTBOX_EXECUTION_BATCH_SIZE`.
- The current Automation & Scripting implementation wires those retention knobs into a scheduled cleanup job for terminal `script_work_items`: `HANDED_OFF` and `CANCELED` rows expire by status-specific retention days, and `DEAD_LETTERED` rows expire by max age plus a row-count cap that removes the oldest excess rows first.
- The current implementation also wires the derived queue contract into runtime behavior instead of leaving it as prose only: queue drains dedupe repeated pointer envelopes by `outboxWorkItemId`, a bounded scheduled rebuild republishes missing queue pointers from durable `PENDING_EVALUATION` / `EVALUATING` work items, and the scheduled executor now uses queue pointers as its first work-discovery path before claiming durable outbox rows. Redis never becomes authoritative: execution only proceeds after a PostgreSQL row is successfully transitioned from `PENDING_EVALUATION` to `EVALUATING`, stale/orphaned pointers are ignored by that durable claim, and queue discovery failures fall back to the bounded durable scan.
- Those current implementation claims do not yet prove the target durable child-dispatch model, the 1:1 mapping from current internal statuses to the normative parent/child states, multi-command partial handoff, or retention gated by downstream evidence.
- Operator-facing replay, purge, and convergence tooling must treat those retention windows as the supported diagnosis horizon rather than inventing ad hoc cleanup timing.

## `scriptEventId` Lifecycle and Deduplication

`scriptEventId` is the canonical identifier for a single script trigger/run; it appears on automation queue entries, tick commands, and `script_event_audit` rows so behavior can be correlated end-to-end.

- **Generation rules**
  - For external events, the event source that owns the trigger creates a `scriptEventId` when the event is first emitted and includes it in the `TriggerScriptEvent` payload. If the caller retries the gRPC call due to infrastructure errors, it must reuse the same `scriptEventId`.
  - For scheduler-originated events such as `onInterval` and `onTimerExpire`, the Automation & Scripting scheduler creates the `scriptEventId` when the timer or interval becomes due.
  - For dry-run/test invocations, the Automation & Scripting Service generates `scriptEventId` by default so test tooling does not create cross-client collisions.

- **Uniqueness scope**
  - Uniqueness is enforced over the full Trigger Identity field set, including `gameInstanceId` and, for gameplay/tick-aligned triggers, `regionEpoch`.
  - There is no requirement for global uniqueness across all tenants; downstream idempotency keys are derived from stable tuples such as `<tenantId, regionId, regionEpoch, entityId, scriptId, scriptEventId, tickId, scriptPatchVersion>` depending on the call path.

- **Deterministic scheduler IDs**
  - Scheduler-originated `scriptEventId` values must be deterministic so leader failover and bounded catch-up do not double-fire.
  - The specific encoding is an implementation detail, but it must be derived from stable inputs, not from process-local randomness.

- **Handling retries and duplicates**
  - The Automation & Scripting Service treats script execution as at-most-once per Trigger Identity.
  - Duplicate delivery handling must preserve a single `script_event_audit` row per Trigger Identity with monotonic stage progression.
  - Downstream services and replay tools rely on stable idempotency tokens derived from Trigger Identity plus tick context when applicable.

## Runtime Deployment & Versioning

Deployment semantics, publish-time ownership, and the canonical versioning model remain owned by [Scripting DSL Reference & Event Lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md#deployment--versioning) together with [System Architecture: Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md). This runtime document keeps the anchor as a routing stub because execution-time readers often arrive here first, but the versioning contract itself should not be duplicated in two normative homes.

## Script Patch Lifecycle

The canonical patch lifecycle and readiness states remain owned by [Scripting DSL Reference & Event Lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md#script-patch-lifecycle). Runtime execution consumes that lifecycle by enforcing pin visibility and admission checks:

- If the supplied `scriptPatchVersion` is `READY` for the tenant, runtime must additionally compare it to a fresh-enough observed pin for `<tenantId, gameInstanceId>`.
- If pin visibility is stale beyond its configured max age and fresh control-plane state cannot be obtained, admission fails closed.
- If the supplied `scriptPatchVersion` is `READY` for the tenant but does not match the observed pinned patch, admission is rejected.
- If the patch is unknown or in a non-ready state, the trigger is rejected at admission.

## Runtime Execution Flow

Scripts do not execute inside the tick system. The Automation & Scripting Service evaluates scripts independently, on a schedule, via timers, or in response to events, and enqueues the resulting commands into each entity's command queue.

- These queued commands run during the next tick cycle via the normal Game Session and Game Logic flow.
- Script evaluation never blocks or interferes with tick execution.
- Script-generated commands may fail due to lock contention or target remote regions, and Game Session handles those cases via its normal tick rescheduling and cross-region routing logic.
- The Automation & Scripting Service only determines which commands to inject. It never mutates entity or world data directly.

For a non-exclusive multi-handler event, Automation assigns the resolved handlers a durable `handlerSequence` under [ADR 0123](./decisions/adr-0123-preselected-exclusive-handlers-and-durable-fanout-ordering.md). The event resolution record or equivalent durable manifest identifies the complete ordered set. That sequence and each handler's local command ordinal are preserved on work items, generated-command persistence, handoff records, Game Session command admission, retries, and final application. Consumers must not infer semantic order from enqueue time, database identity, Redis list position, worker claim order, scheduling priority, or arrival time.

A handler's terminal failure or empty output closes its sequence position and allows later non-exclusive handler commands to proceed without canceling them. A delayed lower sequence is not equivalent to a terminal failure; downstream application must retain or reconstruct enough resolution state to avoid applying a higher sequence prematurely. The exact buffering representation may vary, but missing or contradictory sequence/completeness metadata fails explicitly rather than degrading to arrival order.

An authorized exclusive binding is selected before work-item fan-out and is the only handler materialized for that event scope. If it fails or is denied, no sibling fallback is created.

## Output Budgeting and Command Fan-Out

Admission and sandboxing must bound not only how often a handler runs, but also how much work a single admitted run can emit:

- Each DSL run incrementally enforces `maxCommandsPerRun`, `maxCommandsPerEntityPerTrigger`, `maxSerializedWorkItemBytes`, declared data-dependent caps, and any known large-command payload ceilings before constructing or serializing the next over-limit output element.
- A resolved handler's generated output is persisted atomically as one complete bounded set or not persisted at all. Exceeding an output budget fails deterministically at handler stage `DSL_EVAL` with a bounded reason such as `command_count_exceeded`, `per_entity_command_limit_exceeded`, `work_item_size_exceeded`, or the applicable cap violation; it never leaves a partial command set.
- A request-envelope limit known before handler resolution remains an event-ingress outcome. It does not stand in for a later generated-output result, and a handler failure does not rewrite successful event-scope admission or another handler's outcome.
- A non-exclusive handler failure closes only that handler's durable sequence position. It does not cancel sibling work or change their Trigger Identities and outcomes.
- Output budgets apply equally to core scripts and plugins.
- Publish-time validation in Game Design must perform conservative worst-case fan-out analysis for bounded loops and bulk action nodes using the same component cost metadata that Automation & Scripting uses for runtime revalidation.

### Static Output Cost Contract

Under [ADR 0088](decisions/adr-0088-static-and-incremental-script-output-bounds.md), every compiled script artifact records the version and digest of one shared normalized component-cost metadata contract so Game Design and Automation & Scripting use the same interpretation:

- Each action/component definition must declare `maxCommandsEmitted`, optional per-entity command distribution rules, `maxSerializedBytesPerCommand`, and whether its output cost is `STATIC`, `BOUNDED_BY_INPUT`, or `UNSUPPORTED_FOR_STATIC_BOUND`.
- `STATIC` components contribute a fixed cost. `BOUNDED_BY_INPUT` components name a finite bound that is recorded in the artifact and enforced incrementally at runtime, such as a maximum selected-entity count, loop count, or configured list length. `UNSUPPORTED_FOR_STATIC_BOUND` components are not eligible for publish in live scripts until redesigned or given an enforced bounded contract.
- Branches are analyzed conservatively by taking the maximum cost of mutually exclusive branches and the sum of costs for paths that can both execute in one run.
- Bounded loops multiply the loop body cost by the validated finite iteration bound. Loops without a finite bound are rejected by loop-safety validation before output-cost analysis.
- Timer edges that create future triggers do not add same-run command cost beyond the timer-registration command itself, if any; the future trigger is analyzed as its own run.
- Bulk action nodes must expose an explicit validated maximum fan-out. Runtime-discovered collection sizes without a publish-time upper bound are treated as `UNSUPPORTED_FOR_STATIC_BOUND`.
- Game Design writes the normalized cost summary plus component-cost metadata version/digest into the compiled artifact. Automation & Scripting revalidates that exact version/digest and rejects a missing, displaced, mismatched, stale, or over-ceiling artifact rather than interpreting it with a private newer table.
- Runtime output budgeting remains mandatory and incremental even when static validation passes; it protects against registry defects, corrupt artifacts, and actual data-dependent values before oversized allocation, serialization, or persistence.

The separate estimated-millisecond ordered-prefix automation admission contract is target-state and not implemented by the current runtime; its authority is the Automation service documentation and [ADR 0088](decisions/adr-0088-static-and-incremental-script-output-bounds.md), not this runtime output-budget contract.

## Budget Charge Points

Quota and budget accounting must be deterministic so operators can reason about load and so retries do not double-charge:

- Event-scope ingress admission does not itself consume per-script quota windows or tenant runtime budgets.
- One durable charge record keyed by full handler Trigger Identity and persisted `quotaClass` records admission and execution charge state. Duplicate delivery, retry, and recovery reuse it.
- Per-script quota is charged once and nonrefundably at handler admission. A handler accepted into `queue_until_free` consumes that admission quota but holds no sandbox capacity while waiting.
- Per-tenant and cluster execution usage is charged once when sandbox execution actually begins. Cancellation before that point does not consume execution usage; failure, timeout, or cancellation after it begins does not refund the usage already consumed.
- Sandbox concurrency is a fenced lease rather than a refundable charge. It is acquired for execution, released on terminal completion/cancellation, and reclaimed after crash or timeout; stale holders cannot execute. Release returns capacity without changing usage history.
- Scheduler reservations, when implemented, remain separate from these durable charges and occupancy leases; actual runtime does not create a same-tick refund.
- `onLoad` readiness work uses the separate `PUBLISH_READINESS` quota class and must never consume the ordinary live per-script quota window or tenant runtime execution budget.
- Automation must persist the resolved registry `quotaClass` onto each durable `script_work_item` so execution-time budget behavior reads the same canonical policy that ingress used instead of re-inferring from `eventType`.
- Current Automation execution also reserves dedicated readiness capacity for non-dry-run `PUBLISH_READINESS` work before DSL evaluation; if that bounded substrate is exhausted, the work item is canceled with `finalStage=ADMISSION`, `finalOutcome=quota_denied`, and `finalReason=onload_budget_exceeded`.
- Implementations may expose additional budget dimensions, but they distinguish durable usage charges from temporary capacity leases and map them to these canonical boundaries rather than inventing ad hoc semantics.

## Ordering Between Player and Script Commands

- Each entity has a single authoritative command queue in Redis that contains both player-originated commands and script-generated commands.
- Commands are appended to this queue in the order they are accepted by the Game Session Service.
- During tick processing, the Game Session Service reads at most one command per entity per tick from this combined queue.
- Script-generated commands carry `scriptEventId`, `scriptId`, and when applicable upstream ordering tokens such as `tickId`.

## Redis Key Summary for Scripting

The main Redis keys used by the Automation & Scripting Service are:

| Key pattern | Owner / service | Purpose | Hash tag / shard scope | TTL / retention expectations |
| --- | --- | --- | --- | --- |
| `automation:queue:{tenantInstanceTag}:<entityId>` | Automation & Scripting | Per-instance, per-entity queue of post-DSL script work item indexes awaiting durable executor pickup or rebuild inspection. | Single-key queue per entity within an instance scope. | Reset-tolerant, best-effort derived index; authoritative pending work items are persisted durably in PostgreSQL (outbox). |
| `automation:timer:{tenantRegionTag}` | Automation & Scripting scheduler | Region-scoped index of script timers and intervals. | Hash-tagged on `{tenantRegionTag}`. | Persistent while timers are active. |
| Scheduler leadership state | Automation & Scripting scheduler | Derived scheduler ownership aligned to the canonical runtime and region-scoped coordination model; do not assume a separate first-class `script-leader:*` prefix unless a later Redis design update explicitly introduces it. | Must follow the same slotting and reset rules as the documented scheduler coordination families. | Short-lived and reset-tolerant by design. |

## Failure Modes and Error Handling

Script evaluation may retry after infrastructure failure under the same full Trigger Identity. Retries must converge on one durable logical work item and one deterministic child dispatch per emitted command; they do not promise that sandbox evaluation code physically ran at most once. Common outcome classes include:

- `handoff_accepted`
- `completed_no_commands`
- `quota_denied`
- `sandbox_error`
- `validation_error`
- `disabled_due_to_errors`
- `version_unavailable`
- `signer_policy_unavailable`
- `infrastructure_error`
- `validation_error` with `finalReason=unsafe_component`

Component safety classification for core scripts is fixed at validation and readiness time, not reevaluated as a live runtime policy on already-READY patches.

`handoff_accepted` is valid only after every required child dispatch is durably accepted by Game Session; it is not gameplay success. A valid live evaluation that intentionally emits no commands uses `completed_no_commands` at `DSL_EVAL`. Audit records stay stage-aware, while later gameplay truth remains per dispatch.

Retry behavior:

- Logical failures are treated as final for a trigger.
- Backpressure outcomes such as `skipped_reloading` and `rollback_paused` are not treated as final for low-rate external events.
- Infrastructure errors may be retried by lower layers following platform-wide retry policies and idempotency contracts.

When script components call other services over gRPC, they must pass a stable idempotency key derived from Trigger Identity plus tick context when applicable so downstream operations can retry without duplicating effects.

## Version Fencing and Rollback Safety

Rollback of a script patch must not allow previously queued work from the rolled-back patch to continue affecting gameplay.

- Script work items and tick commands carry the effective `scriptPatchVersion` used to produce them.
- Game Session enforces a version fence at execution time.
- Operational rollback flows include a drain/purge step for queued automation work items and staging entries that cannot satisfy the version fence.

## Timer Failure Semantics

Timer-based triggers such as `onInterval` and `onTimerExpire` are subject to the same at-most-once per trigger semantics as other events:

- When a timer becomes due, the scheduler attempts to admit the corresponding trigger subject to quotas and budgets.
- If a timer trigger is skipped because of quotas, budgets, or an unavailable patch, the scheduler records the skip in `script_event_audit` with a canonical outcome and reason.
- If a timer trigger fails with `infrastructure_error` after admission, the DSL body is not re-executed for the same `scriptEventId`.
- The scheduler’s responsibility is to attempt to fire timers that fit within configured budgets and capacity; there is no guarantee of eventual execution for every individual interval or timer firing.

## `onLoad` Semantics and Failure Handling

The `onLoad` lifecycle event is a tenant-readiness check for scripts in a given `<tenantId, scriptPatchVersion>` before that patch becomes active:

- `onLoad` handlers run after static validation and compilation succeed, but before the patch is marked `READY` for a tenant.
- The immutable patch declares the exact required `onLoad` handler identities, including an explicitly empty set when no handlers are required.
- Each required handler uses one stable logical execution identity keyed to its tenant and patch. Bounded infrastructure retries reuse the same deterministic `scriptEventId`; “once” means one successful logical outcome rather than one physical attempt.
- Allowed uses are limited to bounded validation and optional warming of ephemeral, recomputable caches. Correctness cannot depend on warmed state, cache loss after `READY` cannot affect correctness, and tenant-level success does not promise that every worker is warm.
- `onLoad` must not create durable or semi-durable artifacts in databases, Redis, object storage, or other shared stores.
- There is no compensating `onUnload` / `onDeactivate` lifecycle in the current architecture.
- Schema evolution, authored data, player-state transformation, and instance initialization use their owning migration, publication, cutover/remap, or separately fenced lifecycle workflows rather than `onLoad`.

Failure handling:

- `onLoad` admission uses a dedicated publish-time initialization budget, not the normal live-trigger quota windows enforced by `ScriptQuotaService`.
- Automation & Scripting may mark the patch `READY` only after every identity declared in the immutable handler manifest has been admitted and reached one successful logical terminal outcome. Observing no work is sufficient only for an explicitly empty manifest.
- Missing expected work or failure to admit any required handler fails the patch rather than leaving readiness ambiguous or treating an incomplete observed set as success.
- If `onLoad` fails with a logical or sandbox-level error, the patch is marked `FAILED` for that tenant and events that reference the failed patch are rejected at admission with `version_unavailable` or a more specific bounded variant such as `onload_failed`.
- If the dedicated `onLoad` initialization budget is exhausted before completion, the patch must fail deterministically with an explicit bounded reason.
- If `onLoad` fails with `infrastructure_error`, the service may optionally retry a bounded number of times using the same logical execution identity, `scriptEventId`, and idempotent operations.
- Candidate supersession is fenced by the tenant's monotonic accepted-publication sequence. Only a greater accepted sequence may terminally supersede the current non-terminal candidate, and late completion for the older candidate may be audited but cannot reopen it or advance it to `READY`.

All `onLoad` runs are recorded in `script_event_audit` with `eventType=onLoad`, the target `scriptPatchVersion`, and their final stage-aware outcome. A successful `onLoad` contributes to patch readiness aggregation and uses `finalStage=DSL_EVAL`, `finalOutcome=readiness_success`; it does not claim `handoff_accepted`.
Patch-level readiness for `<tenantId, scriptPatchVersion>` compares the immutable declared handler set with admitted logical outcomes; it is not inferred from the set of work that happened to be observed.
