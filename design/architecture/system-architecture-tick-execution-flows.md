# FireMUD Tick System: Execution Flows

This document focuses on **how tick-driven commands execute end-to-end**, including per-command phases, staging/commit, and cross-region flows.

It is intended for developers implementing or reviewing tick-driven commands, Lua scripts, and integration points with other services.

For the canonical, detailed design, see `design/architecture/system-architecture-ticks.md`.

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
   - During the tick, the executor resolves targets from the pinned snapshot for that `<tenantId, regionId>`:
     - Single-target actions select a specific entity or room.
     - Multi-target actions derive a bounded set of entity IDs from local region state (room occupants, threat lists, groups).
   - This phase is read-only with respect to durable state; it decides *what* to touch without mutating Redis or PostgreSQL.
3. **Region-Local Mutations**
   - For purely local effects, the executor acquires the relevant entity lock(s) under `tick:{tenantRegionTag}:lock:<entityId>` and stages effects into `tick:{tenantRegionTag}:pending` via Lua.
   - Domain services apply changes under local transactions and idempotency rules keyed by `(tenantId, regionId, region_epoch, tickId, effectKey)`.
4. **Cross-Region Effects (if any)**
   - For cross-region commands, the origin region:
     - Applies local-only effects first (for example, text feedback, animations).
     - Records durable follow-up work in PostgreSQL for the target entities (tick effect ledger / follow-up tables), with a stable effect identity and explicit target timeline eligibility (`target_region_epoch`, `due_tick_id`).
       - `due_tick_id` is derived from the target region’s durable status surface (for example `GetRegionTickStatus` / `RegionStatus.lastCommittedTickId`) as `due_tick_id = target_last_committed_tick_id + delta_ticks` (immediate eligibility uses `delta_ticks = 1`).
       - Writers must persist `target_region_epoch` and `due_tick_id` from the same status read so retries and failover cannot shift eligibility non-deterministically.
     - Optionally writes a best-effort Redis hint marker such as `remote:<tenantId>:<entityId>` (for the target entity) to reduce latency when the target region drains follow-ups.
     - If the command needs to wait for remote completion, the origin region creates or updates a separate durable cross-region coordinator record. This waiting state is **not** kept as a non-terminal row inside the origin tick batch.
   - The target region later drains these follow-ups into its own tick pipeline and applies them under its lease and locks.
5. **Completion / Finalization (optional)**
   - Many commands do not need global awareness of “all regions finished”; origin and target regions can operate independently with eventual consistency.
   - For flows that truly require end-to-end completion (for example, complex cross-region trades), the origin region tracks success/failure from participating regions in that separate coordinator record and later applies a final status (success, partial, failed) in a subsequent origin-region tick once all responses or timeouts are observed.

Every phase must be idempotent with respect to `(region_epoch, tickId)` and effect identity so replays after failure do not double-apply effects.

### Command Ingress Acknowledgement Contract (Required)

Because enqueueing uses reset-tolerant Redis coordination queues, command acceptance must distinguish between **ingress receipt** and **durable gameplay outcome**:

- Every accepted command receives a stable `commandId` generated client-side or by Game Session and returned to the caller.
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
  - `BOUND_TO_BATCH` – command has been durably tied to a specific `tick_batch_id` and `(tenantId, regionId, region_epoch, tickId)`.
  - `TERMINAL` – command has reached a final command outcome.
- Minimum terminal command outcomes:
  - `APPLIED`
  - `ABANDONED`
  - `LOST_BEFORE_STAGING` – accepted into volatile coordination flow but never durably tied to a surviving `tick_batch_id` before reset/tail-loss/reconcile.
- Required recovery behavior:
  - Region/tenant/cluster resets and tail-loss reconciliation must drive every accepted command record that is not `BOUND_TO_BATCH` or already `TERMINAL` to an explicit terminal outcome.
  - For `ACCEPTED_VOLATILE`, `LOST_BEFORE_STAGING` is an expected terminal outcome and must be returned by command-status APIs/events rather than leaving the command indefinitely deduplicated with no execution result.
  - Re-sends with the same `(tenantId, gameInstanceId, commandId)` after a terminal outcome return that prior terminal outcome and must not enqueue a new logical command.
  - Re-sends with a new `commandId` remain new commands.
