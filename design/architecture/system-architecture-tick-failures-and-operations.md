# FireMUD Tick System: Failures & Operations

This document focuses on **failure modes, recovery flows, and operational guidance** for the tick system.

It is aimed at both developers and operators who need to understand what happens when executors crash, Redis has issues, or ticks must be replayed.

For the canonical, detailed design, see `design/architecture/system-architecture-ticks.md`.

## What This Covers

- Crash recovery and replay behavior.
- Idempotency rules tied to `tickId`.
- Handling stuck or partial tick entries.
- Design checklist for new tick-driven commands.

## Key Sections in the Main Tick Doc

The following sections in `system-architecture-ticks.md` contain the main failure-handling and operational rules:

- **Crash Recovery and Replay** – how executors recover from failure and resume processing safely.
- **Domain Idempotency Rules (TickId in PostgreSQL)** – how tick IDs enforce idempotent domain mutations.
- **Design Checklist for New Tick-Driven Commands** – review checklist for new commands to ensure they follow tick invariants.
- **Tick Execution and Redis Integration** – failure scenarios and invariants around the canonical commit pattern.
- **Cross-Region Command Execution and Result Relay** – constraints for cross-region retries and replay.

When implementing new failure-handling flows or adding operational procedures, ensure the detailed behavior is captured in `system-architecture-ticks.md` and reflected in the appropriate runbooks (for example, Redis incident runbooks).

## Crash Recovery and Replay

Tick recovery is driven by Redis coordination state plus domain-level idempotency rules:

- On executor crash or failover, a new worker acquires the region lease, inspects `tick:{tenantRegionTag}:pending`, `retry:{tenantRegionTag}`, and timers, and replays or resumes work based only on persisted state.
- Redis is treated as a volatile coordination layer with **at-least-once** semantics; network retries, executor failover, and AOF replay can all cause the same logical effect to be attempted more than once.
- Domain services rely on `tickId` and effect guards to ensure that replays do not double-apply logical effects even when Redis state is partially lost.

### Tick Effect Ledger and Replay Guarantees

To make replays observable and bounded, Game Session maintains a **tick effect ledger** in PostgreSQL. Conceptually:

- Every tick effect that can be staged in `tick:{tenantRegionTag}:pending` or in retry/timer queues is mirrored into a Game Session–owned ledger table (for example `tick_effects`) with columns such as:
  - `tenant_id`, `region_id`, `tick_id`
  - `effect_key` (stable, human-readable descriptor passed through from staging)
  - `command_id`
  - `status` ∈ {`SCHEDULED`, `APPLIED`, `ABANDONED`}
  - `reason` / `outcome`
  - `created_at`, `updated_at`
- For any `(tenant_id, region_id, tick_id, effect_key)` there must eventually be **exactly one terminal state**:
  - `status = APPLIED` – effect successfully committed to domain state.
  - `status = ABANDONED` – effect intentionally skipped or judged unrecoverable.
- Rows must not remain in `SCHEDULED` beyond a configured grace window; stuck rows are treated as operational smells and surfaced via metrics and alerts.

Replay of a tick is driven from ledger state:

- When reprocessing a tick, the executor loads ledger rows for that `<tenantId, regionId, tickId>` with `status = SCHEDULED` and:
  - Re-queues/re-runs effects whose domain targets do not yet reflect `APPLIED`, then marks those rows `APPLIED`.
  - Marks effects `ABANDONED` with a precise reason when replay determines they are no longer valid (expired session, entity gone, descheduled tick, and so on).
  - Marks effects `APPLIED` and skips domain calls when it determines the effect has already been applied idempotently.
- Every Lua script that stages effects is required to include the `effect_key` used in the ledger so Redis `pending` entries can always be correlated with ledger rows; staging scripts that cannot be tied back to a ledger identity are rejected.

The ledger makes replay visible operationally via metrics such as:

- `tick.effects_pending_total`
- `tick.effects_applied_total`
- `tick.effects_abandoned_total{reason}`
- `tick.effects_replayed_total`

Alerts fire when:

- Pending (`SCHEDULED`) counts remain above thresholds for longer than a tick window, or
- The abandoned ratio grows unexpectedly for a region or effect type.

These metrics complement the Redis- and lock-level health metrics described in the Redis architecture and operations docs.

Common scenarios and invariants:

- **Primary crash, AOF fully up to date**
  - Redis: `pending`, locks, timers, and retry metadata preserved for recent ticks.
  - PostgreSQL: all committed effects durably stored.
  - Invariants: no double-apply; at-most-one executor per region after lease re-acquisition.
  - Action: new executor replays any surviving `pending` entries; ticks complete or are retried automatically.
- **Crash during AOF window (tail loss)**
  - Redis: some recent `pending`/lock/queue keys for the last ticks may be missing.
  - PostgreSQL: effects applied before the crash remain; very recent, in-flight effects may or may not have been applied.
  - Invariants: no double-apply; some ticks may be lost or need manual reconstruction.
  - Action: metrics and dashboards surface gaps or stuck regions; recovery tooling may mark missing ticks as `FAILED`/`SKIPPED` and clear inconsistent Redis state once operators have reviewed.
- **GC pause > `lock_ttl_ms` but < `lease_ttl_ms`**
  - Redis: locks may expire and be reacquired; `pending` remains; lease still held by original executor.
  - PostgreSQL: any effects applied before the pause remain consistent; replays of the same `(tenantId, regionId, tickId, effectKey)` are treated as no-ops by idempotent handlers.
  - Invariants: no double-apply; lease ownership unchanged; at-most-one executor per region.
  - Action: late work that fails token checks is retried; regions with persistent over-TTL behavior may be marked degraded until configuration or workload is adjusted.
- **GC pause > `lease_ttl_ms` (lease lost)**
  - Redis: lease may move to a new executor; `pending` and queues are preserved subject to the AOF window.
  - PostgreSQL: effects applied by the old executor before losing the lease remain consistent; the new executor replays the same effects with idempotent handlers.
  - Invariants: no double-apply; at-most-one active executor per region enforced by lease tokens.
  - Action: work the old executor attempts to perform after lease loss is discarded; the new executor drives recovery; the region may be degraded until behavior stabilizes.
- **Redis coordination cluster outage**
  - Redis: coordination keys temporarily unavailable; a tail window of recent `pending`/lock/queue state may be lost depending on failure and AOF configuration.
  - PostgreSQL: remains authoritative; effects committed before the outage are not rolled back.
  - Invariants: no double-apply; some ticks may be lost or skipped, but already-applied effects are not undone.
  - Action: Game Session halts ticks/commands for affected regions, following the Redis outage policy; after Redis recovers, the recovery subsystem and operators decide whether to skip, retry, or repair missing ticks.

These scenarios assume the Redis AOF is configured as described in `system-architecture-redis.md`. If AOF or replication settings differ, the same idempotency rules still apply, but the tail window for potential tick loss may change.

## Stalled Regions and Downstream Behavior

Stalled regions are those that still hold `tick-executor-lease:{tenantRegionTag}` but have not made forward progress (for example, no successful commits or too many consecutive failures) for several multiples of `tick_interval_ms`, as defined in the concepts doc. Once a region is classified as stalled:

- The scheduler stops scheduling new ticks for that `<tenantId, regionId>` until progress recovers or operators intervene.
- New gameplay commands targeting that region are rejected with a clear “region unavailable”–style error instead of being accepted and queued behind a non-advancing executor.
- The existing executor continues renewing the lease for a short grace period so no other instance takes over and attempts the same failing work against unhealthy dependencies.
- If a large number of regions on the same instance become stalled, that instance’s readiness/liveness may be marked unhealthy so orchestration can restart it once downstream services (for example PostgreSQL, other gRPC services) are healthy again.

Lease expiry and failover remain about coordination safety (avoiding split-brain on Redis state). Stalled-region handling layers a progress watchdog on top: leases describe who owns a region’s coordination keys, while stalled/healthy state describes whether that owner is actually advancing game state.

## Stuck Pending Entries and Recovery

