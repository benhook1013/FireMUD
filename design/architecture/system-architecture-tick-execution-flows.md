# FireMUD Tick System: Execution Flows

This document focuses on **how tick-driven commands execute end-to-end**, including per-command phases, staging/commit, and cross-region flows.

It is intended for developers implementing or reviewing tick-driven commands, Lua scripts, and integration points with other services.

For the canonical, detailed design, see `design/architecture/system-architecture-ticks.md`.

## Implementation Notes

This document describes the target execution model. The current live runtime is narrower:

- the durable owner/status surface is currently `{tenantId, gameInstanceId}`-scoped rather than true region-scoped;
- `GetGameplayCommandStatus` is the canonical command-status API, but its live fields and state vocabulary are narrower than the accepted lifecycle described below;
- the live batch/effect substrate exists with the current gameplay-command selected-work manifest on `tick_batch`, but timer/retry/remote-follow-up source-claim manifests, cross-region result-return plumbing, and some richer command-status fields are still target-state follow-through.
- the live gameplay-command staging path still uses its current `commandId`/deterministic text-and-slot fallback when a complete authored handoff identity is unavailable. The target path is fail-closed: Game Session must reject or terminalize an item that cannot receive a complete canonical `EffectId`, rather than allowing a participant to invent identity. The target wording below is not a claim that the fallback has already been removed from code.

Naming convention: API, workflow, and EffectId prose uses `regionEpoch`. Snake-case forms such as `region_epoch`, `target_region_epoch`, and `due_tick_id` are reserved for explicitly identified SQL/storage fields, Redis payloads/keys, or schema examples.

## What This Covers

- Per-command execution phases.
- Tick staging and commit flows.
- Redis integration and Lua patterns.
- Cross-region command execution and result relay.

## Key Sections in the Main Tick Doc

The following sections in `system-architecture-ticks.md` describe execution behavior:

- **Per-Command Execution Phases** – phases a tick-driven command passes through, including an example (Cross-Region Lifesteal).
- **Tick Execution Flow** – how the executor pulls commands and coordinates work for each tick.
- **Tick Staging and Commit Flow** – how state transitions are staged, validated, and committed atomically.
- **Tick Execution and Redis Integration** – how Redis keys and Lua scripts are used to implement the commit pattern.
- **Cross-Region Command Execution and Result Relay** – patterns for sending commands across regions without shared locks.
- **Tick Chaining and Reentrant Effect Control** – how chained effects are controlled to avoid unbounded recursion or reentrancy.

When changing execution behavior, update these sections in the main tick document first, then reconcile any differences here.

## Per-Command Execution Phases (Detail)

Within a region’s tick, each command proceeds through several phases:

1. **Enqueue**
   - Game Session accepts commands from Telnet and WebSocket clients, AI, or automation.
   - Commands are enqueued into per-entity (or occasionally per-region) queues such as `tick:{tenantRegionTag}:queue:<entityId>`.
2. **Target Resolution (read-only)**
   - During the tick, the executor resolves targets from the pinned snapshot for that `<tenantId, gameInstanceId, regionId>`:
     - Single-target actions select a specific entity or room.
     - Multi-target actions derive a bounded set of entity IDs from local region state (room occupants, threat lists, groups).
   - This phase is read-only with respect to durable state; it decides *what* to touch without mutating Redis or PostgreSQL.
3. **Region-Local Mutations**
   - For purely local effects, the executor acquires the relevant entity lock(s) under `tick:{tenantRegionTag}:lock:<entityId>` and stages effects into `tick:{tenantRegionTag}:pending` via Lua.
   - Domain services apply changes under local transactions and idempotency rules keyed by the complete `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` EffectId.
4. **Cross-Region Effects (if any)**
   - For cross-region commands, the origin region:
     - Applies local-only effects first (for example, text feedback, animations).
     - Records durable follow-up work in PostgreSQL for the target entities (tick effect ledger / follow-up tables), with a stable effect identity and explicit target timeline eligibility (`target_region_epoch`, `due_tick_id`).
       - `due_tick_id` is derived from the target region’s durable status surface (target-state `GetRegionTickStatus` / `RegionStatus.lastCommittedTickId`; current-live uses the selected region’s committed tick fields from `GetRuntimeOwnershipStatus`, with `ObserveRuntimeTickProgress` as the progress feed) as `due_tick_id = target_last_committed_tick_id + delta_ticks` (immediate eligibility uses `delta_ticks = 1`).
       - Writers must persist `target_region_epoch` and `due_tick_id` from the same status read so retries and failover cannot shift eligibility non-deterministically.
     - Optionally writes a best-effort Redis hint marker such as `remote:{tenantInstanceTag}:<entityId>` (for the target entity, with the tag derived from the target `<tenantId, gameInstanceId>`) to reduce latency when the target region drains follow-ups.
     - If the command needs to wait for remote completion, the origin region creates or updates a separate durable cross-region coordinator record. This waiting state is **not** kept as a non-terminal row inside the origin tick batch.
       - Minimum coordinator fields include durable `coordinatorId`, `tenantId`, `originCommandId`, `originGameInstanceId`, `originRegionId`, `originRegionEpoch`, `originTickId`, target scope (`targetGameInstanceId`, `targetRegionId`, `targetRegionEpoch`, `dueTickId`), the originating and target leg EffectId projections, lifecycle state (`PENDING_REMOTE`, `REMOTE_APPLIED`, `REMOTE_ABANDONED`, `REMOTE_TIMEOUT_ABANDONED`, `LATE_RESULT_IGNORED`, `LATE_RESULT_RECONCILED`), origin-timeline deadline (`originDeadlineRegionEpoch`, `originDeadlineTickId`; storage may use names such as `remote_deadline_region_epoch` / `remote_deadline_tick_id`), final player-facing/result-facing projection fields when applicable (`executionOutcome`, `gameplayResult`), and `updatedAt`.
       - Target completion is returned through one durable origin-owned result path, not an unspecified transient message:
         - the target leg writes an origin-addressed result or inbox row keyed to `coordinatorId` and repeats `originCommandId`, `originGameInstanceId`, origin region/epoch/tick, target region/epoch, and target ledger/EffectId identity,
         - the row records the target terminal outcome and any command-specific result payload needed by origin reconciliation; it is not correlated only by the target `gameInstanceId` or a transient message ID,
         - origin-side processing claims and applies that result idempotently when advancing the coordinator lifecycle.
   - The target region later drains these follow-ups into its own tick pipeline and applies them under its lease and locks.
5. **Completion / Finalization (optional)**
   - Many commands do not need global awareness of “all regions finished”; origin and target regions can operate independently with eventual consistency.
   - For flows that truly require end-to-end completion (for example, complex cross-region trades), the origin region tracks success/failure from participating regions in that separate coordinator record and later applies a final status (success, partial, failed) in a subsequent origin-region tick once all responses or timeouts are observed.