- `ACCEPTED_DURABLE` designs may replace `LOST_BEFORE_STAGING` with a stronger replay/re-drive contract, but that contract must be documented explicitly in the feature design.

#### Command Outcome Status Surface (Required)

Command outcome convergence must be externally observable through one canonical status surface. Whether this is implemented as an API, event stream, or both, the contract is:

- Canonical control-plane surfaces:
  - `GetCommandStatus(tenantId, gameInstanceId, commandId)` for authoritative lookup.
  - Optional `StreamCommandOutcomes` (or equivalent) for lower-latency observation of the same lifecycle.
- Lookup key:
  - `(tenantId, gameInstanceId, commandId)`
- Minimum returned fields:
  - `commandId`
  - `ackLevel`
  - `ingressStatus`
  - `executionOutcome` (nullable until terminal)
  - `gameplayResult` (nullable until terminal command result is known)
  - `tickBatchId` (nullable until `BOUND_TO_BATCH`)
  - `regionId`, `regionEpoch`, `tickId` (nullable until bound)
  - `updatedAt`
- Terminal outcome semantics:
  - `executionOutcome = APPLIED` – command effects reached a terminal execution state and at least one batch-bound effect converged successfully enough that the command is no longer replay-pending.
  - `executionOutcome = ABANDONED` – command reached a terminal execution failure after being durably bound to a batch.
  - `executionOutcome = LOST_BEFORE_STAGING` – command never became batch-bound and was terminated by reconcile/reset handling.
  - `gameplayResult` is a separate player-facing/result-facing projection derived from the command type’s documented semantics:
    - Minimum shared vocabulary: `SUCCESS`, `PARTIAL`, `FAILED`, `TIMEOUT`.
    - `gameplayResult` may remain `null` until the command reaches terminal state.
    - `gameplayResult` must not be inferred solely from `executionOutcome`; cross-region and multi-leg commands may legitimately end as `executionOutcome = APPLIED` with `gameplayResult = PARTIAL`.
- Delivery rules:
  - `GetCommandStatus` is the authoritative source for the fields above.
  - `StreamCommandOutcomes`, if implemented, must expose the same lifecycle plus the same `executionOutcome` and `gameplayResult` vocabulary as `GetCommandStatus`.
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
  - `gameplayResult = FAILED`

#### Canonical Command Terminal Mapping Table

This table is the canonical shared reference for command terminal mappings used by
status APIs, replay/reset handling, and operator runbooks. Other architecture and
operations docs should link here instead of restating partial mappings in prose.

| Scenario | executionOutcome | gameplayResult |
| --- | --- | --- |
| Pure local success | `APPLIED` | `SUCCESS` |
| Batch-bound local or same-region failure | `ABANDONED` | `FAILED` |
| Cross-region partial success | `APPLIED` | `PARTIAL` |
| Cross-region timeout before any successful remote leg | `ABANDONED` | `TIMEOUT` |
| Lost before staging during reset/tail-loss reconcile | `LOST_BEFORE_STAGING` | `FAILED` |

#### Ingress Deduplication Store (Required)

To make the re-submission contract enforceable across failover and scoped coordination resets, ingress deduplication must use a durable record outside Redis coordination queues:

- Game Session persists a dedupe record keyed by `(tenantId, gameInstanceId, commandId)` in PostgreSQL.
- Minimum fields:
  - `ack_level` (`ACCEPTED_VOLATILE` or `ACCEPTED_DURABLE`)
  - `ingress_status` (`RECEIVED`, `ENQUEUED`, `BOUND_TO_BATCH`, `TERMINAL`)
  - `first_seen_at`, `last_seen_at`
  - `tick_batch_id` (nullable until bound)
  - canonical durable status fields for the command outcome surface:
    - `execution_outcome` (nullable until terminal execution outcome)
    - `gameplay_result` (nullable until terminal gameplay result)
