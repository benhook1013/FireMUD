# FireMUD System Architecture: Tick System and Runtime Design

📄 This document expands on the [Game Loop / Tick Model](./system-architecture-overview.md#game-loop--tick-model) section of the FireMUD System Architecture Overview. It defines how ticks execute, resolve concurrency, handle crashes, and preserve deterministic, fair game logic under load.

Cross-service operations triggered by ticks rely on Redis scripts and gRPC; sagas are unnecessary for these gameplay actions as explained in [Transaction Strategies](./system-architecture-transactions.md).

Automation/timer work is a tick input, not a second tick authority. The [Scripting Scheduler and Timers](./system-architecture-scripting-scheduler-and-timers.md) and [Scripting Contracts](./system-architecture-scripting-contracts.md) own schedule continuity and exact `(scriptPatchVersion, scriptPinEpoch)` identity; this document owns only region execution, fairness, and effect-fence consequences. Routine epoch-fenced script rollback keeps ordinary gameplay ticks running.

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

- Target-state `regionId` is an opaque logical runtime-coordination identifier scoped by `{tenantId, gameInstanceId}`. The complete region scope is `{tenantId, gameInstanceId, regionId}`; it is not a World Management numeric row ID, design-time region template ID, room ID, or slug.
- `tenantRegionTag` is the canonical opaque Redis hash-tag projection of that complete region scope. Every region-scoped queue, lease, lock, timer, retry, event, and pending key must include `tenantId`, `gameInstanceId`, and `regionId`; a tenant-plus-region tag is insufficient when two game instances reuse a region ID.
- the live durable ownership and command-status boundary is currently keyed by `{tenantId, gameInstanceId}`, not true `regionId` partitioning;
- the live control-plane/status APIs are `GetRuntimeOwnershipStatus` and the canonical `GetGameplayCommandStatus`; the live scheduler progress feed is `ObserveRuntimeTickProgress` carrying `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)`; `StreamTickHeartbeats` and `GetRegionTickStatus` remain target-state follow-through;
- the live `executorFence` is an opaque generation token used for compare-and-match stale-fence protection, not yet the richer numeric ordering model used in some target-state examples;
- the live `tick_batch` / `tick_effect` substrate is real, and the current gameplay-command manifest now carries current-boundary `enqueueSeq`, `sourceType`, `dueTickId`, and explicit claimed-source state plus digest-checked replay reuse. The live `enqueueSeq` comes from one database-wide sequence; the region-scoped, cross-source allocator that remains monotonic across epoch resets is target-state. Timer/retry/remote-follow-up source-claim breadth and the cross-region result-return contract described below are also target-state follow-through rather than fully shipped behavior.
- the live Game Session automation handoff does not yet carry `commandOrdinal` or the full Trigger Identity required for target-state per-command diagnostics. `TickStagingService` currently derives `effectKey` as `command:<commandId>` and falls back to `command-text:<hash>:slot:<index>` when no command id is available; the target-state `automationDispatchId` plus `commandOrdinal` effect identity is therefore an implementation gap, not current behavior.

---

## Hybrid Tick Model

FireMUD uses a **hybrid tick model** to balance real-time responsiveness with deterministic action resolution:

- Root actor actions are queued per entity (players, NPCs, scripted automation), while passive and inbound effects have a separate bounded lane.
- At each tick, the region executor selects at most one root actor action per eligible entity. Incoming work must not consume that actor-action slot merely because its target is the entity.
- From the player’s perspective, state changes appear as a single, coherent “tick of work” per region, even though they are implemented as multiple service-local transactions plus idempotent retries.

Conceptually, FireMUD treats time as **localized pulses** rather than a single global clock: each tick is a self-contained logical transaction for its region that composes safely with others. Internally, that logical transaction is realized as:

- Per-service database transactions guarded by effect identity and idempotency, and
- Replayable coordination via Redis and the tick effect ledger rather than a single cross-service ACID boundary.

For the precise cross-service transaction model and when sagas are required, see `system-architecture-transactions.md`.

See `system-architecture-tick-concepts-and-invariants.md` for the full description of fairness guarantees and queueing rules.

### Scheduling Lanes and Phase Order

The canonical scheduler has two independently bounded lanes:

- The **actor-action lane** admits at most one root intentional action per eligible entity per tick. Player commands, AI decisions, automation commands, timers explicitly defined as making an actor act, and retries of those actions compete in this lane. A retry preserves the original action identity and does not create another same-tick root action.
- The **passive/inbound-effect lane** admits status pulses, environmental effects, incoming remote effects, actor-generated consequences, and retries of already-admitted effects. These effects do not consume the target entity's actor-action slot.

The phases are ordered as follows: (1) due start-of-tick passive effects, (2) one selected actor action for each still-eligible entity, and (3) effects generated by those selected actor actions. Only phase-1 effects completed before phase-2 eligibility evaluation may change same-tick eligibility; work selected but not completed, or deferred by lane, per-entity, cost, or region budget overflow, affects a later tick. Passive or inbound work that becomes due after start selection is also deferred to a later tick; phase 3 does not drain it. A completed start-of-tick effect may make an entity ineligible to act. An actor-generated effect does not retrospectively remove an action that already executed, and neither lane may recursively grant another root actor action in the same tick.

Every work source declares its phase, lane, and cost class before admission. Within one entity's eligible queue, the persisted manifest orders work by `(priority, due_tick_id, entity_enqueue_seq, source_kind, commandId_or_effectKey)`, with due points normalized before selection. Across eligible entities, persisted rotating/deficit scheduling accounts for declared cost and advances only with the durable selected batch; bounded aging or `maxDeferralTicks` applies where declared. Each lane has separate per-entity count and cost caps, region-wide work budgets, bounded carry-over/backoff, and pressure metrics. Overflow is deferred without changing its persisted ordering inputs or retry/action/effect identity; a new source cannot silently introduce a custom scheduler. See [ADR 0065](./decisions/adr-0065-deterministic-fair-entity-tick-scheduling.md).

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
- Consumer checkpoint and offset state is physically stored under region-scoped Redis keys but has one explicit authority per consumer: because checkpoints are per consumer, the event-offset record `tick-events-offset:{tenantRegionTag}:{consumerId}` owns `streamOffset` and stores `{tenantId, gameInstanceId, regionId, consumerId, regionEpoch, latestTickId, streamOffset}`. Its stable logical identity is `(tenantId, gameInstanceId, regionId, consumerId)`; `regionEpoch`, `latestTickId`, and `streamOffset` are mutable timeline state, not identity fields. Durable schedule identity remains separately due-point-aware. Consumers must compare the event-offset record's stored epoch with the authoritative epoch before using its `streamOffset` or `latestTickId`; an epoch mismatch is a reset boundary, so the old record is discarded and rebuilt from the active canonical status/progress adapter before resuming.

Consumers that persist offsets must key stable consumer identity by `(tenantId, gameInstanceId, regionId, consumerId)` and store `regionEpoch` and `latestTickId` as mutable timeline state. Durable schedule identity is `(tenantId, gameInstanceId, regionId, scheduleId)` (or `scriptId` when that is the durable schedule key); `regionEpoch`, `latestTickId`, and `nextDueTickId` are mutable timeline fields, not part of stable schedule identity. Trigger-instance de-duplication may derive a separate per-occurrence key from that stable schedule identity plus the applicable epoch, due point, and trigger mode, but must not mutate or redefine the schedule itself. Any observed epoch jump is a reset boundary: state derived from the old epoch is discarded or reconciled from PostgreSQL before resuming.

### Tick Commit Definition (Heartbeat Watermark)

FireMUD uses two explicit tick boundaries for `<tenantId, gameInstanceId, regionId, regionEpoch, tickId>`:

- **`durable_committed`** (authoritative commit boundary):
  - The Game Session tick effect ledger has reconciled each effect for that tick under authoritative evidence: `APPLIED` only when proven applied, `ABANDONED` only when proven unapplied, and inconclusive work remains nonterminal/reconciliation-required. There are no remaining `SCHEDULED` ledger rows for that tick.
  - Target-state `RegionStatus.lastCommittedTickId` has been advanced to that `(regionEpoch, tickId)` as part of the same durable visibility boundary. In the current-live deployment, the committed tick fields on `RuntimeOwnershipStatus` advance under the same opaque fence; `ObserveRuntimeTickProgress` is the progress feed and does not replace that durable authority.
- **`coordination_cleared`** (in-flight clearance boundary):
  - Redis staging/lock state for that tick is no longer in flight (for example, `tick:{tenantRegionTag}:pending` is cleared/abandoned and lock cleanup has completed).

Heartbeat emission and consumer semantics are tied to `durable_committed`, not to Redis cleanup:

- A heartbeat `(tenantId, gameInstanceId, regionId, regionEpoch, tickId)` is emitted only after `durable_committed` has been reached.
- Consumers treat heartbeat as the authoritative “last committed tick” watermark and must not infer commit from Redis `pending` state.
- Target-state only: a truly empty cadence boundary may reach `durable_committed` through one lightweight fenced durable watermark/heartbeat transition without an effect batch, domain RPC, entity lock, Redis `pending`, or effect-batch creation. It advances exactly one canonical tick; tick/game time freezes only while the region is `PAUSED` or `STALLED`. The target transition and its `StreamTickHeartbeats` emission are not current-live behavior: current live uses `ObserveRuntimeTickProgress` and has neither the empty-cadence status transition nor `StreamTickHeartbeats` until that target ships.

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
  - Persist one canonical event-offset record per consumer, `{tenantId, gameInstanceId, regionId, consumerId, regionEpoch, latestTickId, streamOffset}`, in **Coordination Redis** under `tick-events-offset:{tenantRegionTag}:{consumerId}`. This record is the sole owner of that consumer's `streamOffset`; scheduler hints must not copy or compete with it. Its stable logical scope is `(tenantId, gameInstanceId, regionId, consumerId)`, while `regionEpoch`, `latestTickId`, and `streamOffset` are mutable timeline state. Consumers can resume from the last observed stream entry only after the stored epoch matches the authoritative epoch. If the record is missing, epoch-mismatched, or the stream has been truncated/reset, that consumer discards its record and bootstraps from the active deployment's canonical status/progress adapter (`GetRuntimeOwnershipStatus` plus `ObserveRuntimeTickProgress` currently, `GetRegionTickStatus` plus `StreamTickHeartbeats` at target state), then establishes a new offset rather than assuming the stream is a complete history.
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
- Maintain per-region scheduler state in Redis (for example `script-scheduler:{tenantRegionTag}:lastTickId`) only as a derived discovery hint. Its value stores `{regionEpoch, latestTickId}` and never owns or persists `streamOffset`; the canonical per-consumer event-offset record remains `tick-events-offset:{tenantRegionTag}:{consumerId}`. The scheduler must reject the hint when its epoch differs from the authoritative current epoch, discard it on reset, and rebuild it from the current canonical status/progress observation. If the event stream is truncated, only each affected consumer's canonical event-offset record is invalidated for resumption; the scheduler still validates or rebuilds its separate hint from canonical progress rather than using the hint as a stream offset. The event-offset identity is `(tenantId, gameInstanceId, regionId, consumerId)`, while `regionEpoch`, `tickId`, and the stream event offset are never key dimensions.
- After a due candidate passes the applicable schedule-admission checks, claim or insert a durable PostgreSQL trigger-instance/outbox row keyed by an **instance-aware** uniqueness projection before enqueueing any `onInterval` or other tick-derived trigger so duplicate heartbeat consumers or failover cannot create duplicate logical gameplay actions. Discovery of a due candidate alone does not create a firing claim or `scriptEventId`; a pre-admission skip is recorded at event scope under a deterministic `scheduleCandidateId` derived from the stable schedule-row identity, applicable runtime region and epoch, and tagged due point.
  - At minimum this uniqueness projection includes the stable scheduler identity fields: `tenantId`, `gameInstanceId`, `playableStateScope`, `regionId`, `regionEpoch`, `entityId` only for an entity-scoped target binding (and absent for region/global targets), `scriptId`, `eventType`, `eventSchemaVersion`, `scriptPatchVersion`, `isDryRun`, `scheduleDefinitionId`, the tagged due point, and `triggerMode`; plugin triggers also include `pluginId`, `pluginVersionId`, and `bindingId`, and all three plugin binding-owner fields remain part of the winning claim and `scriptEventId` derivation. The generated `scriptEventId` is deliberately absent from this pre-claim uniqueness key: the winning durable claim derives/allocates one immutable `scriptEventId` from the winning stable identity before enqueue and audit, while losing contenders reuse the winner's row/result. A globally unique `scheduleId` does not replace runtime scope, version, dry-run namespace, or timeline fencing. The tagged due point is exactly `dueTickId:<value>` or `dueAt:<epochMillis>`; when storage uses nullable `dueTickId`/`dueAt` columns, the alternate field is explicitly `NULL` (`dueTickId=<value>, dueAt=NULL` or `dueTickId=NULL, dueAt=<epochMillis>`), never an empty/zero substitute, and both fields may not be null or populated together. In the target-state handoff model, `automationDispatchId` plus `commandOrdinal` is the per-command child identity and is not a replacement for the parent Trigger Identity.

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
      "executorFence": "7f4a1f1e-5bf5-4cc4-9a52-81e79fd6f2ab",
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

Target-state region authority: for each `<tenantId, gameInstanceId, regionId>` there is exactly one active tick executor (Game Session Service worker) at any given time. True per-region durable `RegionStatus` ownership remains incomplete in the live runtime; the current durable ownership row is instance-scoped at `{tenantId, gameInstanceId}`. The target contract below must not be read as evidence that the target region-partitioned implementation is shipped.

The target executor:

- Owns tick queues, timers, and retries for that region.
- Holds the region lease in Redis.
- Drives staging and commit for that region’s ticks.

Other workers may be running but do not process ticks for that region while the lease is held. See `system-architecture-tick-concepts-and-invariants.md` for the full authority and lease model.

The current-live durable ownership boundary remains instance-scoped at `{tenantId, gameInstanceId}`. Its `RuntimeOwnershipStatus` row carries the selected runtime region fields and opaque `executorFence`, but it is not yet a separate durable ownership row for every region. The region-scoped lease and target-state `GetRegionTickStatus` model above must not be treated as replacing that live instance-scoped boundary until the target surfaces are shipped.

### Ownership Identities and Fences

These identities are distinct and must not be substituted for one another:

- `regionEpoch` is the durable semantic timeline generation for `<tenantId, gameInstanceId, regionId>`. It changes only for a scoped reset, topology replacement, or another intentional severance of the timeline.
- Target-state `executorFence` is a never-reused durable ownership generation installed once for each successful new lease ownership generation. It may be opaque in the current live boundary, where exact compare-and-match is the contract; it is not a Redis TTL, and renewal never advances it.
- The Redis region-lease token is an opaque ephemeral proof of current liveness possession. Only a non-secret correlation of it may be persisted; it is not a durable fence or timeline identity.
- An entity-lock identity is the complete region scope plus `entityId`; its opaque lock token proves only that individual Redis lock. It is not region ownership and cannot authorize durable tick writes.

Current-live ownership facts are enforced through the existing instance-scoped two-part fence; this is not the complete TICK-02 handshake. Target-state status surfaces must not be read as though they replace these live semantics until their implementation is shipped:

- Redis remains the fast-path lease and liveness mechanism.
- PostgreSQL `RuntimeOwnershipStatus.executorFence` is the durable ownership fence for current-live tick-control writes:
  - Every successful current-live ownership acquisition publishes a fresh opaque generation token for the instance-scoped `{tenantId, gameInstanceId}` row; `regionId` and `regionEpoch` are attributes of the selected runtime region, not additional live row-key fields.
  - Every durable tick-control write (`tick_batch`, ledger transitions, `lastCommittedTickId`, and equivalent recovery/control rows) records the expected token plus its region/timeline fields and succeeds only when the stored instance ownership token matches exactly.
  - Rows written under a different or missing fence are stale by definition and must not advance or continue tick execution.

This durable fence is the canonical protection against stale executors that lost Redis lease ownership but still have in-flight SQL work.

The following accepted ADR 0052 contract is target-state and is not shipped behavior: acquire/install/revalidate, the pre-dispatch lease check, bounded outage restrictions, and takeover reconciliation. It supplements the current-live facts above rather than asserting that the current implementation proves the complete handshake.

### Target Acquire/Install/Revalidate Protocol (ADR 0052)

1. The executor acquires the region Redis lease with a unique opaque token and bounded TTL. A renewal compares the same token and retains the current `executorFence`; it never advances the fence. Lease acquisition alone does not authorize work.
2. For a new ownership generation, Game Session compare-and-sets the authoritative durable status for the expected `regionEpoch` and prior ownership state, installs a fresh never-reused `executorFence`, and records only a non-secret lease-token correlation.
3. The executor revalidates that the same Redis lease token and expected `regionEpoch` are still current after fence installation. If it lost the lease before revalidation, it remains inert even if the fence transaction committed.
4. Only after successful revalidation may the executor stage work. Every durable batch creation, ledger transition, commit watermark, recovery write, and region-control mutation compares the current `regionEpoch` and `executorFence`; stale or missing fences fail closed.
5. Immediately before dispatching an authoritative domain effect, the executor revalidates Redis lease possession and the expected epoch. Stable effect identity and domain guards protect the residual lease-loss race after dispatch.

PostgreSQL outage handling is bounded: an executor may renew an already-held Redis lease for a bounded renewal window to avoid unnecessary ownership churn, but it may not install a new fence, create a durable batch, dispatch an effect, advance a commit watermark, or return a success result until the durable fence is revalidated. If the renewal window expires, or Redis is unavailable or lease possession is uncertain, execution stops and no unfenced fallback is permitted.

### Target Lease and Fence Ownership Handoff (ADR 0052)

Ownership handoff is ordered so a new executor cannot overlap a still-valid persisted fence:

1. The outgoing executor stops renewing the Redis lease and drains or recovers its in-flight work to the documented safe boundary. Each effect is reconciled under authoritative evidence (`APPLIED` only when proven applied, `ABANDONED` only when proven unapplied); inconclusive work remains nonterminal/reconciliation-required, with no bulk abandonment.
2. After the old lease expires or is relinquished, the successor acquires the Redis lease but remains unable to stage or commit durable work.
3. The successor installs a newer never-reused durable `executorFence` and revalidates the same lease token. From fence installation, every durable stage/commit/recovery write carrying the old fence fails closed; lease acquisition alone is not proof that the old SQL writer is harmless.
4. Before staging a new tick, the successor reconciles unfinished batches and effect-ledger rows from prior fences from durable PostgreSQL state. Redis `pending`, retry, timer, and lock keys are optional hints; they cannot authorize replay or abandonment.
5. The successor may stage or commit only after reconciliation and lease revalidation, and every write must compare that fence plus the expected region/timeline fields.

Fence replacement is one serialized control-plane transaction. The externally observable contract remains that the old fence is no longer valid before the new generation can stage or commit. A failed or ambiguous replacement leaves the successor unable to write and the region paused; it does not permit either generation to continue.

Redis lease outage, partition, expiry, or uncertainty stops region execution immediately: the executor must not stage, mutate durable tick state, dispatch effects, or treat an old lease token as current. Redis recovery does not by itself prove ownership; the acquire/install/revalidate sequence must complete again.

An ordinary executor handoff changes only `executorFence`; it does not bump `regionEpoch` and preserves the current timeline and durable identities. A reset first advances `regionEpoch`, invalidates or advances durable ownership, then clears or rebuilds Redis coordination state from durable intent. Old-epoch queues, locks, timers, retries, and pending entries are not silently replayed into the new epoch.

---

## Distributed Locking

Tick execution uses **per-entity locks** in Redis to coordinate concurrent actions within a region. Locks:

- Are acquired one entity at a time by default.
- Every Redis Lua invocation acquires at most one entity lock; piecemeal acquisition and multi-lock scripts are prohibited. Multi-entity correctness belongs to owning transactions or durable effect/workflow legs as described in `system-architecture-tick-concepts-and-invariants.md` and ADR 0074.
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
  - Drains in-flight work to a safe boundary (for example, after the current `pending` tick is committed or recovered), reconciling each effect under authoritative evidence rather than bulk-abandoning old work.
- Another instance acquires the released or expired lease, installs and revalidates its fresh durable fence, reconciles unfinished work from PostgreSQL, and only then continues tick processing. Existing Redis state is an optional coordination hint, not authority for replay.

Normal lease handoff/rebalancing does **not** bump `regionEpoch`: `regionEpoch` is reserved for scoped coordination resets and explicit maintenance operations that intentionally sever the old region timeline. Redis lease state provides liveness proof, while the durable `executorFence` provides stale-writer fencing; the opaque lease token is valid only while Redis confirms possession.

For most deployments, region topology changes (splits, merges, or reassignments between executors) are applied between game instances or during maintenance windows so active sessions are not disrupted.

World Management owns region topology (layout and `<regionId>` assignments scoped by `{tenantId, gameInstanceId}`) and may, over time, support “drain and split” or “merge” flows:

- Split flows mark a region for split, freeze scheduling and new command intake, reconcile each in-flight and durable effect under authoritative evidence, bump `regionEpoch` when the ownership mapping changes, move entities to new `<tenantId, gameInstanceId, regionId>` assignments, and reset/rebuild coordination state from durable state before ticks resume. They do not move live Redis queues by renaming them under new prefixes.
- Merge flows consolidate lightly used regions into a single region to reduce overhead.

### Topology Changes (Split/Merge) Protocol (Required Invariants)

Region split/merge operations interact directly with tick idempotency, Redis key ownership, and cross-region follow-ups. To keep these operations safe and deterministic, topology changes must follow a single, explicit protocol:

1. **Freeze and fence**
   - Pause tick scheduling and new command intake for the affected region(s).
   - Wait for any in-flight `tick:{tenantRegionTag}:pending` work to commit or be recovered effect-by-effect to an evidence-qualified terminal state; inconclusive effects remain nonterminal/reconciliation-required.
2. **Converge durable outcomes**
   - Run the tick effect ledger replay controller/reconcile tooling for the affected scope so each lingering `SCHEDULED` effect is reconciled by authoritative evidence: `APPLIED` only when proven applied, `ABANDONED` only when proven unapplied, and inconclusive effects remain nonterminal/reconciliation-required before moving queues or entities.
   - During the same reset/topology scope, only command records with `ackLevel` = `ACCEPTED_VOLATILE` in `RECEIVED`/`ENQUEUED` and no surviving batch/binding converge to `executionOutcome = LOST_BEFORE_STAGING`; `ACCEPTED_DURABLE` records follow their feature-specific durable replay or post-abandon re-drive contract, while inconclusive batch-bound work remains nonterminal/reconciliation-required. See the [canonical command outcome convergence](./system-architecture-tick-execution-flows.md#command-outcome-convergence-required).
   - If any old-epoch effect remains inconclusive, wait for the authority-fenced attestation under its original `EffectId` before changing the topology or reassigning ownership. It remains nonterminal/reconciliation-required and cannot be bulk-abandoned or treated as ordinary convergence; any exceptional maintenance path must preserve that state and the required follow-up reconciliation.
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
     - **Target state:** Rewritten through an atomic durable rollover that creates and durably links the new target-leg/follow-up identity, then marks the old identity `ABANDONED` only after effect-by-effect authoritative evidence proves it unapplied. If evidence proves it applied, it remains `APPLIED`; inconclusive work remains nonterminal/reconciliation-required.
     - **Target-state fallback:** If one transaction cannot cover the records, persist a fenced durable rollover intent containing the old/new identities, desired mapping, and sealed follow-up context; recovery retries/reconciles that intent until the new identity and link are durable and effect-by-effect evidence proves the old identity unapplied before marking it `ABANDONED`. Inconclusive work remains nonterminal/reconciliation-required.
     - Mark the old identity `ABANDONED` with a topology-change reason only when authoritative evidence proves it unapplied, replaying it under the new mapping is invalid, and no new target identity is required; otherwise preserve `APPLIED` or nonterminal/reconciliation-required state.
   - **Current drift:** The current follow-up path does not yet claim the complete atomic or recoverable rollover boundary. Until it is proven, a mapping-changing operation remains paused/fenced and must not mark an old follow-up `ABANDONED` solely because the mapping changed.
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

- Drains due start-of-tick passive effects under the passive/effect budget.
- Selects at most one root actor action for each entity that remains eligible.
- Drains only effects generated by the selected phase-2 actor actions under the passive/effect budget; all other passive/inbound work, including work that becomes due after start selection, is deferred to a later tick.
- Drives staging and commit for all selected work under the current lease and durable fence, with a lease revalidation immediately before authoritative effect dispatch.

See `system-architecture-tick-execution-flows.md` for the detailed algorithm, including lane classification, retry identity, ADR 0065's exact persisted ordering tuple, fair scheduler state, and bounded carry-over.

---

## Tick Staging and Commit Flow

Tick execution uses a **staging/commit pattern**:

- Stage: compute intended effects and write them into Redis (`tick:{tenantRegionTag}:pending`) via Lua under the region lease.
- Commit: call into domain services, which apply changes using the region-scoped tick timeline `(regionEpoch, tickId)` and effect guards to ensure idempotency across replays and resets.

Full commit-pattern details are in `system-architecture-tick-execution-flows.md` and the Redis docs.

The Game Session Service owns the full tick transaction lifecycle, including durable batch/source intent, commit, and cleanup/abandon semantics. Redis holds disposable coordination state inside that lifecycle but is not a co-owner or proof of commit; the Game Logic Service remains stateless with respect to tick transactions and is responsible only for deterministic resolution of actions, not for managing tick commit or post-failure cleanup.

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

Crash recovery replays staged ticks safely by re-invoking domain handlers; replays must not double-apply logical effects. Redis loss is evaluated against the measured `redis_unreplicated_write_window_slo_ms`; `ticks_exposed` is diagnostic only. The storage projection tuple `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)` identifies the ledger/storage view, while participant guard uniqueness is separately `(rootEffectId, typedOperation, targetAggregateType, targetAggregateId)`. The immutable request digest is bound to and compared by that participant guard. The combination of the coordination timeline, tick effect ledger, and per-service idempotency guards applies the ADR 0058 class-specific outcomes: each projection converges to a single terminal outcome (`APPLIED` or `ABANDONED`) only when the evidence policy permits terminalization; an inconclusive old-epoch projection remains reconciliation-required and non-terminal under its original root `EffectId`. See [ADR 0058](./decisions/adr-0058-class-specific-redis-loss-outcomes.md) and [Tick Failure and Operations](./system-architecture-tick-failures-and-operations.md#inconclusive-old-epoch-reconciliation-policy).

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

Script schedule identity, clock selection, reload preservation, and recovery policy are owned by [Scripting Scheduler and Timer Lifecycle](./system-architecture-scripting-scheduler-and-timers.md#script-timers-vs-tick-timers) and the [normative timer semantics matrix](./system-architecture-scripting-normative-contract-tables.md#table-3-timer-semantics-matrix). This document retains only the Game Session integration consequences:

- Game Session supplies the authoritative runtime ownership and tick-progress context for tick-aligned script schedules; Automation consumes that context without owning tick execution.
- Tick-aligned script due points remain bound to the supplied `(regionEpoch, tickId)` timeline. Wall-clock script eligibility is not compared with `currentTickId`; the scheduler converts an eligible wall-clock timer into the next canonical tick-driven trigger under its owner contract.
- Redis timer keys, scheduler hints, and retry queues remain volatile coordination projections. Durable script schedules, trigger identity, declared clock/recovery policy, and recovery outcomes remain outside this tick document and are rebuilt or reconciled through the scripting owner contracts above.

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
- Multi-aggregate operations use explicit `tick_effect_guard` storage projections that retain the epoch-scoped fields (for example `(tenantId, gameInstanceId, playableStateScope, regionId, regionEpoch, tickId, effectKey, targetAggregateType, targetAggregateId)`) and link each row to the stable root `EffectId` plus its derived typed operation/target guard identity.

### Tick Effect Identity and Idempotency Contract

Effect identity and idempotency rules are defined jointly by:

- The `(regionEpoch, tickId)` carried on tick-driven calls.
- **Target-state effect identity:** Game Session assigns a stable root `EffectId` for each logical effect from admitted identity and tick context. Participant guard identities derive from that root effect, typed operation, and target aggregate; they bind the immutable request digest. Script-generated commands include `(automationDispatchId, commandOrdinal)` in their admitted operation identity; `scriptEventId` alone cannot distinguish fan-out commands. The current live path is documented above and remains a tracked implementation gap.

The root identity is the canonical `EffectId` described in `system-architecture-transactions.md`; ledger records retain it and participant guards use its deterministic operation/target projection rather than ad-hoc keys.

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

PostgreSQL follow-up/result records are authoritative. The payload carries exact target identity plus required ownership, location, and aggregate-version preconditions; a matching epoch alone is insufficient. Result admission and timeout arbitration are serialized in one origin coordinator transaction and lock domain: a durably admitted result wins before timeout, a timeout terminal outcome is immutable, and any later result is recorded separately as late. Paused/stalled origin gameplay suspends tick-clock deadlines; an operational maximum-real-wait policy may terminalize stranded coordination only with its operational reason. See [ADR 0066](./decisions/adr-0066-durable-asynchronous-cross-region-result-arbitration.md).

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