Every phase must use its phase-specific durable identity so replays after failure do not double-apply logical work. Command ingress uses `(tenantId, gameInstanceId, commandId)`; source claims and selection use the durable source identity (such as a timer member ID, retry member ID, or follow-up row ID) together with its bound target/timeline; tick effects use the complete `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` EffectId; and cross-region completion uses the coordinator identity plus the target ledger/result identity. A command or source item may cross tick boundaries without changing its ingress or source identity.

### Command Ingress Acknowledgement Contract (Required)

Because enqueueing uses reset-tolerant Redis coordination queues, command acceptance must distinguish between **ingress receipt** and **durable gameplay outcome**:

- Every accepted command has a stable `commandId` before the first backend retry boundary and returns it to the caller. A capable client may generate it; for line-oriented or Telnet sessions, the first trusted Game Session/session-front-end ingress assigns and retains it before forwarding or retrying. Human players do not type or manage this identity.
- Ingress acknowledgements use explicit levels:
  - `ACCEPTED_VOLATILE` (default for gameplay commands): command is accepted into coordination flow but may still be lost within tail-loss/reset envelopes before staging.
  - `ACCEPTED_DURABLE` (exceptional, feature-specific): command intent is durably recorded outside Redis before acknowledgement and can be re-driven after coordination loss.
- For `ACCEPTED_VOLATILE`, clients and upstream services must treat acknowledgement as “accepted for processing, not guaranteed to execute” and reconcile via command outcome events/status APIs.
- Re-submission rules:
  - Re-sends with the same `commandId` must deduplicate at ingress and not create duplicate logical actions.
  - Re-sends with a new `commandId` are treated as new commands.
- Features that require “accepted means never silently lost” semantics must explicitly adopt `ACCEPTED_DURABLE` with a durable intake record and replay story documented in their design.

#### Command Outcome Convergence (Required)

Ingress deduplication is not sufficient on its own: every accepted command must also converge to a terminal command outcome, even when the underlying Redis queue entry is lost before staging.

- Minimum command lifecycle:
  - `RECEIVED` – durable dedupe record exists.
  - `ENQUEUED` – command has been accepted into volatile coordination flow.
  - `BOUND_TO_BATCH` – command has been durably tied to a specific `tick_batch_id` and `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`.
  - `TERMINAL` – command has reached a final command outcome.
- Minimum terminal command outcomes:
  - `APPLIED`
  - `ABANDONED`
  - `LOST_BEFORE_STAGING` – accepted into volatile coordination flow but never durably tied to a surviving `tick_batch_id` before reset/tail-loss/reconcile.
- Legacy live command states map to the canonical lifecycle as follows:
  - `STAGED` means `ENQUEUED` when it has only been written to the Redis source queue. It maps to `BOUND_TO_BATCH` only when a durable batch/effect record explicitly links the command to a `tick_batch_id`; the legacy value alone is not proof of binding.
  - `DRAINED` means `BOUND_TO_BATCH`; Redis `pending` was consumed into a durable tick batch and the command remains pending execution or terminal reconciliation.
  - `RETRY_QUEUED` means the command has already reached `BOUND_TO_BATCH` and its current retry source is enqueued. The prior batch binding remains part of the command lifecycle; retry source state does not make it a new ingress command.
- These legacy values are non-terminal. They must not be exposed as new canonical terminal outcomes, and purge/recovery must classify the current attempt by the durable batch binding rather than by the legacy label alone.
- Required recovery behavior:
  - Region/tenant/cluster resets and tail-loss reconciliation must drive every accepted command record that is not `BOUND_TO_BATCH` or already `TERMINAL` to an explicit terminal outcome.
  - For `ACCEPTED_VOLATILE`, `LOST_BEFORE_STAGING` is an expected terminal outcome and must be returned by command-status APIs/events rather than leaving the command indefinitely deduplicated with no execution result.
  - Re-sends with the same `(tenantId, gameInstanceId, commandId)` after a terminal outcome return that prior terminal outcome and must not enqueue a new logical command.
  - Re-sends with a new `commandId` remain new commands.
- `ACCEPTED_DURABLE` designs may replace `LOST_BEFORE_STAGING` with a stronger replay/re-drive contract, but that contract must be documented explicitly in the feature design.

#### Command Outcome Status Surface (Required)

Command outcome convergence must be externally observable through one canonical authoritative API. Optional event delivery projects the same lifecycle but does not replace lookup authority. The contract is:

- Canonical control-plane surfaces:
  - `GetGameplayCommandStatus(tenantId, gameInstanceId, commandId)` for authoritative lookup.
  - Optional `StreamCommandOutcomes` (or equivalent) for lower-latency observation of the same lifecycle.
- Lookup key:
  - `(tenantId, gameInstanceId, commandId)`
- Minimum returned fields:
  - `commandId`
  - `ackLevel`
  - `ingressStatus`
  - `executionOutcome` (nullable until terminal)
  - `gameplayResult` (nullable until terminal command result is known)
  - `failureCode` and `failureMessage` when a terminal reason is required; for purge terminalization these two existing fields are the canonical structured `{code, message}` reason
  - `tickBatchId` (nullable until `BOUND_TO_BATCH`)
  - `gameInstanceId`, `regionId`, `regionEpoch`, `tickId` (nullable until bound)
  - `updatedAt`
- Existing values such as `STAGED`, `DRAINED`, and `RETRY_QUEUED` are lifecycle progress and must map into the canonical ingress model. Values such as `PURGED` become a terminal outcome plus a structured reason rather than a competing terminal vocabulary. The canonical existing reason representation is the required pair `{failureCode, failureMessage}` returned by `GetGameplayCommandStatus` and persisted in the authoritative durable projection.
- Rich routing, automation, script, plugin, remote-leg, and diagnostic metadata may extend this response without replacing or conflating the canonical lifecycle fields.
- Terminal outcome semantics:
  - `executionOutcome = APPLIED` – command effects reached a terminal execution state and at least one batch-bound effect converged successfully enough that the command is no longer replay-pending.
  - `executionOutcome = ABANDONED` – command reached a terminal execution failure after being durably bound to a batch.
  - `executionOutcome = LOST_BEFORE_STAGING` – command never became batch-bound and was terminated by reconcile/reset handling.
  - `gameplayResult` is a separate player-facing/result-facing projection derived from the command type’s documented semantics:
    - Minimum shared vocabulary: `SUCCESS`, `PARTIAL`, `FAILED`, `TIMEOUT`, `NOT_APPLIED`.
    - `gameplayResult` may remain `null` until the command reaches terminal state.
    - `gameplayResult` must not be inferred solely from `executionOutcome`; cross-region and multi-leg commands may legitimately end as `executionOutcome = APPLIED` with `gameplayResult = PARTIAL`.
- Delivery rules:
  - `GetGameplayCommandStatus` is the authoritative source for the fields above.
  - `StreamCommandOutcomes`, if implemented, must expose the same lifecycle plus the same `executionOutcome` and `gameplayResult` vocabulary as `GetGameplayCommandStatus`.
  - Events are advisory for latency; the durable status surface is authoritative.
  - Clients must not infer command success from ingress acknowledgement alone.