- Required behavior:
  - Re-send with same `(tenantId, gameInstanceId, commandId)` returns the prior acknowledgement and must not enqueue a second logical command.
  - Region/tenant/cluster coordination resets do not delete this dedupe record; they only affect volatile queue state.
  - Retention is TTL-based at the SQL layer and must outlive expected client retry windows.
- `ACCEPTED_VOLATILE` remains volatile for execution semantics: the dedupe record guarantees no duplicate logical enqueue for the same `commandId`, not guaranteed eventual execution, but it does guarantee eventual convergence to a terminal command outcome.

Storage rule:

- The durable command-status surface may be implemented as:
  - a single command-ingress table carrying the minimum fields above, or
  - a command-ingress table plus a derived command-outcome projection/table.
- Canonical persisted shape:
  - Exactly one durable status record keyed by `(tenantId, gameInstanceId, commandId)` must be readable as the authoritative command outcome surface, whether it is physically stored as one row or as a joined ingress/outcome projection.
  - That durable surface must expose at least: `ackLevel`, `ingressStatus`, `tickBatchId`, bound tick coordinates when present (`regionId`, `regionEpoch`, `tickId`), `executionOutcome`, `gameplayResult`, and `updatedAt`.
  - Physical storage may use snake_case column names such as `execution_outcome` / `gameplay_result`, but the logical contract above is canonical and must be documented that way in service APIs and schema docs.
  - If ingress metadata and outcome fields are split physically, the projection still behaves as one canonical record for `GetCommandStatus`; callers must not reconstruct status from Redis or by replaying effect history ad hoc.
- Worked schema examples:
  - Single-row ingress table shape:
    - `command_ingress(tenant_id, game_instance_id, command_id, ack_level, ingress_status, tick_batch_id, region_id, region_epoch, tick_id, execution_outcome, gameplay_result, updated_at, ...)`
  - Split ingress plus outcome projection:
    - `command_ingress(tenant_id, game_instance_id, command_id, ack_level, ingress_status, tick_batch_id, region_id, region_epoch, tick_id, ...)`
    - `command_outcome_projection(tenant_id, game_instance_id, command_id, execution_outcome, gameplay_result, updated_at, ...)`
  - In both shapes, `GetCommandStatus` reads one authoritative durable record keyed by `(tenantId, gameInstanceId, commandId)`; Redis is not part of the lookup path.
- Regardless of physical schema, `GetCommandStatus` must be able to return `executionOutcome` and `gameplayResult` from durable storage without re-walking Redis coordination state.

## Tick Execution Flow

At each tick for a `<tenantId, regionId>`, the executor:

1. Collects work:
   - Pulls at most one queued command per active entity into the per-entity worklist.
   - Pulls a bounded number of due timers and retries (up to configured caps per tick).
   - Optionally includes a bounded “remote follow-up drain” step (see below); drained remote follow-ups are enqueued into the same per-entity queues as local commands at the target region.
   - Selection alone does **not** make work exclusive yet. Until the tick batch and durable claims exist, the selected work remains recoverable from its source queues/indexes.
2. Orders fairly:
   - Aggregates all candidate work items per entity (queued commands, due timers, retries, and remote follow-ups) and selects **at most one** work item per entity for the current tick; any additional due work for that entity is deferred to future ticks according to the retry/timer scheduling rules.
   - Orders per-entity selections using a deterministic ordering function:
     - Primary: policy-defined priority (low-cardinality).
     - Then: stable enqueue/due ordering based only on persisted fields (for example `due_tick_id`, `enqueue_seq`, or `created_at` captured into Redis/PostgreSQL at enqueue time).
     - Tie-breakers (must be deterministic): `entityId`, then `commandId`/`effectKey`.
   - Each work item type (queued command, timer, retry, remote follow-up) must expose the same canonical ordering tuple so the sort is reproducible across retries and failover:
     - `(priority, due_tick_id_or_due_ms_normalized, enqueue_seq, source_kind, entityId, commandId_or_effectKey)`
   - New work sources are not allowed to define custom tie-breakers; they must map into this canonical tuple.
