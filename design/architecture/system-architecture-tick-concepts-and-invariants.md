# FireMUD Tick System: Concepts & Invariants

This document summarizes the **core concepts and invariants** of the FireMUD tick system. It is aimed at developers and reviewers who need to understand fairness, region authority, and idempotency without reading the full runtime design in `system-architecture-ticks.md`.

## What This Covers

- Hybrid tick model and player/AI fairness.
- Region authority, leadership, and locking.
- High-level retry, isolation, and idempotency rules.

## Key Sections in the Main Tick Doc

The following sections in `system-architecture-ticks.md` provide the primary conceptual model:

- **Hybrid Tick Model** – overall tick loop structure and fairness guarantees.
- **Tick Events & Heartbeat Stream** – how other services (such as Automation & Scripting) follow the tick timeline via gRPC streams.
- **Region Authority and Tick Executor** – single-writer semantics per region and executor lease behavior.
- **Distributed Locking** – how locks are scoped and enforced to avoid deadlocks.
- **Smart Retry and Conflict Resolution** – canonical patterns for retries under contention.
- **Tick Regions and Parallel Execution** – how work is partitioned to scale horizontally.
- **Region Sizing and Ownership** – guidance for region boundaries and ownership changes.
- **Timeout and Fairness Policy** – how long-running commands are handled without starving others.
- **Isolation and Replay Safety** – invariants that ensure deterministic replays and safe recovery.
- **Timers and Time Scaling** – how tick timers interact with scaled time and the heartbeat stream.

Refer to those sections for the authoritative wording and examples.

## Invariants to Preserve

When designing new tick-driven features, keep these invariants in mind:

- **Single authoritative executor per region** – all tick-side state for a `<tenantId, regionId>` is owned by one executor at a time.
- **Lease and lock tokens are authoritative** – region leases and per-entity locks in Redis always carry opaque tokens; tick scripts must validate those tokens (and the current `tickId`) inside a single Lua invocation before applying or cleaning up any staged work.
- **One action per entity per tick** – fairness is enforced by limiting how many commands a single entity can execute per tick. This applies equally to player commands, AI scripts, and automation.
- **No cross-region locks** – cross-region interactions are modeled as messages, not shared locks or multi-region transactions.
- **Idempotent side effects** – tick IDs and effect guards must be used so that replays after failure do not double-apply mutations.

The main tick document contains the detailed rules and Redis key shapes behind each of these points.

Conceptually, domain services treat Redis locks and leases as **opaque tick-engine concerns**: handlers see only `(tenantId, regionId, tickId, effectKey)` plus their own idempotency state. They never read `tick:{tenantRegionTag}:lock:<entityId>` or `tick-executor-lease:{tenantRegionTag}` to make application-level decisions.

## Locking and Multi-Entity Commands (Conceptual)

Distributed locking in the tick system is designed to avoid deadlocks and keep Lua scripts small and predictable:

- **Default: one entity lock per script**
  - Tick Lua scripts are written, by default, to acquire at most one `tick:{tenantRegionTag}:lock:<entityId>` per invocation.
  - Multi-entity commands decompose into per-entity legs keyed by a shared `tickId` and effect identifiers; cross-entity consistency is enforced at the PostgreSQL layer via idempotency guards and coordinator records rather than by holding multiple Redis locks at once.
- **Strict limits on multi-lock scripts**
  - Commands that truly cannot be decomposed and must lock multiple entities inside a single script must:
    - Acquire locks in a global, deterministic order (for example, sort all `entityId` values and acquire in ascending order).
    - Operate entirely within a single `<tenantId, regionId>`; cross-region multi-lock scripts are not allowed.
  - If any required lock cannot be acquired, the script immediately releases all previously acquired locks and returns a contention result; no partial logical effects are applied for that command.
- **Registry and CI enforcement (see Redis docs)**
  - The Lua Script Registry records, per script, a declared `max_entity_locks` value (default `1`) and enforces a small hard cap for multi-lock scripts.
  - Only an explicit whitelist of scripts may set `max_entity_locks > 1`; adding a new multi-lock script is treated as an architectural change and must update the whitelist and documentation.
  - CI fails builds when a script attempts to use more entity-lock keys than declared or sets `max_entity_locks > 1` without being on the whitelist.

These rules keep deadlock scenarios rare, move complex coordination into transactional domain services, and make it clear when a feature is relying on exceptional multi-lock behavior.

## Timers and Time Scaling (Conceptual)

Tick timers (cooldowns, regeneration, delayed effects) are:

- Stored in per-region sorted sets such as `timer:{tenantRegionTag}`, where the score is an absolute millisecond timestamp and each member encodes the target entity/effect.
- Evaluated using a single, consistent application time source (NTP-synchronized wall clock); Redis server time is not used for timer comparisons.
- Drained with bounded work per tick (for example, up to `game.tick-max-timers` timers per region per tick) so delayed or bursty timers do not turn a single tick into unbounded work.

All writes to timer keys (`timer:{tenantRegionTag}`) are performed under the same region lease and Lua scripts as tick processing; domain services must not modify timer keys via ad-hoc Redis commands. This keeps timers and command queues in the same concurrency domain.

Durations may be scaled dynamically:

- `scaledDuration = baseDuration * timeScaleMultiplier`
- Used for spell effects, world modifiers (for example, “slow motion” zones), or status changes.
- Time scaling changes **durations**, not tick cadence; ticks themselves advance at their configured interval.

## Bounded Effect Chaining

Tick-driven effects may enqueue follow-up actions (for example, explosions spawning secondary hits, traps triggering additional effects, or scripted chains). To avoid unbounded recursion and re-entrancy:

- Each action carries a `tickChainDepth` that increments whenever the action spawns follow-up work.
- A configuration value such as `MAX_TICK_CHAIN_DEPTH` (default 8) defines the maximum allowed chain depth.
- When the chain depth limit is exceeded, the new action is not enqueued; prior committed effects remain in place, and a warning is logged so designers can tune or fix the offending behavior.

This invariant ensures that even highly scripted encounters remain bounded and observable under the tick model.

## Tick Budget, TTLs, and Region Health (Conceptual)

Two related configuration concepts control how long tick work is allowed to run and how long leases/locks are held:

- `tick_interval_ms` – the configured target interval between ticks for a region.
- `tick_budget_ms` – the soft execution budget for a tick (how long the tick engine is allowed to hold locks and perform work), typically derived from `tick_interval_ms` (for example, `tick_budget_ms = tick_interval_ms * 0.8`) but adjustable independently when needed.

From this budget, the Game Session Service derives TTLs for locks and leases using fixed multipliers (see the Redis architecture doc for full details). The defaults are chosen to give generous headroom for GC pauses and hiccups without requiring per-environment tuning; in most deployments, operators primarily adjust `tick_interval_ms`.

At runtime, observed tick durations are compared against lock TTLs using histograms such as `tick.execution_time_ms_p95` and `tick.execution_time_ms_p99`. Ratios like `tick.execution_time_ms_p99 / lock_ttl_ms` drive a simple health model for each `<tenantId, regionId>`:

- **Healthy** – p99 execution time comfortably below the lock TTL.
- **Degraded** – p99 execution time approaching the TTL; regions may emit warnings and metrics recommending configuration or design changes.
- **Unsafe** – p99 execution time close to or exceeding the TTL over a sustained window; the scheduler treats this as a configuration or architecture error and may slow or temporarily halt ticks for the affected region until configuration or workload is adjusted.

Region health transitions (`HEALTHY` → `DEGRADED` → `HALTED`) and the exact thresholds are defined in `system-architecture-redis.md` under Redis availability and safety guarantees. This document captures only the conceptual relationship between tick budgets, TTLs, and region health.

## Retry and Backoff Invariants

Lock contention and transient failures are handled by a bounded retry and backoff policy that preserves fairness:

- Each rescheduled action carries a per-command retry counter and a `next-eligible-tick` value; retries are delayed using an exponential backoff in ticks, for example `nextTick = currentTick + min(2^retryCount, MAX_BACKOFF_TICKS)`.
- Retries are appended to the back of the originating entity’s queue and are scheduled **no earlier than a future tick**; the executor never spins inside a single tick waiting for locks.
- After a bounded number of failed attempts (for example `MAX_RETRIES`), the command is marked permanently failed, a player-visible error is emitted, and metrics/logs capture the contention so operators can see hotspots.
- Fairness is guaranteed per entity: within a given entity’s queue, commands are processed FIFO; cross-entity fairness is best-effort and driven by normal tick scheduling plus the backoff rules.
- Metrics such as `tick_conflict_hotspot_detected_total` and `tick_retry_queue_depth` surface regions where contention is persistent so operators can adjust region layout, tick budgets, or feature design.

These invariants ensure that contention is handled predictably: retries remain bounded, hot entities cannot monopolize the loop indefinitely, and operators have clear signals when configuration or design changes are required.
