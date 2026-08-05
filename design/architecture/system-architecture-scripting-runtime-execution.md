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
- [Current Implementation Status](#current-implementation-status)
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

## Current Implementation Status

This section records current implementation boundaries, not target-state authority. The normative contracts below and the linked owning documents remain canonical.

The current runtime now includes a first durable work-item executor instead of stopping at ingress-only outbox materialization. Automation claims `PENDING_EVALUATION` rows, enforces per-script quota before execution, loads the persisted script definition, evaluates a current-boundary command-emission format, and hands emitted commands to Game Session through `EnqueueAutomationCommandIfAbsent`.

That current-boundary execution format is intentionally narrow but no longer raw-text-only: script definitions may expose `emitCommands` at the top level or under `eventHandlers.<eventType>.emitCommands`, and each emitted command may currently carry either direct `commandText` or a structured `commandAlias` plus templated `arguments`, along with optional `targetEntityId` or first multi-target `targetEntityIds[]`, optional explicit target runtime scope (`targetGameInstanceId`, `targetRegionId`, `targetRegionEpoch`), `requiresSoloTick`, and optional `dueTickId`. If neither target field is present, the command targets the triggering work item's entity; if explicit target runtime scope is absent, the command defaults to the triggering work item's owned gameplay scope. `targetEntityIds[]` expands one emitted command node into multiple gameplay handoffs that share the same rendered command text and deterministic output order under the same work item. The current evaluator can preserve that order locally, while end-to-end command identity propagation remains target-state until the Game Session handoff is widened. Command nodes may also carry bounded `when` / `unless` predicate maps over durable work-item metadata and flat primitive payload fields; skipped nodes do not consume command ordinals. Template substitution is still limited to durable work-item metadata and flat primitive payload fields such as `{{payload.commandName}}`. Richer graph execution and broader DSL semantics remain target-state work above this first production evaluator path.

The current `onLoad` implementation slice is tenant-readiness work limited to configuration/runtime metadata validation and warming recomputable in-process caches. It does not perform per-entity setup or create durable shared state.

At the live Game Session handoff boundary, `EnqueueAutomationCommandIfAbsent` currently carries `tenantId`, `gameInstanceId`, `regionId`, `regionEpoch`, optional `dueTickId`, `automationDispatchId`, `automationWorkItemId`, `scriptId`, `scriptPatchVersion`, target entity, rendered command text, `requiresSoloTick`, `pluginId`, `pluginVersionId`, `playableStateScope`, routing fields, and origin-source fields; its response returns the live Game Session `commandId` and admission outcome. It does not yet carry `commandOrdinal`, `bindingId`, `eventType`, `eventSchemaVersion`, `scriptEventId`, `isDryRun`, `scheduleDefinitionId`, `triggerMode`, or the complete applicable Trigger Identity.

Current dedupe, rejection, and status diagnostics use the available `outboxWorkItemId`/parent work-item correlation, persisted `automationDispatchId`, Game Session `commandId`/persisted `gameSessionCommandId`, command text, selected provenance, and `script_event_audit`. This is a live diagnostic and retry fallback only; it does not claim end-to-end complete Trigger Identity, `commandOrdinal`, or target command-level deduplication/fence proof.

## Work Item Outbox Contract (Normative)

The Automation & Scripting Service must treat Redis automation queues (`automation:queue:*`) as derived indexes/pointers only. The authoritative record of admitted post-DSL work is the durable work item outbox. Queue ownership, command handoff, and cross-service retry invariants are defined in the [scripting cross-service contracts](./system-architecture-scripting-contracts.md); this section retains the local outbox schema and projection behavior.

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

`outboxWorkItemId` is the Automation-owned durable work-item identifier and the stable queue-pointer and executor-deduplication key. At the current Game Session boundary, Automation produces `EnqueueAutomationCommandIfAbsentRequest.automationWorkItemId` with this same value, and Game Session consumes/reports it under the wire field name `automationWorkItemId`; these are boundary-specific names for one identifier, not separate work-item records. `automationDispatchId` is a separate command-handoff identity and must not replace the outbox key.

When a work item is handed to Game Session, the local wire boundary is:

- `EnqueueAutomationCommandIfAbsent` carries the local handoff fields described in [Current Implementation Status](#current-implementation-status), and its response returns the live Game Session `commandId`/admission outcome. The target handoff identity and fenced acceptance rule are owned by the [cross-service scripting contracts](./system-architecture-scripting-contracts.md#2-script-work-item-vs-tick-command-boundary).

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

- `outboxWorkItemId` - the stable durable pointer and deduplication key; it must not be replaced by Redis list position or `automationDispatchId`.
- A minimal identity checksum (for example `gameInstanceId`, `scriptPatchVersion`, optional `pluginVersionId`) so rebuild/drain logic can detect version-fence mismatches early without reading full payloads.

The pointer/index format must be forward-compatible (versioned envelope) so it can evolve without requiring out-of-band Redis migrations.

### Rebuild and Deduplication Rules

- Rebuilding `automation:queue:*` from the outbox must be safe to run repeatedly and concurrently (idempotent projection).
- Automation's queue-drain/rebuild path and durable executor must dedupe by `outboxWorkItemId` (not by Redis list position) so queue resets, re-indexing, and retries do not cause double-handoff.
- `CancelPendingWorkItemsForPatch` and `CancelPendingWorkItemsForPluginVersion` must be implemented as outbox state transitions (`workItemStatus=CANCELED`) so cancellation is durable even if Redis is reset. Cancellation must be reflected in `script_event_audit` stage-aware outcomes (for example `finalStage=ADMISSION` with a cancel outcome/reason for newly arriving triggers, and non-success outcomes for already persisted work that is canceled before handoff).

### Operational Constraints

- Outbox scanning for rebuild and cancellation must be bounded and backpressured (pagination, time windows, per-tenant limits) so it cannot become an unbounded full-table scan on large tenants.
- Outbox retention must be explicitly defined for `HANDED_OFF`, `CANCELED`, and `DEAD_LETTERED` records, and must preserve enough history for rollback diagnosis and audit queries.
- The canonical defaults are owned by [Automation & Scripting Service Configuration](./microservices/automation-scripting-service/configuration.md): `SCRIPT_OUTBOX_HANDED_OFF_RETENTION_DAYS`, `SCRIPT_OUTBOX_CANCELED_RETENTION_DAYS`, `SCRIPT_DEAD_LETTER_MAX_AGE_SECONDS`, `SCRIPT_OUTBOX_TERMINAL_CLEANUP_INTERVAL_SECONDS`, `SCRIPT_OUTBOX_QUEUE_REBUILD_INTERVAL_SECONDS`, `SCRIPT_OUTBOX_QUEUE_REBUILD_BATCH_SIZE`, `SCRIPT_OUTBOX_EXECUTION_INTERVAL_SECONDS`, and `SCRIPT_OUTBOX_EXECUTION_BATCH_SIZE`.
- The current Automation & Scripting implementation wires those retention knobs into a scheduled cleanup job for terminal `script_work_items`: `HANDED_OFF` and `CANCELED` rows expire by status-specific retention days, and `DEAD_LETTERED` rows expire by max age plus a row-count cap that removes the oldest excess rows first.
- The current implementation also wires the derived queue contract into runtime behavior instead of leaving it as prose only: queue drains dedupe repeated pointer envelopes by `outboxWorkItemId`, a bounded scheduled rebuild republishes missing queue pointers from durable `PENDING_EVALUATION` / `EVALUATING` work items, and the scheduled executor now uses queue pointers as its first work-discovery path before claiming durable outbox rows. Redis never becomes authoritative: execution only proceeds after a PostgreSQL row is successfully transitioned from `PENDING_EVALUATION` to `EVALUATING`, stale/orphaned pointers are ignored by that durable claim, and queue discovery failures fall back to the bounded durable scan.
- Operator-facing replay, purge, and convergence tooling must treat those retention windows as the supported diagnosis horizon rather than inventing ad hoc cleanup timing.

## `scriptEventId` Lifecycle and Deduplication

Trigger Identity, endpoint-specific `scriptEventId` ownership, audit uniqueness, and command-level retry identity are defined in the [normative contract tables](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields) and [cross-service scripting contracts](./system-architecture-scripting-contracts.md#4-scripteventid-identity-and-at-most-once-dedupe). Runtime persistence carries the applicable identity on the durable work item and preserves it across queue projection, claims, and handoff attempts.

## Runtime Deployment & Versioning

Deployment semantics, publish-time ownership, and the canonical versioning model remain owned by [Scripting DSL Reference & Event Lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md#deployment--versioning) together with [System Architecture: Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md). This runtime document keeps the anchor as a routing stub because execution-time readers often arrive here first, but the versioning contract itself should not be duplicated in two normative homes.

## Script Patch Lifecycle

The canonical patch lifecycle and readiness states remain owned by [Scripting DSL Reference & Event Lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md#script-patch-lifecycle). Runtime execution consumes that lifecycle by enforcing the [canonical version-fence contract](./system-architecture-scripting-contracts.md#3-version-fencing-rollback-safety) at its local admission boundary:

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
- `onLoad` readiness work uses the separate `PUBLISH_READINESS` quota class and must never consume the ordinary live per-script quota window or tenant runtime execution budget.
- Automation must persist the resolved registry `quotaClass` onto each durable `script_work_item` so execution-time budget behavior reads the same canonical policy that ingress used instead of re-inferring from `eventType`.
- Current Automation execution also reserves dedicated readiness capacity for non-dry-run `PUBLISH_READINESS` work before DSL evaluation; if that bounded substrate is exhausted, the work item is canceled with `finalStage=ADMISSION`, `finalOutcome=quota_denied`, and `finalReason=onload_budget_exceeded`.
- Implementations may expose additional budget dimensions, but they must map to one of these charge points rather than inventing ad hoc charging semantics per caller or per service.

## Ordering Between Player and Script Commands

Game Session owns the combined per-entity tick queue and its enqueue/order invariants as defined in the [cross-service scripting contracts](./system-architecture-scripting-contracts.md#1-tick-queue-ownership-tick). At this runtime boundary, script-generated commands retain their source `scriptEventId`, `scriptId`, and applicable upstream ordering tokens such as `tickId` when the local handoff carries them.

## Redis Key Summary for Scripting

The main Redis keys used by the Automation & Scripting Service are:

| Key pattern | Owner / service | Purpose | Hash tag / shard scope | TTL / retention expectations |
| --- | --- | --- | --- | --- |
| `automation:queue:{tenantInstanceTag}:<entityId>` | Automation & Scripting | Per-instance, per-entity queue of post-DSL script work item indexes awaiting durable executor pickup or rebuild inspection. | Single-key queue per entity within an instance scope. | Reset-tolerant, best-effort derived index; authoritative pending work items are persisted durably in PostgreSQL (outbox). |
| `automation:timer:{tenantRegionTag}` | Automation & Scripting scheduler | Region-scoped index of script timers and intervals for the full `<tenantId, gameInstanceId, regionId>` scope represented by the opaque tag. | Hash-tagged on the shared `{tenantRegionTag}` derived by key helpers; the tag is not a competing identity family. | Persistent while timers are active. |
| Scheduler leadership state | Automation & Scripting scheduler | Derived scheduler ownership aligned to the canonical runtime and region-scoped coordination model; do not assume a separate first-class `script-leader:*` prefix unless a later Redis design update explicitly introduces it. | Must follow the same slotting and reset rules as the documented scheduler coordination families. | Short-lived and reset-tolerant by design. |

`{tenantRegionTag}` is owned by the shared Redis key builders under [Redis Architecture](./system-architecture-redis.md#key-naming-and-shard-discipline) and is intentionally limited to full `<tenantId, gameInstanceId, regionId>` locality. `playableStateScope` remains a first-class field in durable Trigger Identity, command/effect identity, and handoff diagnostics; it must not be inferred from, or added to, this tag without an owning Redis design and key-builder change.

## Failure Modes and Error Handling

The canonical stage and outcome taxonomy is defined in the [normative audit table](./system-architecture-scripting-normative-contract-tables.md#table-2-script_event_audit-stages-and-outcomes). Runtime-specific failure behavior includes quota, sandbox, validation, version, signer-policy, and infrastructure failures; the executor does not re-enter the DSL for a persisted trigger when retrying an idempotent downstream operation.

Component safety classification for core scripts is fixed at validation and readiness time, not reevaluated as a live runtime policy on already-READY patches.

Live `success` is valid only after tick-handoff acceptance, not merely after DSL evaluation; the owner table defines the stage-aware audit fields and allowed outcomes.

Retry behavior:

- Logical failures are treated as final for a trigger.
- Backpressure outcomes such as `skipped_reloading` and `rollback_paused` are not treated as final for low-rate external events.
- Infrastructure errors may be retried by lower layers following platform-wide retry policies and idempotency contracts.

When script components call other services over gRPC, they must pass a stable idempotency key derived from Trigger Identity plus tick context when applicable so downstream operations can retry without duplicating effects.

## Version Fencing and Rollback Safety

Rollback of a script patch must not allow previously queued work from the rolled-back patch to continue affecting gameplay. The version-fence fields, rejection disposition, and diagnostic identity are owned by the [cross-service scripting contracts](./system-architecture-scripting-contracts.md#3-version-fencing-rollback-safety). Locally, script work items retain the effective `scriptPatchVersion`; current live handoff limitations and rejection/status diagnostic fallback are recorded in [Current Implementation Status](#current-implementation-status). A version or runtime-scope mismatch is not applied, temporary plugin-authority unavailability leaves the durable effect retryable, and rollback flows drain or purge queued work that cannot satisfy the fence.

## Timer Failure Semantics

Timer admission, due-point identity, catch-up, reload, and failure outcomes follow the [normative timer semantics](./system-architecture-scripting-normative-contract-tables.md#table-3-timer-semantics-matrix). Runtime-specific behavior is at-most-once DSL evaluation for an admitted timer identity: an infrastructure failure may retry independently idempotent downstream operations, but must not re-enter the DSL for the same trigger. The scheduler makes a best-effort attempt within configured capacity; individual firings are not guaranteed.

## `onLoad` Semantics and Failure Handling

The `onLoad` lifecycle event is a tenant-readiness check for scripts in a given `<tenantId, scriptPatchVersion>` before that patch becomes active. Its lifecycle and identity are defined by the [DSL lifecycle](./system-architecture-scripting-dsl-reference-and-lifecycle.md#onload-semantics) and [normative readiness contract](./system-architecture-scripting-normative-contract-tables.md#table-1-trigger-identity-required-fields):

- `onLoad` handlers run after static validation and compilation succeed, but before the patch is marked `READY` for a tenant.
- Allowed uses are limited to ephemeral or trivially recomputable runtime initialization.
- `onLoad` must not create durable or semi-durable artifacts in databases, Redis, object storage, or other shared stores.
- There is no compensating `onUnload` / `onDeactivate` lifecycle in the current architecture.
- If future requirements demand durable patch-managed shared state, the platform must add a symmetric deactivation/cleanup lifecycle first.

Failure handling:

- `onLoad` admission uses a dedicated publish-time initialization budget, not the normal live-trigger quota windows enforced by `ScriptQuotaService`.
- If `onLoad` completes successfully for a tenant, the Automation & Scripting Service may mark the patch as `READY` for that tenant.
- If `onLoad` fails with a logical or sandbox-level error, the patch is marked `FAILED` for that tenant and events that reference the failed patch are rejected at admission with `version_unavailable` or a more specific bounded variant such as `onload_failed`.
- If the dedicated `onLoad` initialization budget is exhausted before completion, the patch must fail deterministically with an explicit bounded reason.
- If `onLoad` encounters `infrastructure_error`, the service must not re-enter the DSL for that readiness identity. Only an external infrastructure operation that is independently idempotent may be retried; if the required step cannot be retried safely, readiness fails rather than executing the DSL again.

All `onLoad` runs are recorded in `script_event_audit` with `eventType=onLoad` and the target `scriptPatchVersion`; stage/outcome fields follow the [normative audit table](./system-architecture-scripting-normative-contract-tables.md#table-2-script_event_audit-stages-and-outcomes). Patch-level readiness for `<tenantId, scriptPatchVersion>` is derived from the aggregate of all required per-script runs and becomes `READY` only after every required readiness execution succeeds.