Worked examples:

- Pure local success:
  - `executionOutcome = APPLIED`
  - `gameplayResult = SUCCESS`
- Batch-bound local or same-region failure:
  - The command was durably tied to a `tick_batch_id`, began normal execution, and then reached a terminal domain failure that is not a timeout path.
  - `executionOutcome = ABANDONED`
  - `gameplayResult = FAILED`
- Cross-region partial success:
  - Origin effects and at least one remote leg converged, but another remote leg reached terminal failure after timeout or explicit abandonment.
  - `executionOutcome = APPLIED`
  - `gameplayResult = PARTIAL`
- Cross-region timeout before any successful remote leg:
  - Origin coordinating effect reached terminal failure after the deadline.
  - `executionOutcome = ABANDONED`
  - `gameplayResult = TIMEOUT`
- Lost before staging during reset/tail-loss reconcile:
  - `executionOutcome = LOST_BEFORE_STAGING`
  - `gameplayResult = NOT_APPLIED`

#### Canonical Command Terminal Mapping Table

This table is the canonical shared reference for command terminal mappings used by
status APIs, replay/reset handling, and operator runbooks. Other architecture and
operations docs should link here instead of restating partial mappings in prose.

| Scenario | executionOutcome | gameplayResult | terminalReason |
| --- | --- | --- | --- |
| Pure local success | `APPLIED` | `SUCCESS` | none |
| Batch-bound local or same-region failure | `ABANDONED` | `FAILED` | failure code/message when applicable |
| Cross-region partial success | `APPLIED` | `PARTIAL` | failure code/message for the failed leg when applicable |
| Cross-region timeout before any successful remote leg | `ABANDONED` | `TIMEOUT` | failure code/message for the timeout |
| Lost before staging during reset/tail-loss reconcile | `LOST_BEFORE_STAGING` | `NOT_APPLIED` | failure code/message for the reconcile cause |
| Operator rollback purge before `BOUND_TO_BATCH` | `LOST_BEFORE_STAGING` | `NOT_APPLIED` | `failureCode=ROLLBACK_PURGED`, required `failureMessage` from ingress `reason` |
| Operator rollback purge after `BOUND_TO_BATCH` | `ABANDONED` | `NOT_APPLIED` | `failureCode=ROLLBACK_PURGED`, required `failureMessage` from ingress `reason` |

For operator rollback purge, the ingress request `reason` is required and non-blank. The durable command projection and `GetGameplayCommandStatus` response must retain and return the same structured terminal reason as `failureCode=ROLLBACK_PURGED` and `failureMessage=<the required ingress reason>`. `PURGED` is the operator action/legacy label, not a competing `executionOutcome` value. Purge may terminalize source-queued work and an explicitly purgeable batch-bound retry, but it must not rewrite work that a concurrent drain has already claimed or work that has reached an applied terminal state.

#### Ingress Deduplication Store (Required)

To make the re-submission contract enforceable across failover and scoped coordination resets, ingress deduplication must use a durable record outside Redis coordination queues:

- Game Session persists a dedupe record keyed by `(tenantId, gameInstanceId, commandId)` in PostgreSQL.
- Minimum fields:
  - immutable authenticated subject identity and gameplay actor/character identity when applicable, or the equivalent authorized internal automation/operator identity
  - canonical normalized-request fingerprint version and digest covering the command type and semantic payload
  - `ack_level` (`ACCEPTED_VOLATILE` or `ACCEPTED_DURABLE`)
  - `ingress_status` (`RECEIVED`, `ENQUEUED`, `BOUND_TO_BATCH`, `TERMINAL`)
  - `first_seen_at`, `last_seen_at`
  - `tick_batch_id` (nullable until bound)
  - canonical durable status fields for the command outcome surface:
    - `execution_outcome` (nullable until terminal execution outcome)
    - `gameplay_result` (nullable until terminal gameplay result)
    - `failure_code` and `failure_message` (required together for a terminal reason, including `ROLLBACK_PURGED`)
- Required behavior:
  - Re-send with the same `(tenantId, gameInstanceId, commandId)` returns the prior acknowledgement and must not enqueue a second logical command only when immutable subject/actor identity and normalized-request fingerprint match.
  - A same-ID mismatch in subject, actor/character, command type, or semantic payload fails closed without returning the existing acknowledgement or status.
  - `GetGameplayCommandStatus` authorizes the read against the immutable ingress identity or an explicitly authorized internal/operator route; knowledge of `commandId` is insufficient.
  - Region/tenant/cluster coordination resets do not delete this dedupe record; they only affect volatile queue state.
  - Retention is TTL-based at the SQL layer and must outlive expected client retry windows.
- `ACCEPTED_VOLATILE` remains volatile for execution semantics: the dedupe record guarantees no duplicate logical enqueue for the same `commandId`, not guaranteed eventual execution, but it does guarantee eventual convergence to a terminal command outcome.

Storage rule:

- The durable command-status surface may be implemented as:
  - a single command-ingress table carrying the minimum fields above, or
  - a command-ingress table plus a derived command-outcome projection/table.
- Canonical persisted shape:
  - Exactly one durable status record keyed by `(tenantId, gameInstanceId, commandId)` must be readable as the authoritative command outcome surface, whether it is physically stored as one row or as a joined ingress/outcome projection.
  - That durable surface must expose at least: `ackLevel`, `ingressStatus`, `tickBatchId`, bound tick coordinates when present (`gameInstanceId`, `regionId`, `regionEpoch`, `tickId`), `executionOutcome`, `gameplayResult`, `updatedAt`, and the structured terminal-reason pair `failureCode`/`failureMessage` when terminal reason applies.
  - Physical storage may use snake_case column names such as `execution_outcome`, `gameplay_result`, `failure_code`, and `failure_message`, but the logical contract above is canonical and must be documented that way in service APIs and schema docs.
  - If ingress metadata and outcome fields are split physically, the projection still behaves as one canonical record for `GetGameplayCommandStatus`; callers must not reconstruct status from Redis or by replaying effect history ad hoc.
- Atomic/versioned consistency for split storage is mandatory:
  - The ingress row and outcome projection share a monotonic `statusVersion` (a physical `row_version` is acceptable) and the same `updatedAt` for each logical transition.
  - Every lifecycle transition updates both physical records in one PostgreSQL transaction using compare-and-set on the previously observed `statusVersion`; the successful writer advances the version exactly once. A transition that loses the compare-and-set race is retried from a fresh read and must not merge fields from different versions.
  - `GetGameplayCommandStatus` reads both records in one database snapshot and only returns a joined record when their key and `statusVersion` agree. A missing or mismatched projection is an unavailable/inconsistent read that fails closed or retries; it is never reconstructed from Redis or partially joined.
  - A terminal transition writes a terminal `ingressStatus` and its matching `executionOutcome`/`gameplayResult` in the same versioned transaction. Repeating the identical terminal write is idempotent; a conflicting terminal write is rejected for reconciliation.