In rare cases, a `tick:{tenantRegionTag}:pending` entry may remain present even though repeated replays cannot complete successfully (for example, due to a persistent domain bug). A small recovery subsystem handles these **stuck ticks**:

- A background watcher scans metrics and/or a compact Redis/PostgreSQL index of `pending` entries to identify candidates, such as:
  - `pending` keys that have existed across multiple tick intervals with exhausted retries.
  - Regions where `tick:{tenantRegionTag}:pending` has not advanced despite repeated recovery attempts.
- Candidate stuck ticks are enqueued into a `tick_recovery` queue or table with metadata such as `<tenantId, regionId, tickId, firstSeenAt, lastRetryAt>`.
- An automated recovery worker:
  - Marks clearly terminal ticks as `FAILED` or `SKIPPED` in PostgreSQL using the same idempotency guards as normal handlers.
  - Clears `tick:{tenantRegionTag}:pending` and associated retry metadata via a dedicated, idempotent helper path.
  - Emits detailed logs and metrics for audit and dashboards.
- Operator tooling allows manual override for complex cases (for example, suspected data corruption), with two typical modes:
  - **Recommendation mode** – the system proposes recoveries; operators approve or override.
  - **Auto-recovery mode** – low-risk patterns are resolved automatically once thresholds are met.

Retry and timer queues are protected against unbounded growth:

- Retry queues (`retry:{tenantRegionTag}`) are ZSETs keyed by next-eligible execution time; scripts process at most `N` entries per invocation and enforce a maximum retry budget per action.
- Timer keys (`timer:{tenantRegionTag}`) are ZSETs keyed by due time; scripts pop at most `N` timers per call and delete processed members.
- Defensive limits (for example, maximum timers per region) trigger alerts or throttling if exceeded so bugs cannot create unbounded timer or retry growth.

Entity Management provides the reference example for per-aggregate tick idempotency; see `microservices/entity-management-service/README.md#tick-idempotency`.

## Domain Idempotency Rules (TickId in PostgreSQL)

Domain services must treat `tickId` as the canonical idempotency token for tick-driven effects. Two patterns are used:

- **Per-aggregate last-tick state**
  - Aggregates that are updated at most once per tick (for example, a character’s core stats row or a room’s dynamic state row) maintain a shadow tick-state record such as `entity_tick_state` keyed by the aggregate identifier.
  - The shadow state stores at minimum a `last_tick_id` field (plus tenant/region identifiers or a foreign key implying them).
  - When applying a tick effect:
    - The handler reads the current tick state.
    - If `last_tick_id >= currentTickId`, the update is treated as a replay or out-of-order attempt and becomes a no-op (or, in strict modes, a validation-only check).
    - If `last_tick_id < currentTickId`, the handler applies the change and updates `last_tick_id = currentTickId` in the same transaction as the domain mutation.
- **Operation-level effect guard**
  - Operations that may touch multiple aggregates or legitimately apply multiple distinct effects to the same aggregate in a single tick (for example trades, AoE damage, or multi-target buffs) use a small guard table such as `tick_effect_guard` keyed by:
    - `tenant_id`
    - `region_id`
    - `tick_id`
    - `effect_key` – a deterministic identifier describing the logical effect (for example `entity:<entityId>:award:achievement:<achievementId>` or `room:<roomId>:drop:item:<itemId>`).
  - Inside the same transaction as the domain update, the handler attempts to insert `(tenant_id, region_id, tick_id, effect_key)`:
    - If the insert succeeds, the effect is new for this tick and the handler applies all associated state changes.
    - If the insert conflicts on primary key, the effect has already been applied for this `(tenantId, regionId, tickId, effectKey)` and the handler treats the call as a replay:
      - In the simple case, it returns success without reapplying changes.
      - In stricter flows, it may verify that current state is consistent with the previously applied effect before returning.

Examples:

- **Single-entity damage (per-aggregate last-tick state)**
  - `ApplyDamage` in Entity Management receives `(tenantId, regionId, tickId, entityId, damageAmount)`.
  - It reads `entity_tick_state` for `entityId` and compares `last_tick_id` to `tickId`.
  - If `last_tick_id >= tickId`, the handler treats the request as a replay and returns without changing HP.
  - If `last_tick_id < tickId`, it subtracts `damageAmount` and updates `last_tick_id = tickId` in `entity_tick_state` in the same transaction.
- **Trade between two entities (operation-level effect guard)**
  - `TradeItem` receives `(tenantId, regionId, tickId, fromEntityId, toEntityId, itemId)`.
  - It computes `effectKey = "trade:" + fromEntityId + ":" + toEntityId + ":" + itemId`.
  - In one transaction it:
    - Attempts to insert `(tenantId, regionId, tickId, effectKey)` into `tick_effect_guard`.
    - If the insert conflicts, it treats the call as a replay and returns success without modifying inventories.
    - If the insert succeeds, it debits the item from `fromEntityId`, credits it to `toEntityId`, and commits both inventory changes and the guard-row insert together.

Operationally:

- Every tick-driven write path must use either the per-aggregate `last_tick_id` pattern or the operation-level guard pattern.
- Domain handlers treat Redis locks and leases as opaque; they never read `tick:{tenantRegionTag}:lock:<entityId>` or `tick-executor-lease:{tenantRegionTag}` directly.
- Operations that cannot be made idempotent or compensatable at the domain layer—for example payments, emails, or webhooks into third-party systems—must not be executed directly inside tick-driven handlers. Those flows must use the saga/outbox patterns in `system-architecture-transactions.md` so they can tolerate retries and partial failures independently of tick replay.

### Effect Identity, Endpoint Semantics, and Outcome Metrics

Tick-driven domain calls use a **canonical effect identity** and a shared contract for retries:

- Effect identity is derived deterministically from tick context and target aggregate identity; conceptually it includes:
  - `tenantId`
  - `tickId` (region-scoped)
  - `effectKey` (stable descriptor such as `damage:entity:<id>:command:<commandId>`)
  - `targetAggregateType` (for example `ENTITY`, `INVENTORY`, `ROOM_STATE`, `QUEST`, `ACHIEVEMENT`)
  - `targetAggregateId`
  - Optional `domainScope` when needed to avoid collisions across independently owned domains.
- Game Session derives this identity while computing tick outcomes and passes it to each tick-invoked handler; handlers must not generate fresh random IDs for idempotency in tick paths.

Every gRPC endpoint that can be invoked from tick execution must document and implement:

- **Duplicate handling** – repeated calls with the same effect identity must return OK / “already applied” semantics (for example, no new HP or inventory changes) instead of logical errors that drive infinite retries.
- **Already-in-desired-state handling** – if the target state already reflects the intended outcome, the endpoint returns OK and does not emit additional side effects.
- **Retry classification** – errors must be classified as retryable (transient infrastructure issues) vs terminal (invalid inputs, missing aggregates). Terminal errors must move the corresponding ledger/guard entry into a terminal state (for example `ABANDONED`) instead of leaving work stuck in a retry loop.

Endpoints participating in tick-driven effects should also emit a small, standardized metric:

- `tick.effect_outcome_total{service, effect_type, outcome}`
  - `service` – owning microservice (for example `entity-management-service`).
  - `effect_type` – low-cardinality side-effect category (for example `entity_state`, `inventory`, `quest`, `room_state`), **not** the full effect identity.
  - `outcome` – `first_apply`, `replay_ok`, or `guard_error` (for unexpected failures at the idempotency boundary).

This metric provides a cross-service view of how often replay paths are exercised and highlights handlers that are not honoring the canonical idempotency contract.

### Side-Effect Categories and Idempotency Strategies

Different side-effect categories may use different persistence primitives, but each must be explicitly tied back to tick-driven effect identity:

- **Award-once side effects** (items, currency, XP):
  - Use a durable “effect applied” ledger keyed by the effect identity (or a unique equivalent) with insert-if-absent semantics.
  - On uniqueness conflict, treat the call as an idempotent replay and return OK without applying additional changes.
