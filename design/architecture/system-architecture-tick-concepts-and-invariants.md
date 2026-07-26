# FireMUD Tick System: Concepts & Invariants

This document summarizes the **core concepts and invariants** of the FireMUD tick system. It is aimed at developers and reviewers who need to understand fairness, region authority, and idempotency without reading the full runtime design in `system-architecture-ticks.md`.

## What This Covers

- Hybrid tick model and player/AI fairness.
- Region authority, leadership, and locking.
- High-level retry, isolation, and idempotency rules.
- Region health, stalled-region detection, and tick pacing (including idle/background ticks and global fan-out).

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

- **Single authoritative executor per region** – all tick-side state for a `<tenantId, gameInstanceId, regionId>` is owned by one executor at a time.
- **Lease and lock tokens are authoritative** – region leases and per-entity locks in Redis always carry opaque tokens; tick scripts must validate those tokens (and the current `tickId`) inside a single Lua invocation before applying or cleaning up any staged work.
- **One action per entity per tick** – fairness is enforced by limiting how many **tick work items** (player commands, AI/automation commands, due timers, retries, and remote follow-ups) a single entity can execute per tick. The scheduler and tick-execution flow choose at most one such work item per entity per tick; any additional due work for that entity is deferred to later ticks according to the retry and scheduling rules. This applies equally to player commands, AI scripts, automation, and remote follow-ups drained from other regions (which are enqueued into the same per-entity queues at the target region).
- **No cross-region locks** – cross-region interactions are modeled as messages, not shared locks or multi-region transactions.
- **Idempotent side effects** – the region-scoped tick timeline `(region_epoch, tickId)` and effect guards must be used so that replays after failure do not double-apply mutations.

The tick system adopts the same **coordination timeline** concept as the Redis architecture: for each `<tenantId, gameInstanceId, regionId>` there is a canonical timeline defined by `(region_epoch, tickId)`. Within a given `region_epoch`:

- `tickId` is monotonic and uniquely identifies each committed tick for that region.
- All **staged tick coordination state** in Redis (for example `tick:{tenantRegionTag}:pending` entries and other data created for a specific in-flight tick) and all tick effect ledger rows in PostgreSQL conceptually belong to exactly one `(region_epoch, tickId)` pair.
- Region-scoped source structures such as `tick:{tenantRegionTag}:queue:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, and `tick-executor-lease:{tenantRegionTag}` are primarily **epoch-scoped** rather than tick-scoped:
  - They belong to the current `region_epoch`.
  - They carry eligibility/order metadata that later maps selected work into a specific `(region_epoch, tickId)` when the tick batch is formed.
  - Reset/replay tooling must therefore distinguish “epoch-scoped source state” from “tick-scoped staged state” rather than treating every region key as already owned by one committed or in-flight tick.
- Tenant-scoped coordination such as gameplay session keys (`session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`) live on Coordination Redis but are **not** bound to a single region epoch; they follow the authentication/reconnection contracts and reset behavior described in the Redis hub and usage/profile docs rather than the per-region epoch model.
- When a scoped coordination reset occurs (or a topology/maintenance operation explicitly severs the old region timeline), the tick control plane bumps `region_epoch` and ensures that subsequent tick work for that `<tenantId, gameInstanceId, regionId>` is scheduled only on the new timeline; survivors from older epochs in region-scoped Redis keys are treated as stale and either ignored or explicitly reconciled via the tick effect ledger and reset tooling.

The canonical `tenantRegionTag` used in coordination keys is an opaque encoding of the complete `<tenantId, gameInstanceId, regionId>` scope. In particular, a tick event stream or offset must never use a tenant-plus-region tag that omits `gameInstanceId`; two game instances of one tenant must have disjoint coordination and observer-hint namespaces.

The main tick document contains the detailed rules and Redis key shapes behind each of these points.

### Fairness Under Tail-Loss and Resets

Fairness and the “one action per entity per tick” rule apply to steady-state execution within a stable `region_epoch`. Around the coordination tail-loss window and explicit resets:

- Redis tail-loss and scoped coordination resets may cause some actions near the tail of the timeline to be dropped, replayed, or slightly re-ordered.
- In these cases, the system prioritizes **EffectId convergence** (each `(tenantId, gameInstanceId, playableStateScope, regionId, region_epoch, tickId, effectKey, targetAggregateType, targetAggregateId)` ends up durably APPLIED or ABANDONED without double-apply) over strict per-entity fairness across the reset boundary.
- Designers should treat fairness guarantees as strong within a healthy epoch and best-effort across failures and resets; if a feature requires stronger guarantees around resets, that requirement must be called out explicitly in its design and validated against the Redis tail-loss SLOs.

Conceptually, domain services treat Redis locks and leases as **opaque tick-engine concerns**: handlers see only `(tenantId, gameInstanceId, playableStateScope, regionId, region_epoch, tickId, effectKey, targetAggregateType, targetAggregateId)` plus their own idempotency state. Handlers never read `tick:{tenantRegionTag}:lock:<entityId>` or `tick-executor-lease:{tenantRegionTag}` to make application-level decisions, nor do they depend on Cache/Rate-Limit Redis keys (for example `inventory:*`, `view:*`, `ratelimit:*`) for correctness or ordering. Cache usage, when present, is encapsulated inside domain services and affects only latency, not the tick engine’s notion of “what happened” or “in which order”.

- **Target-state automation identity:** For script-generated commands, `effectKey` must incorporate the command-level `(automationDispatchId, commandOrdinal)`; `scriptEventId` alone cannot distinguish fan-out commands.
- **Current-live fallback:** The Game Session handoff does not yet carry `commandOrdinal` or the full Trigger Identity. `TickStagingService` currently derives `effectKey` from `commandId`, falling back to a hash of command text plus the staging slot when no command id is available. This fallback is not the target-state automation identity and must not be documented as though `(automationDispatchId, commandOrdinal)` were enforced live.

### Tail-Loss and Tick Replay Window

Redis coordination state is subject to a bounded tail-loss envelope (see `system-architecture-redis.md` and `system-architecture-redis-operations.md`). From the tick system’s perspective:

- A normal failover or restart may drop or replay the last `N` ticks for a `<tenantId, gameInstanceId, regionId>`, where the loss window is bounded by the canonical Redis SLO formula:
  - `tail_loss_budget_ms = max(2000, 2 * tick_interval_ms)` (see `system-architecture-redis-operations.md`).
- Tick-driven designs must tolerate:
  - Some commands and timers near the tail of the timeline being lost, re-ordered slightly, or replayed.
  - Region leases being briefly lost and re-acquired under the same or a new `region_epoch`.

## Tick Tail-Loss Contract

The tick system and Redis tail-loss SLOs combine into a simple contract:

- For each `(tenantId, gameInstanceId, playableStateScope, regionId, region_epoch, tickId, effectKey, targetAggregateType, targetAggregateId)` there must eventually be exactly one **terminal** outcome in PostgreSQL (`APPLIED` or `ABANDONED`), even if:
  - The last few ticks for that region are dropped or replayed within the tail-loss envelope, or
  - Executors crash and re-acquire leases under the same `region_epoch`.
- Any work that cannot be safely replayed after Redis loss or tick re-execution must be:
  - Guarded with idempotency checks that detect and short-circuit replays, or
  - Intentionally marked `ABANDONED` in the tick effect ledger with a precise reason (for example, reset scopes as described in the failures and operations doc).

Players may observe brief rollbacks or duplicated feedback around failover boundaries, but they must never experience permanent double-application of critical effects or silent corruption of authoritative state.

Redis tail-loss thresholds are defined in `system-architecture-redis-operations.md` under Redis availability and safety guarantees. This section captures only the conceptual relationship between those budgets and the tick invariants they are meant to uphold.

### Isolation Within a Tick

Per-tick isolation is defined explicitly so replay and fairness remain deterministic:

- Actions may only see staged state from the **current** tick for their `<tenantId, gameInstanceId, regionId>`.
- Changes from other tick regions or from **future** ticks in the same region are invisible while a tick is in progress.
- Changes staged earlier in the same tick are composable: later actions in that tick may observe them when computing their own outcomes.
- When required state is missing or inconsistent, the action must fail and retry under the normal retry/backoff rules rather than speculatively mixing cross-tick or cross-region reads.

These rules ensure clean, replayable ticks and keep visual or scripting shortcuts from leaking inconsistent state across ticks.

## Locking and Multi-Entity Commands (Conceptual)

Distributed locking in the tick system is designed to avoid deadlocks and keep Lua scripts small and predictable:

- **Default: one entity lock per script**
  - Tick Lua scripts are written, by default, to acquire at most one `tick:{tenantRegionTag}:lock:<entityId>` per invocation.
  - Multi-entity commands decompose into per-entity legs keyed by the same region-scoped timeline `(region_epoch, tickId)` plus effect identifiers; cross-entity consistency is enforced at the PostgreSQL layer via idempotency guards and coordinator records rather than by holding multiple Redis locks at once.
- **Registry-backed lock and lease management only**
  - Tick and lease keys such as `tick:{tenantRegionTag}:lock:<entityId>` and `tick-executor-lease:{tenantRegionTag}` are created, renewed, and released exclusively via Lua scripts registered in the shared Lua Script Registry.
  - Ad-hoc Redis commands (for example `SET NX PX` or direct deletes) must not be used to manipulate these coordination keys; all flows go through the registry helpers so lock tokens and lease epochs are validated and updated consistently across services.
- **Strict limits on multi-lock scripts**
  - Commands that truly cannot be decomposed and must lock multiple entities inside a single script must:
    - Acquire locks in a global, deterministic order (for example, sort all `entityId` values and acquire in ascending order).
    - Operate entirely within a single `<tenantId, gameInstanceId, regionId>`; cross-region multi-lock scripts are not allowed.
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
- Treated as absolute wall-clock due times: changing `tick_interval_ms` does not rescale existing timer scores, and any time-scaling logic must be applied when scheduling (producing a new `due_ms`), not by rewriting past timers.
- Drained with bounded work per tick (for example, up to `game.tick-max-timers` timers per region per tick) so delayed or bursty timers do not turn a single tick into unbounded work.
- Implemented using deterministic, idempotent Lua scripts that accept `now_ms` as a caller-supplied `ARGV` value; scripts must not call Redis `TIME`, and AOF replay reuses the same `ARGV` values.

All writes to timer keys (`timer:{tenantRegionTag}`) are performed under the same region lease and Lua scripts as tick processing; domain services must not modify timer keys via ad-hoc Redis commands. This keeps timers and command queues in the same concurrency domain.

Timer and retry keys are **volatile coordination structures**, not durable schedules. After coordination resets or data loss, only schedules that are also represented durably elsewhere (for example PostgreSQL-backed automation schedules or explicit domain state) are expected to be recovered or re-derived; the existence of a `timer:{tenantRegionTag}` entry is never the only record of a correctness-critical timer.

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
- `tick_budget_ms` – the soft execution budget for a tick (how long the tick engine is allowed to hold locks and perform work). FireMUD uses a shared, canonical derivation:
  - `tick_budget_ms = tick_interval_ms * 0.8`
- `lock_ttl_ms` – the TTL used for per-entity locks (for example `tick:{tenantRegionTag}:lock:<entityId>`), derived from the budget using a bounded multiplier:
  - `lock_ttl_ms = clamp(tick_budget_ms * 8, 500, 5_000)`

These formulas are implemented once in shared tick/Redis helpers and consumed by Game Session and participating services; individual services must not define their own alternative lock/budget formulas. The defaults are chosen to give generous headroom for GC pauses and hiccups without requiring per-environment tuning; in most deployments, operators primarily adjust `tick_interval_ms`.

The only allowed exception is an explicit **solo-tick budget mode** for commands marked `requiresSoloTick: true`:

- `solo_tick_budget_ms` is a second canonical shared setting, not an ad-hoc per-command override.
- When enabled for a tick, `solo_lock_ttl_ms` is derived from `solo_tick_budget_ms` using the same shared helper family and operator-visible health model.
- The scheduler must admit solo-budget ticks only when the command is the sole work item for that region tick.
- A deployment that does not enable `solo_tick_budget_ms` must treat `requiresSoloTick` as isolation-only; it does not permit budget overruns beyond the normal `tick_budget_ms`.
- During a solo-budget tick, health and alerting use the solo-derived ratios (`tick_execution_time_ms_* / solo_lock_ttl_ms`) for that tick rather than the normal `tick_lock_ttl_ms` denominator.

Changing tick cadence is also constrained by replay determinism:

- `tick_interval_ms` is fixed within a live `region_epoch`.
- Any cadence change that would alter timer ordering or due normalization requires an explicit `regionEpoch` bump and timer re-derivation/reconciliation for the affected region.

Worked cadence-change example:

1. Region `R7` is running at `tick_interval_ms = 100` in `regionEpoch = 13`.
2. Operators decide to move `R7` to `tick_interval_ms = 200`.
3. Game Session pauses the region and bumps `regionEpoch` to `14` rather than changing cadence in place.
4. Timer ordering state is re-derived for the new epoch, including canonical `due_tick_id` values for any timers that must survive the change.
5. The new epoch resumes at `lastCommittedTickId = -1`, so the first committable tick under the new cadence is `tickId = 0`.

At runtime, observed tick durations are compared against lock TTLs using Prometheus-facing series such as `tick_execution_time_ms_p95` and `tick_execution_time_ms_p99` (derived from `tick_execution_time_ms_bucket` recording rules). Ratios like `tick_execution_time_ms_p99 / tick_lock_ttl_ms` drive region health for each `<tenantId, gameInstanceId, regionId>`.

### Canonical Region Health States and Threshold Source

This table is the single source of truth for region health state names and threshold intent across tick, Redis, and scaling docs:

| State | Meaning | Primary triggers |
| --- | --- | --- |
| `RUNNING` | Region is making normal forward progress. | `tick_execution_time_ms_p99 / tick_lock_ttl_ms` remains below the degraded threshold and commit progress is advancing. During an admitted solo-budget tick, evaluate the same state against `solo_lock_ttl_ms` instead. |
| `DEGRADED` | Region is still progressing but close to safety limits. | `tick_execution_time_ms_p99 / tick_lock_ttl_ms` is near or above the degraded threshold over a sustained window, or remote/retry backlog exceeds budget. During an admitted solo-budget tick, evaluate the same state against `solo_lock_ttl_ms` instead. |
| `STALLED` | Region lease may still be held but progress has stopped. | No successful commits for multiple `tick_interval_ms` windows, repeated failed ticks, or persistent stuck cleanup/ledger signals. |
| `PAUSED` | Region is intentionally paused by control plane or maintenance flow. | Operator/control-plane pause for reset, migration, or incident mitigation. |

Threshold values and alert windows are defined by this document’s ratio formulas plus the concrete metric thresholds in `system-architecture-redis-operations.md` and enforced through `tick_status{status="RUNNING|DEGRADED|STALLED|PAUSED"}`.

In addition to timing-based health, Game Session tracks **forward progress** for each `<tenantId, gameInstanceId, regionId>`:

- A simple progress record includes:
  - The tickId or timestamp of the last successful commit.
  - A counter of consecutive failed ticks due to downstream errors or timeouts (not mere lock contention).
- A region is treated as **stalled** when it still holds `tick-executor-lease:{tenantRegionTag}` but has not committed successfully for several multiples of `tick_interval_ms` or has exceeded a threshold of consecutive failures.

Conceptually:

- Lease ownership indicates **who** is allowed to coordinate work for a region.
- Progress signals indicate whether that owner is actually advancing game state.

Downstream behavior for stalled regions (rejecting new commands, marking instances unhealthy, and so on) is described in the failures and operations doc; this section captures only the invariants that distinguish “lease held but stalled” from `RUNNING`, `DEGRADED`, and intentionally `PAUSED` regions.

## Retry and Backoff Invariants

Lock contention and transient failures are handled by a bounded retry and backoff policy that preserves fairness:

- Each rescheduled action carries a per-command retry counter and a `next_eligible_tick_id` value; retries are delayed using an exponential backoff in ticks, for example `nextTick = currentTick + min(2^retryCount, MAX_BACKOFF_TICKS)`.
- Retries are appended to the back of the originating entity’s queue and are scheduled **no earlier than a future tick**; the executor never spins inside a single tick waiting for locks.
- After a bounded number of failed attempts (for example `MAX_RETRIES`), the command is marked permanently failed, a player-visible error is emitted, and metrics/logs capture the contention so operators can see hotspots.
- Fairness is guaranteed per entity: within a given entity’s queue, commands are processed FIFO; cross-entity fairness is best-effort and driven by normal tick scheduling plus the backoff rules.
- Metrics such as `tick_conflict_hotspot_detected_total` and `tick_retry_queue_depth` surface regions where contention is persistent so operators can adjust region layout, tick budgets, or feature design.

These invariants ensure that contention is handled predictably: retries remain bounded, hot entities cannot monopolize the loop indefinitely, and operators have clear signals when configuration or design changes are required.

At the configuration level:

- Lua staging scripts enforce hard per-tick caps on how many commands or events move from queues into `pending`, using keys such as:
  - `game.tick-max-commands`
  - `automation.tick-max-events`
- These caps exist so no single player or script can monopolize the tick loop, even if they enqueue many actions; excess work spills into subsequent ticks according to the same fairness rules.

Runtime health is also expressed via ratios such as `tick_execution_time_ms_p95` or `tick_execution_time_ms_p99` over `tick_lock_ttl_ms`:

- `RUNNING` – p99 execution time is well below `tick_lock_ttl_ms` (for example, < 0.5 × `tick_lock_ttl_ms`).
- `DEGRADED` – p99 execution time is approaching or exceeding `tick_lock_ttl_ms` over a sustained window.
- `STALLED` – forward progress has stopped even if timing ratios are noisy or unavailable.

This keeps fairness and safety enforceable through bounded per-tick work and timing-based health checks without introducing alternate state names in other docs.

## Tick Regions, Global Effects, and Idle Background Ticks

Tick work is scoped to **regions** so that:

- Regions advance independently and can be assigned to different executors.
- Failures and stalls are isolated to a region rather than the whole game.

Two additional behaviors complete the mental model:

- **Global or multi-region effects use fan-out**:
  - World-wide or multi-region events are implemented by Game Session injecting commands into each affected region and forcing a tick there.
  - This ensures the effect is applied even if a region would otherwise be idle; the work still runs under that region’s normal lease, locks, and fairness rules.
- **Idle regions still advance time via lightweight ticks**:
  - Idle/background behavior does not create a second slower canonical tick cadence inside the same live `region_epoch`.
  - Regions continue to use the configured `tick_interval_ms` timeline for timer ordering and `tickId` advancement; “background” means reduced work and wake-up pressure when there are no due commands or timers, not a different epoch-local clock.
  - Any true cadence change for an idle region still requires the same explicit epoch bump and timer re-derivation rules described above.

The underlying Redis key layout and shard-locality rules for these behaviors are documented in the Redis architecture; this section captures the conceptual guarantees for designers and implementers.