- Worked schema examples:
  - Single-row ingress table shape:
    - `command_ingress(tenant_id, game_instance_id, command_id, subject_id, actor_id, request_fingerprint_version, request_fingerprint, ack_level, ingress_status, tick_batch_id, region_id, region_epoch, tick_id, execution_outcome, gameplay_result, failure_code, failure_message, status_version, updated_at, ...)`
  - Split ingress plus outcome projection:
    - `command_ingress(tenant_id, game_instance_id, command_id, subject_id, actor_id, request_fingerprint_version, request_fingerprint, ack_level, ingress_status, tick_batch_id, region_id, region_epoch, tick_id, status_version, updated_at, ...)`
    - `command_outcome_projection(tenant_id, game_instance_id, command_id, execution_outcome, gameplay_result, failure_code, failure_message, status_version, updated_at, ... )`
  - In both shapes, `GetGameplayCommandStatus` reads one authoritative durable record keyed by `(tenantId, gameInstanceId, commandId)`; Redis is not part of the lookup path.
- Regardless of physical schema, `GetGameplayCommandStatus` must be able to return `executionOutcome` and `gameplayResult` from durable storage without re-walking Redis coordination state.

## Tick Execution Flow

At each tick for a `<tenantId, gameInstanceId, regionId>`, the executor:

1. Collects candidates:
   - Pulls at most one queued command per active entity into the per-entity worklist.
   - Pulls a bounded number of due timers and retries (up to configured caps per tick).
   - Optionally includes a bounded “remote follow-up drain” step (see below); due remote follow-up rows are selected and locked with database-side concurrency control, then mapped into the same per-entity worklist as local commands at the target region. The selection transaction must not mark them claimed, remove them from the due set, or treat them as runnable before the durable tick-batch transaction commits. Redis queue entries or `remote:*` markers may wake the region, but they are not the source of truth for remote follow-up ownership.
   - Selection and candidate locks alone do **not** make work durably exclusive. Until the tick batch and its source claims exist, selected work remains recoverable from its source queues/indexes.
2. Orders and locks fairly:
   - Aggregates all candidate work items per entity (queued commands, due timers, retries, and remote follow-ups) and selects **at most one** work item per entity for the current tick; any additional due work for that entity is deferred to future ticks according to the retry/timer scheduling rules.
   - Orders per-entity selections using a deterministic ordering function:
     - Primary: policy-defined priority (low-cardinality).
     - Then: stable enqueue/due ordering based only on persisted canonical fields (for example `dueTickId`, `dueAt` normalized into `due_point_normalized`, and `enqueue_seq`). `created_at` may support diagnostics, but it is not an ordering field unless it has been deterministically normalized into the canonical tuple.
     - Tie-breakers (must be deterministic): `entityId`, then `commandId`/`effectKey`.
   - Each work item type (queued command, timer, retry, remote follow-up) must expose the same canonical ordering tuple so the sort is reproducible across retries and failover:
     - `(priority, due_point_normalized, enqueue_seq, source_kind, entityId, commandId_or_effectKey)`
   - New work sources are not allowed to define custom tie-breakers; they must map into this canonical tuple.
   - After ordering, the executor locks or reserves the selected source entries with their source-specific ownership checks while they remain discoverable. These locks prevent competing executors from selecting the same candidates but are not a substitute for durable source claims.
3. Atomically binds selected work:
   - While the candidate locks are held, Game Session atomically creates the durable PostgreSQL `tick_batch`, its selected-work manifest, `SCHEDULED` ledger rows, and source claims in one transaction for the complete `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` scope. Any selected command also moves to `BOUND_TO_BATCH` in that transaction.
   - For remote follow-ups, only that transaction may set the batch claim or remove the row from the due set. A rollback releases the candidate lock and leaves the follow-up discoverable for a later selection.
4. Stages effects:
   - This step is allowed only after the durable PostgreSQL `tick_batch`, ledger rows, and source claims have committed. Redis `pending` is staging/acceleration coordination only; it is never the authoritative record or a prerequisite that can precede durable batch creation.
   - Under the region lease and entity locks, calls Lua scripts to write intended effects into `tick:{tenantRegionTag}:pending`.
5. Applies and commits:
   - Invokes domain services to apply effects under idempotent rules.
   - Runs a final Lua commit/cleanup script to reconcile Redis state, clear `pending`, and release locks.

### Canonical Work Ordering Tuple (Normative Mapping)

Every selected work item must provide the tuple
`(priority, due_point_normalized, enqueue_seq, source_kind, entityId, commandId_or_effectKey)`.

- `priority`:
  - Integer where lower values win; allowed values are from a small global enum shared by all work sources.
- `due_point_normalized`:
  - Every scheduler/timer identity carries exactly one tagged persisted due point: `dueTickId` for tick-based scheduling or `dueAt` for wall-clock scheduling; it must carry neither both nor neither.
  - Tick-based queued commands, retries, and remote follow-ups use `dueTickId`.
  - A timer using `dueAt` must be deterministically normalized to a due tick under the active scheduler contract before ordering; its wall-clock value remains available for firing-time evaluation and diagnostics. A timer using `dueTickId` uses that persisted tick directly.
  - Lower normalized value wins.
- `enqueue_seq`:
  - Strictly increasing across all candidate work sources within the complete `<tenantId, gameInstanceId, regionId>` allocation scope; one region-scoped allocator must assign it at ingress or scheduling time and persist it with the item. It is not a global, tenant-only, game-instance-only, or cross-region counter.
  - A reset bumps `regionEpoch` and restarts `tickId` at `0`, but does not reset or reuse `enqueue_seq` within the same `<tenantId, gameInstanceId, regionId>` scope. New-epoch work therefore remains ordered by the continuing allocator, while `regionEpoch` keeps old-epoch replay identities distinct.
  - Lower value wins.
- `source_kind`:
  - Fixed, low-cardinality tie-break enum (`command`, `retry`, `timer`, `remote_followup`), used only after the region-wide `enqueue_seq` if earlier fields are equal.
- `entityId`:
  - Stable deterministic tie-breaker after source ordering.
- `commandId_or_effectKey`:
  - Final deterministic tie-breaker; value must be stable across replay and failover.

No source-specific tie-break fields are allowed beyond this tuple. If a new work source cannot be mapped without adding fields, the design must update this section first.
`created_at`, `updated_at`, Redis insertion order, and wall-clock arrival order are not members of the tuple and must not be used as implicit tie-breakers. A legacy timestamp may influence ordering only after it has been deterministically normalized into the persisted `due_point_normalized` and the item has a stable `enqueue_seq`/identity tie-breaker.

### Commit Point and Replay Semantics (Conceptual)

The canonical commit model uses the same two boundaries defined in `system-architecture-ticks.md`:

- `durable_committed` – the target-state `RegionStatus.lastCommittedTickId` commit-authority boundary exposed by `GetRegionTickStatus`; in the current-live deployment, the equivalent authority is the committed tick state on the `RuntimeOwnershipStatus` row read through `GetRuntimeOwnershipStatus`, while `ObserveRuntimeTickProgress` is a progress observation rather than a separate commit authority.
- `coordination_cleared` – the “no longer in flight” Redis cleanup boundary.

Conceptually, tick commit proceeds through these phases:

1. **Durable batch creation**
   - After candidate selection and source locking, and before Redis `pending` is treated as authoritative for a tick, Game Session creates the durable tick-batch record, selected-work manifest, `SCHEDULED` ledger rows, and source claims in one PostgreSQL transaction for `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`.
   - Target-state requires exactly one durable tick batch for a given `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`:
     - The current live schema enforces uniqueness only on the durable `tick_batch_id` key (`tick_batch_tick_batch_id_key` / `idx_tick_batch_tick_batch_id`); it does not yet enforce uniqueness on the coordinate tuple.
     - A required schema migration must audit existing duplicate coordinate tuples, remediate or terminalize any duplicates through the recovery path, and add a PostgreSQL unique constraint/index on `(tenant_id, game_instance_id, region_id, region_epoch, tick_id)`. The migration and its duplicate-audit proof are part of completing this target-state invariant.
     - Until that migration is deployed, lease/fence checks and application-level duplicate detection are required safeguards but are not a database uniqueness guarantee.
     - Batch allocation is an atomic CAS-fenced durable operation. The creating transaction reads the authoritative current epoch and current-live `GetRuntimeOwnershipStatus.executorFence` opaque token, conditionally inserts or adopts the coordinate row only while both match, writes the manifest, ledger allocation, and source claims, and commits before Redis staging. A `RegionStatus.executorFence` value is target-state only unless the live ownership surface explicitly projects it.
     - If the epoch changes, the fence is stale/lost, the mapping generation changes, or a competing owner wins, the transaction must allocate no new batch and fail closed. Redis `pending` cannot be used to repair an allocation that failed this durable CAS.
     - Winner rule:
       - The target-state winner is the transaction that successfully creates the tuple-unique `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` row while holding the currently valid Redis lease and the latest live opaque `executorFence`.
       - If a competing executor finds the existing row carries the same current `executorFence`, it must reuse that row as authoritative state for replay/continuation.
       - If a competing executor finds the existing row carries an older `executorFence` than the caller's current fence, it must not continue that batch on the hot path; a dedicated fenced reconcile transaction may transfer the unfinished batch to the caller's current fence in place, preserving its unique tick coordinate, manifest, effect identities, and command bindings. If transfer preconditions fail, reconciliation abandons the stale batch and terminalizes its effects/commands under the canonical mapping; any separately retryable source work is eligible only for a later tick coordinate with new batch/effect identity. If the stored `executorFence` is newer than the caller's current fence, the caller is stale and must stop without modifying the batch, replaying effects, or requeueing source work. It must never create a second batch for the same unique coordinate or requeue work into that same coordinate.
   - The tick-batch record stores at minimum:
     - `tick_batch_id`
     - `executor_fence`
     - `lease_token` (trace/audit only; not the sole durable fence)
     - `expected_effect_count`
     - `status` (`CREATED`, `REDIS_STAGED`, `COMMITTED`, `ABANDONED`)
     - `tick_batch.status` is batch lifecycle state, not tick-effect-ledger state; effect-ledger `status` remains closed to `SCHEDULED`, `APPLIED`, and `ABANDONED`, with replay details in audit metadata.
     - the selected-work manifest for this batch
     - a correlation field that Redis `pending` can carry back (for example `tick_batch_id`)
   - The selected-work manifest is the authoritative record of which source items were chosen for the tick before Redis staging. At minimum, each selected item records:
     - the batch scope `(tenantId, gameInstanceId, regionId, regionEpoch)`; `enqueue_seq` values are allocated from the complete `<tenantId, gameInstanceId, regionId>` scope and are not reused after a `regionEpoch` reset
     - `source_kind` (`command`, `timer`, `retry`, `remote_followup`)
     - source item identity (`commandId`, timer member ID, retry member ID, or follow-up row ID)
     - `entityId`
     - the canonical ordering tuple `(priority, due_point_normalized, enqueue_seq, source_kind, entityId, commandId_or_effectKey)`
     - source-claim/removal state indicating whether the source entry still resides in Redis/PostgreSQL source structures or has been durably claimed elsewhere
   - Source-specific minimum manifest fields:
     - `command`:
       - `commandId`
       - queue key / logical queue family
       - enqueue sequence used for ordering
     - `timer`:
       - timer member ID
       - exactly one of `dueAt` or `dueTickId`
       - normalized due tick value used for ordering
     - `retry`:
       - retry member ID or effect identity
       - `retryCount`
       - `nextEligibleTickId`
     - `remote_followup`:
       - durable follow-up row ID
       - `targetRegionEpoch`
       - `dueTickId`
   - Implementations may persist additional fields for convenience, but replay and source cleanup must not depend on undocumented per-source payloads beyond this contract.
   - Tick effect ledger rows for the selected effects are inserted in the same PostgreSQL transaction as the tick-batch record with `status = SCHEDULED`.
   - Any selected command tied to the batch moves its durable command record to `BOUND_TO_BATCH` in the same transaction.
   - Source-claim rule (required):
     - Candidate source entries are selected and locked before the batch transaction, but remain discoverable until that transaction commits.
     - The transaction that creates the durable `tick_batch` and `SCHEDULED` ledger rows must also create or update the source claim tied to that `tick_batch_id`; no command, timer, retry, or remote follow-up may be marked claimed in an earlier transaction.
     - In particular, `FOR UPDATE SKIP LOCKED` on a remote follow-up only locks a candidate. Its `claimed_tick_batch_id` update or removal from the due set occurs in the same transaction as durable batch creation, never before it.
     - After that transaction commits, implementations may remove source entries immediately or leave them in place until `tick_batch.status = REDIS_STAGED`; replay uses the durable manifest and claim rather than re-selecting different work.
   - Duplicate-allocation recovery rule (required):
     - If recovery finds more than one durable row purporting to represent the same `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`, the region is inconsistent by definition.
     - Normal replay must not continue. The region is paused and explicit reconcile tooling chooses one survivor batch and converges the others to an audited terminal state before ticks resume.
   - Worked allocation-race example:
     - Executor `E1` and executor `E2` both attempt `(tenantId=7b3b074e-d597-4e9b-b96f-4f5946d26120, gameInstanceId=9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, regionId=R7, regionEpoch=13, tickId=42)`.
     - `E1` inserts a batch row while holding Redis lease token `L9001` and durable `executorFence=27`. Under the current schema, application allocation checks treat it as the candidate winner for `(7b3b074e-d597-4e9b-b96f-4f5946d26120, 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, R7, 13, 42)` but cannot claim database-enforced tuple uniqueness; after the required migration, the tuple-unique constraint makes it the only insertable row.
     - `E2` then reads the existing row.
       - If `E2` is acting under the same current `executorFence=27`, it may continue/replay from that row.
       - If `E2` now holds a later lease acquisition with `executorFence=28`, it must stop the hot path and run the fenced reconcile transaction. If the unfinished batch is transferable, that transaction changes ownership to fence `28` in place while preserving tick `42`'s row, manifest, ledger identities, and bound command state; if not transferable, it converges the old batch to terminal outcomes and only schedules explicitly retryable source work for a later tick.
       - If `E2` is acting under `executorFence=27` but reads an existing row carrying `executorFence=28`, `E2` is stale and must stop without modifying the batch, replaying effects, or requeueing source work.
     - `E2` must not overwrite the manifest, create a second batch row, or select different work for tick `42`.
     - If storage ever reveals two durable rows for `(7b3b074e-d597-4e9b-b96f-4f5946d26120, 9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78, R7, 13, 42)`, the region pauses immediately and reconcile tooling chooses the survivor before any later tick runs.
   - Canonical fence/write sequence (normative):
     1. Executor wins the Redis lease for `<tenantId, gameInstanceId, regionId>`.
     2. Game Session advances the current-live runtime ownership `executorFence` for that new ownership generation; `RegionStatus` is only the target-state durable projection when the live ownership surface is not available.
     3. The same ownership generation creates `tick_batch` and `SCHEDULED` ledger rows carrying that `executorFence`.
     4. Commit watermark advancement (`lastCommittedTickId`) succeeds only if the write still matches the same opaque `executorFence`.
     5. If another executor later acquires the lease and advances `executorFence`, older generations must stop; they may clean up only through fenced recovery paths that do not advance commit state.