3. Stages effects:
   - Under the region lease and entity locks, calls Lua scripts to write intended effects into `tick:{tenantRegionTag}:pending`.
4. Applies and commits:
   - Invokes domain services to apply effects under idempotent rules.
   - Runs a final Lua commit/cleanup script to reconcile Redis state, clear `pending`, and release locks.

### Canonical Work Ordering Tuple (Normative Mapping)

Every selected work item must provide the tuple
`(priority, due_tick_id_or_due_ms_normalized, enqueue_seq, source_kind, entityId, commandId_or_effectKey)`.

- `priority`:
  - Integer where lower values win; allowed values are from a small global enum shared by all work sources.
- `due_tick_id_or_due_ms_normalized`:
  - Tick-based items (`queued command`, `retry`, `remote follow-up`) use `due_tick_id`.
  - Timer items use a durable normalized due value recorded when the timer is scheduled, typically `due_tick_id`, or an equivalent persisted normalization derived from the region cadence that was in effect when the timer was created.
  - Lower normalized value wins.
- `enqueue_seq`:
  - Monotonic per region source stream; assigned at ingress/scheduling time and persisted with the item.
  - Lower value wins.
- `source_kind`:
  - Fixed, low-cardinality tie-break enum (`command`, `retry`, `timer`, `remote_followup`), sorted lexicographically by canonical enum order.
- `entityId`:
  - Stable deterministic tie-breaker after source ordering.
- `commandId_or_effectKey`:
  - Final deterministic tie-breaker; value must be stable across replay and failover.

No source-specific tie-break fields are allowed beyond this tuple. If a new work source cannot be mapped without adding fields, the design must update this section first.

### Commit Point and Replay Semantics (Conceptual)

The canonical commit model uses the same two boundaries defined in `system-architecture-ticks.md`:

- `durable_committed` – the durable heartbeat/RegionStatus commit boundary.
- `coordination_cleared` – the “no longer in flight” Redis cleanup boundary.

Conceptually, tick commit proceeds through these phases:

1. **Durable batch creation**
   - Before Redis `pending` is treated as authoritative for a tick, Game Session creates a durable tick-batch record in PostgreSQL for `(tenantId, regionId, region_epoch, tickId)`.
   - Exactly one durable tick batch may exist for a given `(tenantId, regionId, region_epoch, tickId)`:
     - PostgreSQL enforces a unique key on `(tenantId, regionId, region_epoch, tickId)`.
     - Batch allocation is lease-fenced by the durable `executorFence` from `RegionStatus`; the creating transaction reads and records that fence on the batch row while the corresponding Redis lease is still valid.
     - Winner rule:
       - The winner is the transaction that successfully creates the unique `(tenantId, regionId, region_epoch, tickId)` row while holding the currently valid Redis lease and the latest durable `executorFence`.
       - If a competing executor finds the existing row carries the same current `executorFence`, it must reuse that row as authoritative state for replay/continuation.
       - If a competing executor finds the existing row carries an older or newer `executorFence`, it must not continue that batch unless reconcile explicitly authorizes it. Recovery/reconcile decides whether the earlier batch is continued or abandoned.
   - The tick-batch record stores at minimum:
     - `tick_batch_id`
     - `executor_fence`
     - `lease_token` (trace/audit only; not the sole durable fence)
     - `expected_effect_count`
     - `status` (`CREATED`, `REDIS_STAGED`, `COMMITTED`, `ABANDONED`)
     - the selected-work manifest for this batch
     - a correlation field that Redis `pending` can carry back (for example `tick_batch_id`)
   - The selected-work manifest is the authoritative record of which source items were chosen for the tick before Redis staging. At minimum, each selected item records:
     - `source_kind` (`command`, `timer`, `retry`, `remote_followup`)
     - source item identity (`commandId`, timer member ID, retry member ID, or follow-up row ID)
     - `entityId`
     - the canonical ordering tuple `(priority, due_tick_id_or_due_ms_normalized, enqueue_seq, source_kind, entityId, commandId_or_effectKey)`
     - source-claim/removal state indicating whether the source entry still resides in Redis/PostgreSQL source structures or has been durably claimed elsewhere
   - Source-specific minimum manifest fields:
     - `command`:
       - `commandId`
       - queue key / logical queue family
       - enqueue sequence used for ordering
     - `timer`:
       - timer member ID
       - `dueMs`
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
     - Any selected command/timer/retry/remote follow-up must remain discoverable from its source structure until it is durably tied to the `tick_batch_id`.
     - Implementations may satisfy this either by leaving source entries in place until `tick_batch.status = REDIS_STAGED`, or by creating durable claim rows tied to `tick_batch_id` before removing the source entry.
     - Removing selected work from its source queue/index before one of those durable conditions is met is not allowed.
   - Duplicate-allocation recovery rule (required):
     - If recovery finds more than one durable row purporting to represent the same `(tenantId, regionId, region_epoch, tickId)`, the region is inconsistent by definition.
     - Normal replay must not continue. The region is paused and explicit reconcile tooling chooses one survivor batch and converges the others to an audited terminal state before ticks resume.
   - Worked allocation-race example:
     - Executor `E1` and executor `E2` both attempt `(tenantId=T1, regionId=R7, region_epoch=13, tickId=42)`.
     - `E1` successfully inserts the unique batch row while holding Redis lease token `L9001` and durable `executorFence=27`; that row becomes the only valid durable batch for `(T1, R7, 13, 42)`.
     - `E2` then reads the existing row:
       - if `E2` is acting under the same current `executorFence=27`, it may continue/replay from that row;
       - if `E2` now holds a later lease acquisition with `executorFence=28`, it must stop and treat the existing row as belonging to an older ownership generation until recovery explicitly reconciles it.
     - `E2` must not overwrite the manifest, create a second batch row, or select different work for tick `42`.
     - If storage ever reveals two durable rows for `(T1, R7, 13, 42)`, the region pauses immediately and reconcile tooling chooses the survivor before any later tick runs.
2. **Redis staging complete**
   - After the durable batch exists, the executor stages the selected effects into `tick:{tenantRegionTag}:pending`, carrying the `tick_batch_id` and the expected effect count (or equivalent digest) so Redis and PostgreSQL can be correlated during recovery.
   - `tick:{tenantRegionTag}:pending` is an acceleration/coordination structure. The durable tick-batch plus ledger rows are the authoritative record of what the tick intended to stage.
3. **Domain application**
   - Domain services process staged effects under idempotent rules keyed by `(tenantId, regionId, region_epoch, tickId, effectKey)` and update authoritative PostgreSQL state in their own databases.
   - Game Session records the outcome of each effect in its tick effect ledger (`SCHEDULED` → `APPLIED` or `ABANDONED`) based on the domain calls’ return semantics. Domain services do not write to the Game Session ledger directly.
4. **Commit visibility**
   - Once all ledger rows that belong to that tick batch are terminal (`APPLIED` or `ABANDONED`), Game Session advances the durable commit watermark for the region (for example updating `RegionStatus.lastCommittedTickId` for the current `region_epoch`) under the same `executorFence`, and only then emits the tick heartbeat for that `(region_epoch, tickId)`.
   - Cross-region coordinator rows such as `PENDING_REMOTE` are not part of this terminal set; they are durable workflow state outside the committing tick batch.
5. **Coordination cleanup**
   - A final commit/cleanup script clears the `pending` entry for the tick and releases any entity locks for that region.
   - Cleanup is a required part of “tick is no longer in flight”; if an executor crashes after commit visibility but before cleanup, crash recovery must clear/abandon the `pending` entry before any subsequent tick stages new work.

From the perspective of the `(region_epoch, tickId)` timeline:

- A tick is `durable_committed` once:
  - All ledger rows owned by that tick batch for `(tenantId, regionId, region_epoch, tickId)` have reached a terminal state (`APPLIED` or `ABANDONED`), and
  - The durable commit watermark (for example `RegionStatus.lastCommittedTickId`) has advanced to that `(region_epoch, tickId)` under the same `executorFence`.
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

- A region is considered busy while prior tick coordination state has not reached `coordination_cleared` (in practice, while `tick:{tenantRegionTag}:pending` exists for an in-flight `tickId`).
- The scheduler does not start a new tick for that `<tenantId, regionId>` until the previous tick is `coordination_cleared` as part of normal cleanup or explicitly handled during crash recovery.
- The scheduler obtains the current `(region_epoch, tickId)` baseline for each region from PostgreSQL (for example, a `RegionStatus` table and/or the tick effect ledger); it **does not** use `tick:{tenantRegionTag}:meta.current_tick_id` to decide which tick to run next.
- The scheduler and recovery tooling treat `RegionStatus.executorFence` as the durable owner generation; any batch or commit attempt that carries an older fence must stop rather than trying to race the new owner.
- Additional work enqueued for the same region while a tick is in flight is modeled as retries or follow-up work for a later `tickId`, not as a second concurrent tick.
- On a non-reset cold start where Coordination Redis is empty but PostgreSQL `RegionStatus` remains authoritative:
  - The next winning executor derives the next requested tick from `RegionStatus.lastCommittedTickId + 1`.
  - The first successful hot-path staging script for that tick initializes `tick:{tenantRegionTag}:meta.current_tick_id` to that requested tick as a Redis-side guard only.
  - Schedulers and operators continue to treat PostgreSQL `RegionStatus` as authoritative for baseline tick selection.

If FireMUD later introduces limited intra-region parallelism (for example by sharding a single region into buckets of entities), this model will evolve to use **per-bucket pending keys** such as `tick:{tenantRegionTag}:bucket:<bucketId>:pending` plus matching idempotency and locking rules. Until such a change is explicitly designed, the invariant remains one `pending` entry and one in-flight tick per `<tenantId, regionId>`.

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
  - Queries or claim updates use database-side concurrency control (for example, `FOR UPDATE SKIP LOCKED` or equivalent) so multiple executors do not drain the same follow-up.
  - Intake per entity per tick is limited so other entities with due work still make progress.
- When remote follow-up queues grow:
  - If due follow-ups exceed a threshold, the region is marked `DEGRADED` and emits metrics such as:
    - `remote_followups_due_total`
    - `remote_followups_drain_lag_ms`
    - `remote_followups_backlog_over_budget_total`
  - The executor may temporarily bias part of the per-tick budget toward draining remote follow-ups (within the configured caps) to reduce cross-region lag.
  - Admission control applies at the origin: when the target region is degraded or backlog is high, new cross-region actions may be delayed, rate-limited, or rejected with a clear error so the system sheds load instead of accumulating an unbounded remote backlog.
    - The canonical signal for “target region degraded / unhealthy” comes from Game Session’s durable/control-plane region status (for example `GetRegionTickStatus` / `RegionStatus`), not from best-effort Redis hint keys such as `remote:*`.

Best-effort hint markers (`remote:<tenantId>:<entityId>`) are only allowed to influence latency. Correctness is derived solely from the durable follow-up records in PostgreSQL and the idempotent handling of those records in the target region’s tick pipeline.

## Cross-Region Commands Under Resets

Cross-region flows participate in the same coordination timeline and reset rules as purely local ticks:

- Each leg of a cross-region command is tied to a specific `(region_epoch, tickId, effectKey)` on its origin or target region, and its durable state is tracked in the tick effect ledger and follow-up tables described in `system-architecture-tick-failures-and-operations.md`.
- Any origin-side waiting or aggregation state for those legs lives in a separate durable coordinator record. It must not keep the origin tick batch open or prevent `lastCommittedTickId` from advancing.
- When a region/tenant/cluster reset bumps `region_epoch` for a region, any surviving `SCHEDULED` ledger rows or follow-ups from the old epoch converge to `ABANDONED` under the ledger rules; they are not silently retried on the new epoch.
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

