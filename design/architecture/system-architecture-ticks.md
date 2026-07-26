# FireMUD System Architecture: Tick System and Runtime Design

📄 This document expands on the [Game Loop / Tick Model](./system-architecture-overview.md#game-loop--tick-model) section of the FireMUD System Architecture Overview. It defines how ticks execute, resolve concurrency, handle crashes, and preserve deterministic, fair game logic under load.

Cross-service operations triggered by ticks rely on Redis scripts and gRPC; sagas are unnecessary for these gameplay actions as explained in [Transaction Strategies](./system-architecture-transactions.md).

> 🔗 For Redis keys, Lua-based atomicity, and operational guarantees, see the [Redis Architecture](./system-architecture-redis.md).

---

## Who Should Read What

The tick system serves multiple audiences. Use these companion docs to jump directly to the level of detail you need:

- **Concepts & invariants** – high-level mental model, fairness, locks, and idempotency rules.  
  See: `design/architecture/system-architecture-tick-concepts-and-invariants.md`.
- **Execution flows** – per-command phases, staging/commit, and cross-region flows.  
  See: `design/architecture/system-architecture-tick-execution-flows.md`.
- **Failures & operations** – crash recovery, replay rules, stuck entries, and design checklists.  
  See: `design/architecture/system-architecture-tick-failures-and-operations.md`.

This file provides a **condensed overview and anchor headings** so other docs can deep-link into specific topics. Detailed narrative and worked examples now live primarily in the audience-focused documents.

## Implementation Notes

The target-state tick architecture in this document is intentionally broader than the current live runtime boundary.

Current live substrate to keep in mind while reading:

- the live durable ownership and command-status boundary is currently keyed by `{tenantId, gameInstanceId}`, not true `regionId` partitioning;
- the live control-plane/status APIs are `GetRuntimeOwnershipStatus` and the canonical `GetGameplayCommandStatus`; the live scheduler progress feed is `ObserveRuntimeTickProgress` carrying `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`; `StreamTickHeartbeats` and `GetRegionTickStatus` remain target-state follow-through;
- the live `executorFence` is an opaque generation token used for compare-and-match stale-fence protection, not yet the richer numeric ordering model used in some target-state examples;
- the live `tick_batch` / `tick_effect` substrate is real, and the current gameplay-command manifest now carries current-boundary `enqueueSeq`, `sourceType`, `dueTickId`, and explicit claimed-source state plus digest-checked replay reuse; timer/retry/remote-follow-up source-claim breadth and the cross-region result-return contract described below are still target-state follow-through rather than fully shipped behavior.

---

## Hybrid Tick Model

FireMUD uses a **hybrid tick model** to balance real-time responsiveness with deterministic action resolution:

- Actions are queued per entity (players, NPCs, scripted automation).
- At each tick, the region executor pulls at most one action per entity and resolves them in a fair order so player commands, AI, and automation are treated equivalently.
- From the player’s perspective, state changes appear as a single, coherent “tick of work” per region, even though they are implemented as multiple service-local transactions plus idempotent retries.

Conceptually, FireMUD treats time as **localized pulses** rather than a single global clock: each tick is a self-contained logical transaction for its region that composes safely with others. Internally, that logical transaction is realized as:

- Per-service database transactions guarded by effect identity and idempotency, and
- Replayable coordination via Redis and the tick effect ledger rather than a single cross-service ACID boundary.

For the precise cross-service transaction model and when sagas are required, see `system-architecture-transactions.md`.

See `system-architecture-tick-concepts-and-invariants.md` for the full description of fairness guarantees and queueing rules.

---

## Tick Events & Heartbeat Stream

Two related concepts:

- **Tick execution** – the authoritative per-region loop inside the Game Session Service.
- **Target-state tick heartbeat** – a gRPC stream (`StreamTickHeartbeats`) whose subscription and every heartbeat message carry the complete `(tenantId, gameInstanceId, regionId)` scope plus `regionEpoch` and `tickId` progression so external services (for example Automation & Scripting) can align timers and quotas to the canonical tick timeline.
- **Current-live progress adapter** – `ObserveRuntimeTickProgress` is the current internal Game Session progress feed. It carries `(tenantId, gameInstanceId, regionId, regionEpoch, tickId, observedAtMs)` so Automation can advance durable tick-aligned schedules; it is not a `StreamTickHeartbeats` subscription.

The tick heartbeat is the **canonical timeline** for each `<tenantId, gameInstanceId, regionId>`:

- The canonical coordination timeline is the pair `(regionEpoch, tickId)`; within a given `regionEpoch`, `tickId` is monotonic per region.
- Heartbeats represent **committed tick progression**:
  - A heartbeat `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` is emitted only after the tick is considered committed for that region (as defined by the staging/commit model and the tick effect ledger), not merely after a tick begins or stages work.
  - Consumers must treat the heartbeat as the authoritative “last committed tick” watermark and must not infer commit from Redis `pending` keys or from observer event streams.
- Consumers must be able to reconstruct their view of progress and scheduling purely from the heartbeat stream plus durable domain state; no Redis structure is treated as an authoritative log of past ticks.
- Consumer checkpoint and offset state is physically stored under region-scoped Redis keys but is fenced by a value containing `{regionEpoch, latestTickId, streamOffset}`; the logical identity is `(tenantId, gameInstanceId, regionId, regionEpoch)`, not `tickId` alone. Durable schedule identity remains separately due-point-aware. Consumers must compare the stored epoch with the authoritative epoch before using either the checkpoint or offset; a mismatch is a reset boundary, so old-epoch state is discarded or reconciled from PostgreSQL before resuming.

### Tick Commit Definition (Heartbeat Watermark)

FireMUD uses two explicit tick boundaries for `<tenantId, gameInstanceId, regionId, regionEpoch, tickId>`:

- **`durable_committed`** (authoritative commit boundary):
  - The Game Session tick effect ledger has converged all effects for that tick to terminal outcomes (`APPLIED` or `ABANDONED`) and there are no remaining `SCHEDULED` ledger rows for that tick.
  - `RegionStatus.lastCommittedTickId` (or equivalent) has been advanced to that `(regionEpoch, tickId)` as part of the same durable visibility boundary.
- **`coordination_cleared`** (in-flight clearance boundary):
  - Redis staging/lock state for that tick is no longer in flight (for example, `tick:{tenantRegionTag}:pending` is cleared/abandoned and lock cleanup has completed).

Heartbeat emission and consumer semantics are tied to `durable_committed`, not to Redis cleanup:

- A heartbeat `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` is emitted only after `durable_committed` has been reached.
- Consumers treat heartbeat as the authoritative “last committed tick” watermark and must not infer commit from Redis `pending` state.

If a crash occurs after `durable_committed` but before `coordination_cleared`, recovery finishes cleanup under idempotent replay rules. This is an operational lag window, not a commit regression.

### In-Flight Clearance Boundary (Scheduler Behavior)

`coordination_cleared` defines when a tick is no longer considered in flight for scheduler gating:

- The region scheduler may treat `<tenantId, gameInstanceId, regionId>` as busy while previous tick coordination state remains uncleared.
- The scheduler does not start the next tick for a region until the previous tick reaches `coordination_cleared` (or recovery explicitly resolves the stale coordination state).
- This preserves the single in-flight tick invariant without redefining commit semantics away from the durable watermark.

In addition to the gRPC heartbeat, the Game Session Service exposes a **tick event stream** for schedulers and observers:

- Events are keyed by `<tenantId, gameInstanceId, regionId>` and each event explicitly carries `tenantId`, `gameInstanceId`, and `regionId`, then includes:
  - `regionEpoch` and `tickId` (the canonical coordination timeline for the region).
  - shard metadata.
  - The timestamp when the tick began.
  - The `activeVersionId` pinned for that tick.
- Consumers (for example, schedulers or reconnection logic) typically:
  - Acquire a small lease such as `tick-events-lease:{tenantRegionTag}` to avoid duplicate processing.
  - Persist the checkpoint value `{regionEpoch, latestTickId, streamOffset}` in **Coordination Redis** under a region-scoped key such as `tick-events-offset:{tenantRegionTag}`. Its logical scope is `(tenantId, gameInstanceId, regionId, regionEpoch)`, so consumers can resume from the last observed stream entry only after the stored epoch matches the authoritative epoch. If the offset is missing, epoch-mismatched, or the stream has been truncated/reset, consumers discard it and bootstrap from the active deployment's canonical status/progress adapter (`GetRuntimeOwnershipStatus` plus `ObserveRuntimeTickProgress` currently, `GetRegionTickStatus` plus `StreamTickHeartbeats` at target state) instead of assuming the stream is a complete history.
- The event stream is a **best-effort coordination structure**, not a durable log of record:
  - It is implemented on Coordination Redis under a region-scoped prefix such as `tick-events:{tenantRegionTag}`.
  - Production-like profiles that persist offsets use **Redis Streams** for this prefix so consumers can resume from an offset; pub/sub is reserved for fire-and-forget observers that never track offsets or history.
  - Producers must cap stream retention (for example via `XADD ... MAXLEN ~ tick_events_maxlen`, default `tick_events_maxlen = 2048` per `<tenantId, gameInstanceId, regionId>`) so `tick-events:*` cannot grow without bound; consumers must treat “offset too old / trimmed” as normal truncation and re-bootstrap from the active deployment's canonical status/progress baseline.
  - Events may be dropped, duplicated, or reordered relative to the heartbeat; correctness must not rely on seeing every past event.
  - Events represent **tick start notifications** (a “tick began” signal), not a commit guarantee:
    - A tick may begin and later be retried or abandoned due to failures; consumers must use the active deployment's heartbeat/status adapter as the commit watermark.
  - It is classified as **reset-tolerant** in the Redis reset policy matrix: region/tenant/cluster resets may drop both the event stream and any stored offsets without violating correctness.
  - Consumers must treat missing or truncated history as a signal to re-establish their baseline from the active deployment's canonical status/progress adapter and domain state rather than assuming every past event is available. The current live adapter is `GetRuntimeOwnershipStatus` plus `ObserveRuntimeTickProgress`; target-state deployments use `GetRegionTickStatus` plus `StreamTickHeartbeats`.

This event stream is an observer/wakeup hint used for reconnection timer replay hints and other out-of-band reporting. “Every N ticks” scheduling correctness comes from the active deployment's committed status/progress timeline (target-state heartbeat/RegionStatus) plus durable PostgreSQL schedules; tick events may reduce latency by prompting quicker work discovery, but missing or duplicated events must not change which schedules eventually fire.

Durable automation schedules, quotas, and trigger-instance de-duplication live in PostgreSQL (see the scripting DSL and Automation & Scripting service docs); Redis structures such as `tick-events:{tenantRegionTag}` and `script-scheduler:{tenantRegionTag}:lastTickId` are coordination hints only. Losing or resetting those keys must not change which automation jobs are eventually executed, only when they are next discovered.

Automation & Scripting Service instances typically:

- In target-state deployments, establish long-lived gRPC subscriptions to `StreamTickHeartbeats` with `tenantId`, `gameInstanceId`, and `regionId`; every message must repeat that complete scope so consumers cannot rely on an implicit stream binding to disambiguate instances.
- In the current live deployment, consume `ObserveRuntimeTickProgress` with the same complete runtime scope instead of assuming `StreamTickHeartbeats` is available.
- Maintain per-region scheduler state in Redis (for example `script-scheduler:{tenantRegionTag}:lastTickId`) only as a checkpoint/hint. The physical value must store `{regionEpoch, latestTickId, streamOffset}`; callers must reject and rebuild it when its epoch differs from the authoritative current epoch. Its logical checkpoint key is `(tenantId, gameInstanceId, regionId, regionEpoch)`, while `tickId` and offsets are never key dimensions.
- After a due candidate passes the applicable schedule-admission checks, claim or insert a durable PostgreSQL trigger-instance/outbox row keyed by an **instance-aware** uniqueness projection before enqueueing any `onInterval` or other tick-derived trigger so duplicate heartbeat consumers or failover cannot create duplicate logical gameplay actions. Discovery of a due candidate alone does not create a firing claim or `scriptEventId`.
  - At minimum this uniqueness projection includes the stable scheduler identity fields: `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `entityId`, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `isDryRun`, `scheduleDefinitionId`, the tagged due point, and `triggerMode`; plugin triggers also include `pluginId` and `pluginVersionId`. The generated `scriptEventId` is deliberately absent from this pre-claim uniqueness key: the winning durable claim derives/allocates one immutable `scriptEventId` from the winning stable identity before enqueue and audit, while losing contenders reuse the winner's row/result. A globally unique `scheduleId` does not replace runtime scope, version, dry-run namespace, or timeline fencing. The tagged due point is exactly `dueTickId:<value>` or `dueAt:<epochMillis>`; when storage uses nullable `dueTickId`/`dueAt` columns, the alternate field is explicitly `NULL` (`dueTickId=<value>, dueAt=NULL` or `dueTickId=NULL, dueAt=<epochMillis>`), never an empty/zero substitute, and both fields may not be null or populated together. `automationDispatchId` plus `commandOrdinal` remains the per-command child handoff identity and is not a replacement for the parent Trigger Identity.

### Bootstrap vs Stream (Authoritative Timeline Source)

For any consumer or operator that needs to locate “where a region is” on the `(regionEpoch, tickId)` timeline:

- **Bootstrap** from a durable view:
  - Target-state, Game Session exposes `GetRegionTickStatus` backed by a PostgreSQL `RegionStatus` or equivalent table that records the latest committed `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` for each region.
  - Current-live adapter: consumers and operational tools bootstrap owner/status from `GetRuntimeOwnershipStatus` over the current `{tenantId, gameInstanceId}` ownership row, using its stored `regionId` and committed tick fields when a region is selected. The live scheduler receives its progress baseline through `ObserveRuntimeTickProgress` with the same tenant/game-instance/region scope; it must not call target-state `GetRegionTickStatus` as though that surface were already live.
- Minimum `RegionStatus` contract (required for consumers and admission control):
  - Timeline: `regionEpoch`, `lastCommittedTickId`, and an `updatedAt`/`lastCommitTimestamp`.
  - Ownership fencing: a durable `executorFence` (or equivalent name) recorded on tick batches and other durable tick-control writes.
    - Target-state may choose a monotonic numeric fence.
    - Current live boundary uses an opaque generation token and exact compare-and-match semantics rather than numeric old/new ordering; callers must not increment, order, or otherwise interpret the token numerically.
  - Health: a bounded `status`/`health` value (for example `RUNNING`, `DEGRADED`, `PAUSED`, `STALLED`).
  - Backlog indicators:
    - Minimum required when cross-region gameplay, replay-driven admission control, or backlog-based shedding is enabled: retry depth and remote follow-up lag/backlog so origin-side admission control can shed load without relying on Redis hints.
    - Optional only for deployments/features that do not use backlog-aware admission or cross-region shedding; such profiles must document that reduced contract explicitly rather than assuming the main control-plane surface can omit these fields silently.
  - Update rule: `lastCommittedTickId` advances only after a tick is committed; it is monotonic within an epoch and resets only when `regionEpoch` is bumped by a scoped reset or explicit timeline-severing maintenance. In steady state it advances by exactly `+1` per committed tick. Direct “fast-forward” of `lastCommittedTickId` within a live epoch is forbidden because follow-up eligibility, remote deadlines, and automation schedules derive from the committed timeline.
  - Epoch-start sentinel: on a newly created epoch, `lastCommittedTickId = -1` (default), so the first committable tick in that epoch is `tickId=0`.
  - Illustrative response shape:

    ```json
    {
      "tenantId": "7b3b074e-d597-4e9b-b96f-4f5946d26120",
      "gameInstanceId": "9a2bb6d1-74c7-4f81-a9e8-418e65f6ad78",
      "regionId": "room:starter-village",
      "regionEpoch": 14,
      "lastCommittedTickId": 9284,
      "executorFence": "fence-51",
      "status": "RUNNING",
      "retryQueueDepth": 2,
      "remoteFollowupOldestAgeMs": 180,
      "updatedAt": "2026-03-22T04:15:26Z"
    }
    ```

- **Follow** via streaming heartbeats:
  - After bootstrapping, consumers attach to `StreamTickHeartbeats` with the same `(tenantId, gameInstanceId, regionId)` subscription and treat the combination of the bootstrap status and the live, scope-complete heartbeat as the authoritative progression of the timeline.
  - If the heartbeat stream drops or a reset bumps `regionEpoch`, consumers use the new `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` from the stream plus durable state to re-establish their position through the active deployment's status/progress adapter.

Redis coordination keys remain a volatile buffer; neither `tick:*` nor event-stream prefixes are considered sources of truth for epoch or tick counters.

Tick execution never depends on external buses; external services consume the heartbeat stream and/or tick event stream only. See `system-architecture-tick-concepts-and-invariants.md`, `system-architecture-scripting-dsl-reference-and-lifecycle.md`, and `system-architecture-scripting-runtime-execution.md` for details.

---

## Region Authority and Tick Executor

Target-state region authority: for each `<tenantId, gameInstanceId, regionId>` there is exactly one active tick executor (Game Session Service worker) at any given time. It:

- Owns tick queues, timers, and retries for that region.
- Holds the region lease in Redis.
- Drives staging and commit for that region’s ticks.

Other workers may be running but do not process ticks for that region while the lease is held. See `system-architecture-tick-concepts-and-invariants.md` for the full authority and lease model.

The current-live durable ownership boundary remains instance-scoped at `{tenantId, gameInstanceId}`. Its `RuntimeOwnershipStatus` row carries the selected runtime region fields and opaque `executorFence`, but it is not yet a separate durable ownership row for every region. The region-scoped lease and target-state `GetRegionTickStatus` model above must not be treated as replacing that live instance-scoped boundary until the target surfaces are shipped.

Lease ownership is enforced through a two-part fence. The following is the current-live ownership contract; target-state status surfaces must not be read as though they replace these live semantics until their implementation is shipped:

- Redis remains the fast-path lease and liveness mechanism.
- PostgreSQL `RuntimeOwnershipStatus.executorFence` is the durable ownership fence for current-live tick-control writes:
  - Every successful current-live ownership acquisition publishes a fresh opaque generation token for the instance-scoped `{tenantId, gameInstanceId}` row; `regionId` and `regionEpoch` are attributes of the selected runtime region, not additional live row-key fields.
  - Every durable tick-control write (`tick_batch`, ledger transitions, `lastCommittedTickId`, and equivalent recovery/control rows) records the expected token plus its region/timeline fields and succeeds only when the stored instance ownership token matches exactly.
  - Rows written under a different or missing fence are stale by definition and must not advance or continue tick execution.

This durable fence is the canonical protection against stale executors that lost Redis lease ownership but still have in-flight SQL work.

Canonical ownership sequence:

1. The executor acquires or renews the Redis region lease. A renewal retains the current generation; a new acquisition must complete the fence replacement below before it may write durable tick state.
2. On successful new ownership acquisition, Game Session compare-and-matches the authoritative ownership row and atomically replaces the previous `RuntimeOwnershipStatus.executorFence` with one fresh opaque token for the new generation. That replacement invalidates every old-fence writer before the new executor may stage or commit.
3. The executor creates `tick_batch` rows and other durable tick-control state using that new `executorFence`.
4. Any later durable write under that live instance ownership generation must compare-and-match the current `executorFence` and its expected region/timeline fields; stale writers fail closed and do not advance commit state.

### Lease and Fence Ownership Handoff

Ownership handoff is ordered so a new executor cannot overlap a still-valid persisted fence:

1. The outgoing executor stops renewing the Redis lease and drains, abandons, or recovers its in-flight work to the documented safe boundary.
2. After the old lease expires or is relinquished, the successor acquires the Redis lease but remains unable to stage or commit durable work.
3. The successor compare-and-matches the authoritative ownership row and atomically replaces the old persisted `executorFence` with a fresh opaque fence for the new ownership generation. From that commit, every durable stage/commit/recovery write carrying the old fence fails closed; lease acquisition alone is not proof that the old SQL writer is harmless.
4. The successor may stage or commit only after its fresh fence is visible in the authoritative ownership record, and every write must compare-and-match that fence plus the expected region/timeline fields.

Fence replacement is one serialized control-plane transaction. The externally observable contract remains that the old fence is no longer valid before the new generation can stage or commit. A failed or ambiguous replacement leaves the successor unable to write and the region paused; it does not permit either generation to continue.

---

## Distributed Locking

Tick execution uses **per-entity locks** in Redis to coordinate concurrent actions within a region. Locks:

- Are acquired one entity at a time by default.
- May be acquired in deterministic multi-lock order only for the small explicit whitelist of scripts documented in `system-architecture-tick-concepts-and-invariants.md`.
- Are scoped to a single region; cross-region flows never share locks.

Region executors rely on **lock-on-demand** rather than fixed thread ownership: no region or room is permanently bound to a single worker thread; instead, workers acquire and release Redis-backed locks and leases as they process tick work.

The detailed lock naming, TTL rules, and examples live in `system-architecture-tick-concepts-and-invariants.md`.

---

## Smart Retry and Conflict Resolution

When lock acquisition fails or conflicts arise, the tick engine:

- Classifies failures (transient vs structural).
- Schedules retries under bounded budgets.
- Uses conflict metadata to avoid livelock between competing commands.

See `system-architecture-tick-concepts-and-invariants.md` for conflict categories and retry patterns.

---

## Tick Regions and Parallel Execution

Tick work is partitioned into **regions** so that:

- Regions can advance independently.
- Failures are isolated to a region.
- Horizontal scaling is possible by assigning regions to different workers.

 Region sizing, sharding strategies, and how global effects and idle/background ticks behave are documented in `system-architecture-tick-concepts-and-invariants.md`.

---

## Region Sizing and Ownership

Region boundaries are chosen to:

- Minimize cross-region traffic for common player flows.
- Keep per-region tick load within safe bounds.

Ownership changes (moving a region between executors) follow the lease rules:

- A scheduler or consistent-hash layer maps `<tenantId, gameInstanceId, regionId>` to Game Session instances.
- To rebalance load, the current executor:
  - Stops renewing the region lease for selected regions.
  - Drains in-flight work to a safe boundary (for example, after the current `pending` tick is committed or recovered).
- Another instance acquires the released or expired lease, atomically replaces the old persisted fence with its fresh generation, and only then continues tick processing from the existing Redis and PostgreSQL state for that region.

Normal lease handoff/rebalancing does **not** bump `regionEpoch`: `regionEpoch` is reserved for scoped coordination resets and explicit maintenance operations that intentionally sever the old region timeline. Redis lease state provides liveness coordination, while the durable `executor_fence` provides stale-writer fencing; any lease token is trace/audit context only.

For most deployments, region topology changes (splits, merges, or reassignments between executors) are applied between game instances or during maintenance windows so active sessions are not disrupted.

World Management owns region topology (layout and `<regionId>` assignments) and may, over time, support “drain and split” or “merge” flows:

- Split flows mark a region for split, freeze scheduling and new command intake, converge in-flight and durable outcomes, bump `regionEpoch` when the ownership mapping changes, move entities to new `<tenantId, gameInstanceId, regionId>` assignments, and reset/rebuild coordination state from durable state before ticks resume. They do not move live Redis queues by renaming them under new prefixes.
- Merge flows consolidate lightly used regions into a single region to reduce overhead.

### Topology Changes (Split/Merge) Protocol (Required Invariants)

Region split/merge operations interact directly with tick idempotency, Redis key ownership, and cross-region follow-ups. To keep these operations safe and deterministic, topology changes must follow a single, explicit protocol:

1. **Freeze and fence**
   - Pause tick scheduling and new command intake for the affected region(s).
   - Wait for any in-flight `tick:{tenantRegionTag}:pending` work to commit or be recovered to a terminal state.
2. **Converge durable outcomes**
   - Run the tick effect ledger replay controller/reconcile tooling for the affected scope so any lingering `SCHEDULED` effects converge to `APPLIED` or `ABANDONED` before moving queues or entities.
   - Accepted command records that never became durably tied to a surviving `tick_batch_id` converge to terminal command status (`executionOutcome = LOST_BEFORE_STAGING`, default `gameplayResult = NOT_APPLIED`) as part of the same reset/topology scope; do not leave old-epoch dedupe rows stranded in pre-batch states.
   - If any old-epoch effect remains inconclusive, wait for the authority-fenced attestation under its original `EffectId` before changing the topology or reassigning ownership. Proceeding without that attestation requires an explicit, audited maintenance exception naming the affected scope, residual risk, containment, approver, and follow-up reconciliation; it must not be treated as ordinary convergence.
3. **Sever the old timeline for any mapping change (required)**
   - If the operation changes region boundaries or re-homes entities to a different region mapping, bump `regionEpoch` for all affected `<tenantId, gameInstanceId, regionId>` pairs as part of the topology change (the same epoch-severing mechanism used by scoped coordination resets).
   - Only topology operations that preserve entity-to-region ownership exactly may skip an epoch bump, and those exceptions must be explicitly documented and audited in the maintenance record.
4. **Reset or migrate coordination state using shared key builders**
   - First implementation resets/rebuilds coordination state from durable PostgreSQL state after the epoch bump instead of moving live Redis keys.
   - Future in-place migration may move only reset-tolerant, purely coordination structures (queues, timers, retry metadata) using versioned tooling that uses the shared key builders and Lua Script Registry descriptors.
   - Queue entries, timers, and retry records that are purely coordination state are not reconstructed from old Redis keys unless dedicated migration tooling re-derives them from durable intent under the new mapping. The default fate for accepted-but-unbound queued commands is the explicit command outcome above, not silent replay on the new epoch.
   - Do not hand-edit `tick:*`, `timer:*`, `retry:*`, or lease/lock keys.
5. **Handle cross-region follow-ups explicitly**
   - Durable follow-up rows in PostgreSQL are the source of truth for cross-region work. Topology changes must ensure follow-ups are either:
     - Rewritten to target the new region mapping, or
     - Converged to `ABANDONED` with a topology-change reason when replaying them under the new mapping is not valid.
6. **Resume**
   - Re-enable tick scheduling and command intake once the new mapping is in place and the region(s) are healthy.

See `system-architecture-tick-concepts-and-invariants.md` and the World Management service docs for detailed topology guidance and operational runbooks.

---

## Per-Command Execution Phases

Tick-driven commands typically follow phased execution:

1. Resolve targets and required locks.
2. Stage effects in Redis under the region lease.
3. Apply effects in domain services with idempotent handlers.
4. Finalize and clean up staging metadata.

The full phase breakdown and examples (such as cross-region lifesteal) live in `system-architecture-tick-execution-flows.md`.

---

## Tick Execution Flow

At each tick for a region, the executor:

- Dequeues eligible commands from per-entity queues.
- Pulls a bounded number of due timers and retries into the worklist.
- Applies fairness rules (one action per entity, per tick).
- Drives staging and commit for all selected actions under the region lease.

See `system-architecture-tick-execution-flows.md` for the detailed algorithm, including how timers, retries, and remote follow-ups are folded into the per-tick worklist.

---

## Tick Staging and Commit Flow

Tick execution uses a **staging/commit pattern**:

- Stage: compute intended effects and write them into Redis (`tick:{tenantRegionTag}:pending`) via Lua under the region lease.
- Commit: call into domain services, which apply changes using the region-scoped tick timeline `(regionEpoch, tickId)` and effect guards to ensure idempotency across replays and resets.

Full commit-pattern details are in `system-architecture-tick-execution-flows.md` and the Redis docs.

The Game Session Service and Redis own the full tick transaction lifecycle (staging, commit, and cleanup/abandon semantics); the Game Logic Service remains stateless with respect to tick transactions and is responsible only for deterministic resolution of actions, not for managing tick commit or post-failure cleanup.

Some commands (for example, heavy runtime procedural generation) declare `requiresSoloTick: true`. For these commands:

- The scheduler runs the command alone in its own tick so it does not compete with other player actions.
- If the command needs more than the normal `tick_budget_ms`, it must use the explicit `solo_tick_budget_ms` mode defined in `system-architecture-tick-concepts-and-invariants.md` rather than informally exceeding the shared formulas.

This keeps expensive operations predictable without introducing special-case fallbacks in the tick engine.

---

## Timeout and Fairness Policy

Tick execution is bounded by timeouts and fairness rules so that:

- Long-running commands do not starve other entities.
- Regions that approach unsafe tick durations are treated as degraded and surfaced via metrics.

See `system-architecture-tick-concepts-and-invariants.md` and the Entity Management design docs for the exact policies and operational thresholds.

---

## Isolation and Replay Safety

Isolation and replay guarantees rely on:

- Per-region leases and locks in Redis.
- A shared coordination timeline `(regionEpoch, tickId)` per `<tenantId, gameInstanceId, regionId>` as described in the Redis architecture docs.
- Domain-level idempotency rules keyed by `(regionEpoch, tickId)` and effect identifiers.

Crash recovery replays staged ticks safely by re-invoking domain handlers; replays must not double-apply logical effects. Even when Redis loses or replays up to a few ticks within the tail-loss envelope, the combination of the coordination timeline, the tick effect ledger, and per-service idempotency guards ensures that each `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` converges to a single terminal outcome (`APPLIED` or `ABANDONED`). See `system-architecture-tick-failures-and-operations.md` for the detailed story.

---

## Timers and Time Scaling

Tick timers (cooldowns, regeneration, delayed effects) are:

- Stored and scheduled via Redis timer keys.
- Aligned with the tick heartbeat and tick cadence.
- Subject to time-scaling rules that speed up or slow down perceived time while preserving ordering.
- Evaluated against absolute wall-clock `due_ms` values using caller-supplied `now_ms` in Lua (never Redis `TIME`); changing `tick_interval_ms` does not rescale already-scheduled timers.
- When a timer also has durable ordering/replay metadata, `due_ms` is the wall-clock firing input while the persisted `due_tick_id` is the canonical ordering and replay key.

Tick-region timers and retry queues are **volatile coordination structures**, not durable schedules. After coordination resets or data loss, only timers/schedules that are also represented durably elsewhere (for example PostgreSQL-backed automation schedules or other explicit domain state) are expected to be recovered or re-derived. Gameplay features that require timers to survive resets must store the underlying intent durably and treat Redis timer entries as derived coordination indexes rather than as the only record of the timer.

### Scheduler Recovery Semantics

Automation & Scripting uses the tick heartbeat plus durable PostgreSQL schedules to implement “every N ticks” and similar timers:

- For each scheduled script or automation job, PostgreSQL stores at least:
  - `(tenantId, gameInstanceId, playableStateScope, regionId, region_epoch, scriptId, eventType, eventSchemaVersion, scriptPatchVersion, isDryRun, scheduleDefinitionId, duePoint)` and the interval in ticks. `duePoint` is exactly one tagged value: `dueTickId:<value>` or `dueAt:<epochMillis>`.
- After a due candidate passes admission and before enqueueing its trigger, the scheduler must claim or insert a durable trigger-instance row keyed by an **instance-aware** uniqueness projection. Duplicate schedulers that race on the same heartbeat boundary must observe the same durable row and must not enqueue a second logical trigger.
  - The pre-claim uniqueness projection must include the stable scheduler Trigger Identity fields `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `entityId`, `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `isDryRun`, `scheduleDefinitionId`, the persisted tagged due point, and `triggerMode` (plus plugin identity when applicable) even when `scheduleId` is globally unique. It must not include generated `scriptEventId`; the winning claim derives/allocates that immutable event identity only after the stable claim succeeds, and all contenders reuse the winner's result.
- On startup or after a reset:
  - The scheduler uses the active deployment's canonical bootstrap/progress surface and the corresponding durable due point from PostgreSQL: current-live recovery uses `GetRuntimeOwnershipStatus` for owner/status and `ObserveRuntimeTickProgress` for `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` progress, while target-state recovery uses `GetRegionTickStatus`.
  - If a persisted scheduler checkpoint has a different `regionEpoch` than the current status, the scheduler rejects it, records `checkpoint_region_epoch_mismatch` in scheduler audit, increments `automation_script_timer_runtime_fence_dropped_total{scope, script_category, eventType, reason="checkpoint_region_epoch_mismatch"}`, discards the stale checkpoint and stream offset, and rebuilds the checkpoint from authoritative current-epoch status before reconciling durable due points under the bounded catch-up policy.
  - If `duePoint = dueTickId:<value>` and the due tick is at or before `currentTickId`, the scheduler may fire at most one **tick catch-up trigger** for each eligible schedule instance and full Trigger Identity (for example “you missed one interval while down”) and then advances that schedule instance's tick due point by whole intervals until it is strictly greater than `currentTickId`.
  - If `duePoint = dueAt:<epochMillis>` and the due time is at or before the reconciliation's captured current wall-clock time, the scheduler may fire at most one **wall-clock catch-up trigger** for each eligible schedule instance and full Trigger Identity and then advances that schedule instance's wall-clock due point by whole intervals until it is strictly greater than that current time. A wall-clock due point is never compared with `currentTickId`.
  - Very old missed intervals are not replayed one-by-one; each due point advances independently, the system guarantees that **future intervals fire correctly**, and at most one bounded catch-up occurs per eligible schedule instance/Trigger Identity after downtime.
- After recovery, the scheduler:
  - Tracks tick-aligned progression from the active canonical progress source (target-state heartbeat, current-live `ObserveRuntimeTickProgress`) and updates `nextDueTickId` in API/workflow prose; the PostgreSQL field is `next_due_tick_id`. Wall-clock schedules use wall-clock observations and update `next_due_at` in storage-facing prose. The scheduler does not infer one due-point type from the other.
  - Uses Redis coordination keys such as `script-scheduler:{tenantRegionTag}:lastTickId` as hints/checkpoints only; losing them affects when work is next discovered, not which durably-configured schedules eventually execute, because durable trigger-instance uniqueness remains the de-duplication boundary.

Details of timer key shapes and scaling strategies live in `system-architecture-tick-concepts-and-invariants.md` and `system-architecture-scripting-scheduler-and-timers.md`.

---

## Crash Recovery and Replay

On executor crash or failover, a new worker:

- Acquires the region lease.
- Reads the durable tick-batch, tick effect ledger, and follow-up tables plus the active deployment's authoritative status surface for the affected `(tenantId, gameInstanceId, regionId, regionEpoch)`: target-state `RegionStatus`/`GetRegionTickStatus`, or current-live `GetRuntimeOwnershipStatus` with `ObserveRuntimeTickProgress` progress.
- Inspects any surviving Redis coordination state (`tick:{tenantRegionTag}:pending`, `retry:{tenantRegionTag}`, timers, leases) only as optional hints that may accelerate or narrow replay scope.
- Replays or resumes work from the durable PostgreSQL record of staged or claimed work plus domain idempotency tables; Redis coordination state must not be treated as the sole persisted recovery basis.

See `system-architecture-tick-failures-and-operations.md` for the full crash-recovery algorithm and failure modes.

---

## Domain Idempotency Rules (Region Epoch + TickId in PostgreSQL)

Domain services must ensure that tick-driven effects are **idempotent** with respect to the region-scoped tick timeline `(regionEpoch, tickId)`:

- Single-aggregate updates use a “last applied tick” pattern that is epoch-aware (for example storing and comparing `(last_region_epoch, last_tick_id)` for the aggregate).
- Multi-aggregate operations use effect guard tables keyed by the same epoch-scoped identity (for example `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)`).

### Tick Effect Identity and Idempotency Contract

Effect identity and idempotency rules are defined jointly by:

- The `(regionEpoch, tickId)` carried on tick-driven calls.
- A stable, structured effect identity derived deterministically from the command payload and tick context, including at minimum `tenantId`, `gameInstanceId`, resolved `playableStateScope`, `regionId`, `regionEpoch`, `tickId`, `effectKey`, `targetAggregateType`, and `targetAggregateId`. For script-generated commands, `effectKey` must incorporate the command-level `automationDispatchId`; `scriptEventId` alone cannot distinguish fan-out commands.

Together these form the canonical `EffectId` described in `system-architecture-transactions.md`. Tick coordination keys in Redis, tick effect ledger rows, and domain-level guard tables (for example `tick_effect_guard`) must all use projections of this same `EffectId` rather than introducing ad-hoc idempotency keys.

For the complete contract, ledger schema, and endpoint semantics, see `system-architecture-tick-failures-and-operations.md` and `system-architecture-transactions.md`.

---

## Tick Execution and Redis Integration

Redis provides:

- Per-entity command queues.
- Staging keys for tick effects.
- Locks and retry metadata.

Tick execution relies on a canonical “commit pattern” implemented via Lua and described in `system-architecture-tick-execution-flows.md` and the Redis architecture docs.

---

## Cross-Region Command Execution and Result Relay

Cross-region actions (for example, one player affecting another in a different region) are modeled as **messages between region executors**, not shared locks:

- The origin region enqueues follow-up work for the target region.
- The target region drains and executes those follow-ups under its own lease.
- Results are relayed back to the origin region or player session.

See `system-architecture-tick-execution-flows.md` for the detailed cross-region flow, budgets, and backpressure rules.

---

## Service Responsibilities

At a high level:

- **Game Session Service** – orchestration of tick scheduling, staging, commit, and leases.
- **Game Logic Service** – deterministic resolution of actions and movement.
- **World Management Service** – region layout and world topology.
- **Automation & Scripting Service** – injects scripted commands and consumes tick heartbeats.

Each service’s detailed responsibilities and invariants are captured in its own architecture doc and referenced from the audience-focused tick docs.

---

## Model Benefits

The tick model is designed to provide:

- Parallel, fault-isolated tick execution per region.
- Lock-on-demand using Redis instead of fixed thread ownership, allowing regions to move between workers without changing the programming model.
- Deterministic recovery via idempotent replays.
- Fair scheduling across entities.
- Clear boundaries between coordination state (Redis) and authoritative state (PostgreSQL).
- No long-lived, in-service volatile tick state: tick coordination and session/timer state can be rebuilt from Redis and authoritative domain stores after failures.

See the introduction of `system-architecture-tick-concepts-and-invariants.md` for a more narrative discussion of these benefits.

---

## Related Documentation

- [Redis Architecture](./system-architecture-redis.md)
- [Transaction Strategies](./system-architecture-transactions.md)
- [System Architecture Overview](./system-architecture-overview.md)
- [Scripting & Automation](./system-architecture-scripting.md)
- [Redis Incident Runbook](./system-architecture-redis-incident-runbook.md)