2. **Redis staging complete**
   - After the durable batch exists, the executor stages the selected effects into `tick:{tenantRegionTag}:pending`, carrying the `tick_batch_id` and the expected effect count (or equivalent digest) so Redis and PostgreSQL can be correlated during recovery.
   - `tick:{tenantRegionTag}:pending` is an acceleration/coordination structure. The durable tick-batch plus ledger rows are the authoritative record of what the tick intended to stage.
3. **Domain application**
   - Domain services process staged effects under idempotent rules keyed by the complete `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` EffectId and update authoritative PostgreSQL state in their own databases.
   - Game Session records the outcome of each effect in its tick effect ledger (`SCHEDULED` → `APPLIED` or `ABANDONED`) based on the domain calls’ return semantics. Domain services do not write to the Game Session ledger directly.
4. **Commit visibility**
   - Once all ledger rows that belong to that tick batch are terminal (`APPLIED` or `ABANDONED`), the target-state deployment advances the commit authority `RegionStatus.lastCommittedTickId` for the current `regionEpoch` under the same `executorFence`, and only then emits the target-state tick heartbeat for that `(regionEpoch, tickId)`. The current-live deployment instead advances the committed tick fields on `RuntimeOwnershipStatus` under the current-live opaque fence; `ObserveRuntimeTickProgress` reports that progress, and neither `RegionStatus` nor `StreamTickHeartbeats` is implied to be live.
   - Cross-region coordinator rows such as `PENDING_REMOTE` are not part of this terminal set; they are durable workflow state outside the committing tick batch.
5. **Coordination cleanup**
   - A final commit/cleanup script clears the `pending` entry for the tick and releases any entity locks for that region.
   - Cleanup is a required part of “tick is no longer in flight”; if an executor crashes after commit visibility but before cleanup, crash recovery must clear/abandon the `pending` entry before any subsequent tick stages new work.

From the perspective of the `(regionEpoch, tickId)` timeline:

- A tick is `durable_committed` once:
  - All ledger rows owned by that tick batch for `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` have reached a terminal state (`APPLIED` or `ABANDONED`), and
  - The target-state commit authority `RegionStatus.lastCommittedTickId`, or the current-live committed tick fields on `RuntimeOwnershipStatus`, has advanced to that `(regionEpoch, tickId)` under the applicable fence. `ObserveRuntimeTickProgress` may confirm current-live progress but does not replace the durable authority.
- A tick is `coordination_cleared` once:
  - There is no remaining `pending` entry for that tick in Redis and lock cleanup for that tick has completed.
- Any state before `durable_committed` is **replayable**:
  - Executors may crash after staging but before all effects are applied; the next executor replays remaining SCHEDULED entries using ledger and idempotency rules.
  - AOF replay or tail-loss may cause staging scripts to be re-run; domain idempotency guards and the ledger ensure that replays converge to the same terminal outcome.
- Recovery treats PostgreSQL as the source of truth for staging intent:
  - `tick_batch` exists, Redis `pending` missing:
    - Recovery replays directly from the durable batch manifest and `SCHEDULED` ledger rows to drive effects to terminal `APPLIED` or `ABANDONED` outcomes; missing Redis state is not treated as “no work existed”.
    - First implementation does **not** re-stage old ticks through the normal hot-path `pending` scripts once Redis state is missing. The durable batch manifest is authoritative for what was selected, and replay proceeds without requiring the old tick to be materialized back into Redis.
    - If source entries were intentionally left in place until `REDIS_STAGED`, recovery may reconcile those source structures against the durable batch manifest to clean up or reclassify them, but it must not re-select different work for the same `tick_batch_id`.
  - Redis `pending` exists, `tick_batch` missing:
    - Recovery treats the Redis entry as orphaned coordination state, clears it via the cleanup path, and alerts; no executor is allowed to commit work from `pending` that lacks a durable batch.
  - Both exist but `expected_effect_count` or a stored digest disagrees:
    - Recovery pauses the region, marks the batch inconsistent, and requires reconcile tooling before ticks resume. Silent “best effort” continuation is not allowed.
- If a crash occurs after `durable_committed` but before `coordination_cleared`, recovery must finish cleanup before the next tick stages new work; this window does not regress the durable commit watermark.

The **TickScheduler** in Game Session enforces a **single in-flight tick per region** invariant and derives tick positions from durable state:

- A region is considered busy while prior durable or coordination state has not reached `coordination_cleared`: this includes an existing non-terminal `tick_batch`, any `SCHEDULED` ledger rows, or `tick:{tenantRegionTag}:pending` for an in-flight `tickId`. Missing Redis `pending` does not make unfinished durable work idle.
- The scheduler does not start a new tick for that `<tenantId, gameInstanceId, regionId>` until the previous tick is `coordination_cleared` as part of normal cleanup or explicitly handled during crash recovery.
- The target-state scheduler obtains the current `(regionEpoch, tickId)` baseline and commit authority from `RegionStatus` through `GetRegionTickStatus`. The current-live scheduler obtains owner, fence, and committed tick fields from `RuntimeOwnershipStatus` through `GetRuntimeOwnershipStatus`, and uses `ObserveRuntimeTickProgress` as the live region progress feed; neither deployment treats `tick:{tenantRegionTag}:meta.current_tick_id` as the authority.
- The scheduler and recovery tooling treat the current-live opaque `executorFence` as the owner generation; any batch or commit attempt that carries an older fence must stop rather than trying to race the new owner.
- Additional work enqueued for the same region while a tick is in flight is modeled as retries or follow-up work for a later `tickId`, not as a second concurrent tick.
- On a non-reset cold start where Coordination Redis is empty:
  - In the current-live deployment, the next winning executor bootstraps owner, selected region, opaque `executorFence`, and the committed tick baseline from `GetRuntimeOwnershipStatus`, then observes `ObserveRuntimeTickProgress` to confirm the active `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` progress before deriving the next requested tick.
  - In the target-state deployment, the equivalent baseline comes from `GetRegionTickStatus` backed by `RegionStatus.lastCommittedTickId`; this target-state surface must not be treated as live before it is shipped.
  - The first successful hot-path staging script for the derived tick initializes `tick:{tenantRegionTag}:meta.current_tick_id` to that requested tick as a Redis-side guard only. It must not override the active deployment's ownership/progress adapter or become the source of truth for the baseline.
  - Schedulers and operators continue to treat the active deployment's status/progress surface as authoritative for cold-start baseline selection.

If FireMUD later introduces limited intra-region parallelism (for example by sharding a single region into buckets of entities), this model will evolve to use **per-bucket pending keys** such as `tick:{tenantRegionTag}:bucket:<bucketId>:pending` plus matching idempotency and locking rules. Until such a change is explicitly designed, the invariant remains one `pending` entry and one in-flight tick per `<tenantId, gameInstanceId, regionId>`.

Commands that cannot complete inside the configured tick budget are deferred via retry queues rather than blocking the current tick.

Retry queues store, for each action, a retry counter and `next_eligible_tick_id` so that:

- Retries are scheduled for future ticks using an exponential backoff in ticks (for example, `nextTick = currentTick + min(2^retryCount, MAX_BACKOFF_TICKS)`).
- Retries are appended to the originating entity’s queue, preserving per-entity FIFO ordering.
- After a bounded number of attempts (for example `MAX_RETRIES`), the action is marked permanently failed and surfaced via metrics and player-visible errors rather than retried indefinitely.

## Remote Follow-Up Drain (Cross-Region Budgets)

Remote follow-ups (work created in one region but owned by entities in another) are treated as first-class tick work in the target region:

- Each tick includes a bounded “remote drain” step:
  - The executor pulls at most `MAX_REMOTE_FOLLOWUPS_PER_TICK` due follow-up rows from PostgreSQL for entities in the region.
  - A per-entity cap such as `MAX_REMOTE_FOLLOWUPS_PER_ENTITY_PER_TICK` prevents one hot entity from consuming the entire budget.
- Selection favors oldest-due follow-ups while avoiding starvation:
  - Candidate selection and locking use database-side concurrency control (for example, `FOR UPDATE SKIP LOCKED` or equivalent) so multiple executors do not select the same follow-up.
  - The remote source claim is created only in the same durable PostgreSQL transaction that creates the target tick batch, selected-work manifest, and ledger rows. Only after that transaction commits may the follow-up be removed from the due set or treated as runnable. Redis `remote:*` hints and any per-entity queue projections are latency hints only; a crash before commit leaves the follow-up due in PostgreSQL, and a crash after the claim replays from the batch manifest rather than from Redis.
  - Intake per entity per tick is limited so other entities with due work still make progress.
- When remote follow-up queues grow:
  - If due follow-ups exceed a threshold, the region is marked `DEGRADED` and emits metrics such as:
    - `remote_followups_due_total`
    - `remote_followups_drain_lag_ms`
    - `remote_followups_backlog_over_budget_total`
  - Those Prometheus metrics are aggregate process signals only and may use only bounded labels such as `source_kind`, `status`, or `scope_class`. Region-specific diagnosis comes from the durable runtime ownership/control-plane reads and follow-up records, not raw `tenantId` / `gameInstanceId` / `regionId` metric labels.
  - The executor may temporarily bias part of the per-tick budget toward draining remote follow-ups (within the configured caps) to reduce cross-region lag.
  - Admission control applies at the origin: when the target region is degraded or backlog is high, new cross-region actions may be delayed, rate-limited, or rejected with a clear error so the system sheds load instead of accumulating an unbounded remote backlog.
    - The current-live signal for “target region degraded / unhealthy” combines `GetRuntimeOwnershipStatus` with `ObserveRuntimeTickProgress`; the target-state equivalent is `GetRegionTickStatus` backed by `RegionStatus`. Neither deployment may use best-effort Redis hint keys such as `remote:*` as admission authority.

Best-effort hint markers (`remote:{tenantInstanceTag}:<entityId>`) are only allowed to influence latency. Correctness is derived solely from the durable follow-up records in PostgreSQL and the idempotent handling of those records in the target region’s tick pipeline.

## Cross-Region Commands Under Resets

Cross-region flows participate in the same coordination timeline and reset rules as purely local ticks:

- Each leg of a cross-region command is tied to a specific `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` on its origin or target region, and its durable state is tracked in the tick effect ledger and follow-up tables described in `system-architecture-tick-failures-and-operations.md`.
- Any origin-side waiting or aggregation state for those legs lives in a separate durable coordinator record. It must not keep the origin tick batch open or prevent `lastCommittedTickId` from advancing.
- When a region/tenant/cluster reset bumps `regionEpoch` for a region, any surviving `SCHEDULED` ledger rows or follow-ups from the old epoch converge to `ABANDONED` under the ledger rules; they are not silently retried on the new epoch.
- Origin regions must treat:
  - Explicit `ABANDONED` outcomes from target-region effects as a failed or partial remote leg and, where relevant, surface that status to players (for example, “the remote portion of your action failed”).
  - Timeouts waiting for remote results as equivalent to a failed remote leg and mark their own coordinating effects as `ABANDONED` with an appropriate reason once the timeout elapses.
- By default, cross-region commands are **best-effort and eventually consistent**, not globally atomic across regions:
  - Each leg still satisfies the “no double-apply” and APPLIED/ABANDONED convergence invariants.
  - Features that require stronger “all-or-nothing across regions” behavior must document that requirement explicitly and provide their own higher-level coordination on top of these primitives.

### Cross-Region Leg Lifecycle and Late-Result Policy (Required)

To avoid ambiguity around timeouts and late replies, every cross-region command with origin/target legs uses a shared lifecycle:

1. `PENDING_REMOTE` – origin leg has created durable follow-up(s) and is waiting for target outcome until a defined deadline.
2. `REMOTE_APPLIED` – target reported terminal success for the leg.
3. `REMOTE_ABANDONED` – target reported terminal failure (`ABANDONED`) for the leg.
4. `REMOTE_TIMEOUT_ABANDONED` – origin reached deadline without terminal remote result and marked the leg `ABANDONED`.
5. `LATE_RESULT_IGNORED` or `LATE_RESULT_RECONCILED` – terminal policy when a remote success/failure arrives after timeout.

