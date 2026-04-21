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

### Minimum Status Model

Statuses are a target-state contract; implementations may use different internal names as long as they are mapped 1:1:

- `PENDING` - persisted, eligible for indexing and draining.
- `INDEXED` - a pointer/index has been published into `automation:queue:*` (best-effort; may be rederived).
- `HANDOFF_IN_FLIGHT` - being handed off to Game Session (idempotent retries allowed).
- `HANDED_OFF` - Game Session has accepted the corresponding tick commands into tick queues (`script_event_audit.finalStage=TICK_HANDOFF` is now eligible for `finalOutcome=success`).
- `CANCELED` - permanently canceled by control plane (for example rollback, disable, or operator purge).
- `DEAD_LETTERED` - permanently non-progressing due to repeated infrastructure failures; bounded retention and operator visibility are required.

### Pointer Payload Contract for `automation:queue:*`

Entries in `automation:queue:{tenantInstanceTag}:<entityId>` must contain enough information to locate and safely process the durable outbox record:

- `outboxWorkItemId`
- A minimal identity checksum (for example `gameInstanceId`, `scriptPatchVersion`, optional `pluginVersionId`) so rebuild/drain logic can detect version-fence mismatches early without reading full payloads.

The pointer/index format must be forward-compatible (versioned envelope) so it can evolve without requiring out-of-band Redis migrations.

### Rebuild and Deduplication Rules

- Rebuilding `automation:queue:*` from the outbox must be safe to run repeatedly and concurrently (idempotent projection).
- `ScriptTickService` must dedupe drain/handoff by `outboxWorkItemId` (not by Redis list position) so queue resets, re-indexing, and retries do not cause double-handoff.
- `CancelPendingWorkItemsForPatch` and `CancelPendingWorkItemsForPluginVersion` must be implemented as outbox state transitions (`workItemStatus=CANCELED`) so cancellation is durable even if Redis is reset. Cancellation must be reflected in `script_event_audit` stage-aware outcomes (for example `finalStage=ADMISSION` with a cancel outcome/reason for newly arriving triggers, and non-success outcomes for already persisted work that is canceled before handoff).

### Operational Constraints

- Outbox scanning for rebuild and cancellation must be bounded and backpressured (pagination, time windows, per-tenant limits) so it cannot become an unbounded full-table scan on large tenants.
- Outbox retention must be explicitly defined for `HANDED_OFF`, `CANCELED`, and `DEAD_LETTERED` records, and must preserve enough history for rollback diagnosis and audit queries.

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

## Output Budgeting and Command Fan-Out

Admission and sandboxing must bound not only how often a handler runs, but also how much work a single admitted run can emit:

- Each DSL run must enforce explicit output budgets before a work item is persisted, including `maxCommandsPerRun`, `maxCommandsPerEntityPerTrigger`, and `maxSerializedWorkItemBytes`, plus any bounded payload ceilings needed for known large command families.
- Exceeding an output budget must fail deterministically with a non-success stage-aware outcome. Implementations may classify the failure at `DSL_EVAL` or `WORK_ITEM_PERSIST`, but `finalReason` must use bounded canonical codes such as `command_count_exceeded`, `per_entity_command_limit_exceeded`, or `work_item_size_exceeded`.
- Output budgets apply equally to core scripts and plugins.
- Publish-time validation in Game Design must perform conservative worst-case fan-out analysis for bounded loops and bulk action nodes using the same component cost metadata that Automation & Scripting uses for runtime revalidation.

### Static Output Cost Contract

Every compiled script artifact must carry enough normalized output-cost metadata for Game Design and Automation & Scripting to reach the same accept/reject decision before runtime:

- Each action/component definition must declare `maxCommandsEmitted`, optional per-entity command distribution rules, `maxSerializedBytesPerCommand`, and whether its output cost is `STATIC`, `BOUNDED_BY_INPUT`, or `UNSUPPORTED_FOR_STATIC_BOUND`.
- `STATIC` components contribute a fixed cost. `BOUNDED_BY_INPUT` components must name the validated input bound that caps their fan-out, such as a maximum selected-entity count, bounded loop counter, or configured list length. `UNSUPPORTED_FOR_STATIC_BOUND` components are not eligible for publish in live scripts until they are redesigned or given a bounded contract.
- Branches are analyzed conservatively by taking the maximum cost of mutually exclusive branches and the sum of costs for paths that can both execute in one run.
- Bounded loops multiply the loop body cost by the validated finite iteration bound. Loops without a finite bound are rejected by loop-safety validation before output-cost analysis.
- Timer edges that create future triggers do not add same-run command cost beyond the timer-registration command itself, if any; the future trigger is analyzed as its own run.
- Bulk action nodes must expose an explicit validated maximum fan-out. Runtime-discovered collection sizes without a publish-time upper bound are treated as `UNSUPPORTED_FOR_STATIC_BOUND`.
- Game Design writes the normalized cost summary into the compiled artifact. Automation & Scripting revalidates the summary against its local component registry and rejects the patch if the summary is missing, stale, or exceeds runtime ceilings.
- Runtime output budgeting remains mandatory even when static validation passes; the static contract prevents obviously oversized graphs from publishing, while runtime guards protect against registry bugs, corrupted artifacts, or future component changes.

## Budget Charge Points

Quota and budget accounting must be deterministic so operators can reason about load and so retries do not double-charge:

- Event-scope ingress admission does not itself consume per-script quota windows or tenant runtime budgets.
- Per-script quota windows are charged once per resolved handler at handler admission time.
- A handler admitted immediately to sandbox work consumes one quota slot at admission, and a handler accepted into a bounded `queue_until_free` wait queue also consumes that slot immediately rather than being charged again when execution later starts.
- Per-tenant and cluster execution budgets are charged when a handler-scoped run leaves admission and is reserved onto sandbox execution capacity.
- Duplicate deliveries for the same handler-scoped Trigger Identity must not consume additional quota.
- Budget consumption is not refunded for runs that later fail after the charge point.
- `onLoad` readiness work uses a separate publish-time budget class and must never consume the ordinary live per-script quota window or tenant runtime execution budget.
- Implementations may expose additional budget dimensions, but they must map to one of these charge points rather than inventing ad hoc charging semantics per caller or per service.

## Ordering Between Player and Script Commands

- Each entity has a single authoritative command queue in Redis that contains both player-originated commands and script-generated commands.
- Commands are appended to this queue in the order they are accepted by the Game Session Service.
- During tick processing, the Game Session Service reads at most one command per entity per tick from this combined queue.
- Script-generated commands carry `scriptEventId`, `scriptId`, and when applicable upstream ordering tokens such as `tickId`.

## Redis Key Summary for Scripting

The main Redis keys used by the Automation & Scripting Service are:

| Key pattern | Owner / service | Purpose | Hash tag / shard scope | TTL / retention expectations |
| --- | --- | --- | --- | --- |
| `automation:queue:{tenantInstanceTag}:<entityId>` | Automation & Scripting | Per-instance, per-entity queue of post-DSL script work item indexes awaiting automation ticks. | Single-key queue per entity within an instance scope. | Reset-tolerant, best-effort derived index; authoritative pending work items are persisted durably in PostgreSQL (outbox). |
| `automation:tick:{tenantInstanceScriptTag}:lock` | Automation & Scripting (`ScriptTickService`) | Per-instance, per-script automation tick lock to serialize staging for a script’s work batch. | Hash-tagged on `{tenantInstanceScriptTag}`. | Short-lived lock. |
| `automation:tick:{tenantInstanceScriptTag}:queue` | Automation & Scripting (`ScriptTickService`) | Staging queue for batched script events before they are written into per-entity tick queues. | Hash-tagged on `{tenantInstanceScriptTag}`. | Short-lived staging. |
| `automation:timer:{tenantRegionTag}` | Automation & Scripting scheduler | Region-scoped index of script timers and intervals. | Hash-tagged on `{tenantRegionTag}`. | Persistent while timers are active. |
| `script-leader:{<tenantId>}` | Automation & Scripting scheduler | Leadership lease for scheduler coordination per tenant. | Hash-tagged per tenant. | Short-lived lease refreshed by the active scheduler instance. |

## Failure Modes and Error Handling

Script executions are treated as at-most-once per trigger. Common outcome classes include:

- `success`
- `quota_denied`
- `sandbox_error`
- `validation_error`
- `disabled_due_to_errors`
- `version_unavailable`
- `signer_policy_unavailable`
- `infrastructure_error`
- `validation_error` with `finalReason=unsafe_component`

Component safety classification for core scripts is fixed at validation and readiness time, not reevaluated as a live runtime policy on already-READY patches.

`success` is valid only after tick-handoff acceptance, not merely after DSL evaluation. Audit records therefore stay stage-aware through `finalStage`, `finalOutcome`, and `finalReason` rather than collapsing runtime behavior into a single undifferentiated status.

Retry behavior:

- Logical failures are treated as final for a trigger.
- Backpressure outcomes such as `skipped_reloading` and `skipped_rollback_pause` are not treated as final for low-rate external events.
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
- Each `onLoad` execution is keyed by `<tenantId, scriptId, scriptPatchVersion>` and is treated as at-most-once.
- Allowed uses are limited to ephemeral or trivially recomputable runtime initialization.
- `onLoad` must not create durable or semi-durable artifacts in databases, Redis, object storage, or other shared stores.
- There is no compensating `onUnload` / `onDeactivate` lifecycle in the current architecture.
- If future requirements demand durable patch-managed shared state, the platform must add a symmetric deactivation/cleanup lifecycle first.

Failure handling:

- `onLoad` admission uses a dedicated publish-time initialization budget, not the normal live-trigger quota windows enforced by `ScriptQuotaService`.
- If `onLoad` completes successfully for a tenant, the Automation & Scripting Service may mark the patch as `READY` for that tenant.
- If `onLoad` fails with a logical or sandbox-level error, the patch is marked `FAILED` for that tenant and events that reference the failed patch are rejected at admission with `version_unavailable` or a more specific bounded variant such as `onload_failed`.
- If the dedicated `onLoad` initialization budget is exhausted before completion, the patch must fail deterministically with an explicit bounded reason.
- If `onLoad` fails with `infrastructure_error`, the service may optionally retry the initialization a bounded number of times using the same `scriptEventId` and idempotent operations.

All `onLoad` runs are recorded in `script_event_audit` with `eventType=onLoad`, the target `scriptPatchVersion`, and their final stage-aware outcome. A successful `onLoad` contributes to patch readiness aggregation and must use `finalStage=DSL_EVAL`, `finalOutcome=readiness_success`; it does not use live `finalOutcome=success`, which remains reserved for tick handoff.
Patch-level readiness for `<tenantId, scriptPatchVersion>` is derived from the aggregate of all per-script `onLoad` runs; the patch becomes `READY` only after every required `onLoad` handler succeeds.