- **Monotonic state changes** (achievements, unlocks):
  - Use monotonic fields or unique constraints such as `(tenantId, playerId, achievementId)` plus a mapping to effect identity when they trigger additional rewards or notifications.
- **Notifications and events**:
  - Use transactional outbox entries keyed by effect identity (often `eventId == effectId`) so producers deduplicate sends; consumers may still apply defensive deduplication.
- **Versioned aggregates and CAS-style updates**:
  - Compare-and-set/version checks must treat replay-stale writes as OK/no-op outcomes instead of “conflict” errors that cause unbounded retries.

Together with the tick effect ledger, these patterns ensure that Redis coordination and replay logic remain simple while each domain service guarantees that its persistent mutations and outward side effects are safe to attempt multiple times.

## Remote Hint Markers and Resets

Cross-region flows may use best-effort Redis hint markers such as `remote:<tenantId>:<entityId>` or `remote:<tenantId>:<targetEntityId>` to reduce latency when draining remote follow-ups. Operationally:

- These markers are **latency hints only**:
  - They may be overwritten, duplicated, or lost.
  - Correctness is derived from durable follow-up rows in PostgreSQL, not from the presence of `remote:*` keys.
- Region-level coordination resets do not attempt to delete `remote:*` keys because these keys are tenant-scoped rather than region-scoped.
- After a region reset, the next tick executor:
  - Resumes draining due follow-ups from PostgreSQL into its normal tick pipeline.
  - Treats any stale or missing `remote:*` markers as affecting only how quickly it notices new work, not whether the work is eventually applied.

When debugging cross-region issues, operators should rely on PostgreSQL follow-up tables, tick effect ledgers, and the metrics described in the execution-flow docs rather than assuming `remote:*` keys are authoritative.

## Testing Tick Idempotency and Redis Replays

Because crash recovery relies on idempotent handlers, each service with tick-driven logic should include integration tests that simulate Redis-style replays:

- Invoke the same handler multiple times with identical `(tenantId, regionId, tickId, effectKey, payload)` and assert that:
  - The first call mutates state as expected.
  - Subsequent calls are treated as replays and do not apply additional logical effects (HP changes, inventory moves, etc.).
- Exercise both idempotency strategies:
  - Per-aggregate `last_tick_id` tables (for single-entity updates).
  - Operation-level `tick_effect_guard` tables (for multi-entity effects).
- Where practical, share a small test harness that:
  - Constructs synthetic `tick:{tenantRegionTag}:pending` payloads.
  - Drives the same sequence of domain calls multiple times, mimicking replay of a pending tick after a crash.
  - Verifies that final PostgreSQL state is identical regardless of how many times the tick is “reapplied”.

CI pipelines should run these replay tests; changes to tick handlers that break idempotency ought to fail tests before reaching production.

## Design Checklist for New Tick-Driven Commands

When introducing a new command type that will run under tick control, design docs and code reviews should explicitly cover:

- **Is this command tick-driven?**
  - Does it run because an entry is dequeued from `tick:{tenantRegionTag}:queue:<entityId>` or because a tick timer/retry fired?
  - If not, it may follow different idempotency rules and does not belong in this section.
- **What is the idempotency key?**
  - For single-aggregate updates: which `last_tick_id` field and table enforce “at most one update per tick” for that aggregate?
  - For multi-aggregate or multi-effect operations: what is the `effect_key` used in `tick_effect_guard`, and how is it derived deterministically from the command payload?
- **Where is the guard persisted?**
  - Which schema/table holds `last_tick_id` or `tick_effect_guard` entries?
  - Is there a primary key or unique index that enforces the idempotency key at the database level?
- **What happens on replay?**
  - What does the handler do when it detects that the guard already exists or `last_tick_id >= currentTickId`?
  - Is the “replay” outcome clearly documented and tested (no new logical effects, optional consistency verification)?
- **Are there any non-idempotent external effects?**
  - If the handler sends email, charges a payment method, or calls an external API with irreversible effects, how is that separated from the tick-driven part (for example, via an outbox entry processed by a saga)?

Pull requests that add new tick-driven commands should link back to this checklist and show how each item is satisfied before the feature is considered complete.