Required policy defaults:

- Deadlines are tick-based on the origin region timeline and recorded durably with the origin coordinating effect (`originDeadlineRegionEpoch`, `originDeadlineTickId`; storage may use names such as `remote_deadline_region_epoch` / `remote_deadline_tick_id`), not inferred from wall-clock timers.
- The origin region is the only clock that evaluates remote completion timeouts. Target-region `dueTickId` controls when the remote leg first becomes eligible to run; it does not define when the origin gives up waiting for the result.
- If the origin scope is canonical `PAUSED` or `STALLED`, normal tick-clock timeout progression is suspended. A durable recovery/controller path may still converge overdue coordinators to `REMOTE_TIMEOUT_ABANDONED` using the stored origin deadline plus control-plane health evidence when normal origin ticks are not advancing.
- The coordinator lifecycle above is outside the committing origin tick batch:
  - The origin tick that creates the remote follow-up still commits normally once its own batch rows are terminal.
  - Later remote results or timeouts enqueue subsequent origin-region work or update the separate coordinator record; they do not retroactively keep the original tick non-terminal.
- Remote result return-path contract:
  - target regions do not mutate origin coordinator rows directly through ad hoc RPCs or transient bus messages;
  - they write one durable origin-addressed result row keyed by coordinator identity;
  - origin-side reconciliation is the only component that advances the coordinator from `PENDING_REMOTE` to `REMOTE_APPLIED`, `REMOTE_ABANDONED`, `REMOTE_TIMEOUT_ABANDONED`, `LATE_RESULT_IGNORED`, or `LATE_RESULT_RECONCILED`.
- If origin has already reached `REMOTE_TIMEOUT_ABANDONED`, any later remote result must not silently mutate prior terminal state:
  - Default: record `LATE_RESULT_IGNORED` for observability and keep origin terminal state unchanged.
  - Feature-specific override: `LATE_RESULT_RECONCILED` is allowed only if the feature documents an explicit reconciliation/compensation flow.
- Every cross-region command type must explicitly declare one of two late-result classes in its design:
  - `late_result_safe_to_ignore`
  - `late_result_requires_reconciliation`
- Flows with paired player-visible consequences (for example remote damage plus local heal/refund/reward/economic settlement) must not use the default ignore policy unless the design proves that ignoring the late result cannot strand origin-side state.
- For `LATE_RESULT_RECONCILED`, compensation and external side effects must use outbox/saga mechanisms outside the tick loop.

Worked reset example:

1. Region A creates a coordinator row for `commandId=C123` in state `PENDING_REMOTE` and enqueues a follow-up for region B.
2. Before region B reports a terminal result, region A undergoes a scoped reset that bumps `originRegionEpoch`.
3. The old-epoch coordinator row does not keep the old tick open and is reconciled to a terminal abandoned/timeout-equivalent outcome under the reset rules for the command type.
4. Any later remote result from the old epoch is treated under the late-result policy for that command and must not silently reopen or mutate the old epoch’s committed tick state.

## Tick Chaining and Reentrant Effect Control

Some effects (for example, explosions, chained spells, scripted traps) spawn follow-up actions. To keep this behavior bounded:

- Each action tracks a `tickChainDepth` that increments whenever it spawns follow-up work.
- A configuration such as `MAX_TICK_CHAIN_DEPTH` (default 8) defines the maximum allowed chain depth for a single originating action.
- When a follow-up would exceed `MAX_TICK_CHAIN_DEPTH`:
  - The new action is not enqueued.
  - Existing committed effects remain in place.
  - A warning is logged so designers can adjust the feature or its tuning, and the player may receive a message indicating the chain was halted.

Because chained effects still respect the “one action per entity per tick” rule, this depth guard is primarily a protection against runaway re-entrancy and unbounded script-driven chain reactions.

## Worked Example: Cross-Region Lifesteal Command

To illustrate how cross-region flows compose from the phases above, consider a **lifesteal spell** where a caster in region A damages a target in region B and heals based on the actual damage dealt:

1. **Enqueue (origin region)**
   - The caster issues a `LIFESTEAL <target>` command from a room in `<tenantId, gameInstanceId, regionA>`.
   - Game Session enqueues the command under the caster’s per-entity queue key in Redis.
2. **Target Resolution (origin region, read-only)**
   - During the next tick for `<tenantId, gameInstanceId, regionA>`, the executor:
     - Resolves which remote entity in `<tenantId, gameInstanceId, regionB>` is the intended target.
     - Validates that a cross-region action is allowed (line of sight, range, permissions) using the pinned snapshot and metadata.
   - No HP or inventory state is changed yet; this phase only determines the target and target region.
3. **Damage Leg (target region)**
   - The origin region records durable follow-up work for the target entity in PostgreSQL (tick effect ledger / follow-up tables), attributed to `<tenantId, gameInstanceId, regionB>` and keyed by a stable effect identity.
   - It may also write a best-effort hint marker such as `remote:{tenantInstanceTag}:<entityId>` (for the target entity under the target `<tenantId, gameInstanceId>`) to reduce latency, but correctness does not depend on that marker.
   - In the next tick for `<tenantId, gameInstanceId, regionB>`, the target region’s executor:
     - Computes the damage amount as a percentage of the target’s authoritative current HP.
     - Acquires the target’s lock (`tick:{tenantRegionTag}:lock:<entityId>` for the target entity) and applies damage via Entity Management using the complete `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` EffectId.
   - Writes a durable origin-addressed result row for region A containing `coordinatorId`, `originGameInstanceId`, `originRegionId`, `originRegionEpoch`, the target leg identity, `casterEntityId`, the actual `damageApplied`, and the target terminal outcome. Origin reconciliation verifies those fields against its durable coordinator row before applying the result.
4. **Heal Leg (origin region)**
   - When region A receives the lifesteal result, it enqueues a local “apply lifesteal heal” command for the caster.
   - In a subsequent tick for `<tenantId, gameInstanceId, regionA>`, the executor:
     - Acquires the caster’s lock.
     - Applies a heal up to `damageApplied` (subject to HP rules) using Entity Management and tick idempotency.
5. **Player Feedback and Optional Coordination**
   - The origin region may:
     - Immediately show “You cast Lifesteal…” once the initial command is accepted.
     - Show damage and heal messages as the remote and local legs complete.
   - If stricter “all-or-nothing” semantics are required, the origin region can track whether both damage and heal legs have reported success and apply a final status (success, partial, failed), but most combat flows rely on the default eventual consistency.

Throughout this sequence:

- Each leg is idempotent and keyed by the complete `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` EffectId in the domain services.
- Region executors never hold cross-region locks; they coordinate via queued commands, durable follow-up records, and durable origin-addressed result rows.
- Retries due to lock contention or transient failures are handled by the standard retry queues and idempotent handlers in each region without breaking the overall experience.