- Deadlines are tick-based and recorded durably with the origin coordinating effect (`remote_deadline_tick_id`), not inferred from wall-clock timers.
- The coordinator lifecycle above is outside the committing origin tick batch:
  - The origin tick that creates the remote follow-up still commits normally once its own batch rows are terminal.
  - Later remote results or timeouts enqueue subsequent origin-region work or update the separate coordinator record; they do not retroactively keep the original tick non-terminal.
- If origin has already reached `REMOTE_TIMEOUT_ABANDONED`, any later remote result must not silently mutate prior terminal state:
  - Default: record `LATE_RESULT_IGNORED` for observability and keep origin terminal state unchanged.
  - Feature-specific override: `LATE_RESULT_RECONCILED` is allowed only if the feature documents an explicit reconciliation/compensation flow.
- Every cross-region command type must explicitly declare one of two late-result classes in its design:
  - `late_result_safe_to_ignore`
  - `late_result_requires_reconciliation`
- Flows with paired player-visible consequences (for example remote damage plus local heal/refund/reward/economic settlement) must not use the default ignore policy unless the design proves that ignoring the late result cannot strand origin-side state.
- For `LATE_RESULT_RECONCILED`, compensation and external side effects must use outbox/saga mechanisms outside the tick loop.

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
   - The caster issues a `LIFESTEAL <target>` command from a room in `<tenantId, regionA>`.
   - Game Session enqueues the command under the caster’s per-entity queue key in Redis.
2. **Target Resolution (origin region, read-only)**
   - During the next tick for `<tenantId, regionA>`, the executor:
     - Resolves which remote entity in `<tenantId, regionB>` is the intended target.
     - Validates that a cross-region action is allowed (line of sight, range, permissions) using the pinned snapshot and metadata.
   - No HP or inventory state is changed yet; this phase only determines the target and target region.
3. **Damage Leg (target region)**
   - The origin region records durable follow-up work for the target entity in PostgreSQL (tick effect ledger / follow-up tables), attributed to `<tenantId, regionB>` and keyed by a stable effect identity.
   - It may also write a best-effort hint marker such as `remote:<tenantId>:<entityId>` (for the target entity) to reduce latency, but correctness does not depend on that marker.
   - In the next tick for `<tenantId, regionB>`, the target region’s executor:
     - Computes the damage amount as a percentage of the target’s authoritative current HP.
     - Acquires the target’s lock (`tick:{tenantRegionTag}:lock:<entityId>` for the target entity) and applies damage via Entity Management using the normal `(tenantId, regionId, region_epoch, tickId, effectKey)` idempotency rules.
     - Emits a result back to region A containing `casterEntityId` and the actual `damageApplied`.
4. **Heal Leg (origin region)**
   - When region A receives the lifesteal result, it enqueues a local “apply lifesteal heal” command for the caster.
   - In a subsequent tick for `<tenantId, regionA>`, the executor:
     - Acquires the caster’s lock.
     - Applies a heal up to `damageApplied` (subject to HP rules) using Entity Management and tick idempotency.
5. **Player Feedback and Optional Coordination**
   - The origin region may:
     - Immediately show “You cast Lifesteal…” once the initial command is accepted.
     - Show damage and heal messages as the remote and local legs complete.
   - If stricter “all-or-nothing” semantics are required, the origin region can track whether both damage and heal legs have reported success and apply a final status (success, partial, failed), but most combat flows rely on the default eventual consistency.

Throughout this sequence:

- Each leg is idempotent and keyed by `(region_epoch, tickId)` and effect identity in the domain services.
- Region executors never hold cross-region locks; they coordinate via queued commands, durable follow-up records, and result messages.
- Retries due to lock contention or transient failures are handled by the standard retry queues and idempotent handlers in each region without breaking the overall experience.
