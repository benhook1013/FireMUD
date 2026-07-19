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

- **Single authoritative executor per region** – all tick-side state for a `<tenantId, regionId>` is owned by one executor at a time.
- **Lease and lock tokens are authoritative** – region leases and per-entity locks in Redis always carry opaque tokens; tick scripts must validate those tokens (and the current `tickId`) inside a single Lua invocation before applying or cleaning up any staged work.
- **One intentional actor action per entity per tick** – under [ADR 0051](./decisions/adr-0051-separate-actor-action-and-effect-lanes.md), player commands, AI decisions, automation commands, actor-action timers, and their retries compete for one actor-action slot. Passive status/environmental work, incoming remote effects, actor-generated consequences, and their retries use a separate bounded effect lane and do not consume the target's action slot. Both lanes retain deterministic persisted ordering plus per-entity and region-wide count/cost budgets.
- **Fair deterministic entity selection** – eligible work is ordered within its entity, while persisted rotating/deficit scheduler state selects entities under reserved lane budgets. The selected manifest and scheduler advance commit together so replay preserves the decision and sustained backlog cannot indefinitely starve a permanently eligible non-best-effort entity within a healthy epoch. See [ADR 0065](./decisions/adr-0065-deterministic-fair-entity-tick-scheduling.md).
- **One in-flight tick through cleanup** – a region does not stage tick `N+1` until tick `N` is durably terminal and matching fenced coordination cleanup has cleared its pending state and locks.
- **No cross-region locks** – cross-region interactions are modeled as messages, not shared locks or multi-region transactions.
- **Idempotent side effects** – the region-scoped tick timeline `(region_epoch, tickId)` and effect guards must be used so that replays after failure do not double-apply mutations.

The tick system adopts the same **coordination timeline** concept as the Redis architecture: for each `<tenantId, regionId>` there is a canonical timeline defined by `(region_epoch, tickId)`. Within a given `region_epoch`:

- `tickId` is monotonic and uniquely identifies each committed tick for that region.
- All **staged tick coordination state** in Redis (for example `tick:{tenantRegionTag}:pending` entries and other data created for a specific in-flight tick) and all tick effect ledger rows in PostgreSQL conceptually belong to exactly one `(region_epoch, tickId)` pair.
- Region-scoped source structures such as `tick:{tenantRegionTag}:queue:*`, `timer:{tenantRegionTag}`, `retry:{tenantRegionTag}`, and `tick-executor-lease:{tenantRegionTag}` are primarily **epoch-scoped** rather than tick-scoped:
  - They belong to the current `region_epoch`.
  - They carry eligibility/order metadata that later maps selected work into a specific `(region_epoch, tickId)` when the tick batch is formed.
  - Reset/replay tooling must therefore distinguish “epoch-scoped source state” from “tick-scoped staged state” rather than treating every region key as already owned by one committed or in-flight tick.
- Tenant-scoped coordination such as gameplay session keys (`session:game:{tenantGameplayTag}:<gameInstanceId>:<sessionId>`) live on Coordination Redis but are **not** bound to a single region epoch; they follow the authentication/reconnection contracts and reset behavior described in the Redis hub and usage/profile docs rather than the per-region epoch model.
- When a scoped coordination reset occurs (or a topology/maintenance operation explicitly severs the old region timeline), the tick control plane bumps `region_epoch` and ensures that subsequent tick work for that `<tenantId, regionId>` is scheduled only on the new timeline; survivors from older epochs in region-scoped Redis keys are treated as stale and either ignored or explicitly reconciled via the tick effect ledger and reset tooling.

The main tick document contains the detailed rules and Redis key shapes behind each of these points.

### Fairness Under Tail-Loss and Resets

Fairness and the actor-action/effect-lane rules apply to steady-state execution within a stable `region_epoch`. Around the coordination tail-loss window and explicit resets:

- Redis failover and scoped coordination resets may delay, replay, terminalize, or slightly reorder scheduling projections; accepted commands and correctness-bearing effects retain the class-specific durable outcomes in ADR 0058.
- In these cases, the system prioritizes **EffectId convergence** (each `(tenantId, regionId, region_epoch, tickId, effectKey)` ends up durably APPLIED or ABANDONED without double-apply) over strict per-entity fairness across the reset boundary.
- Designers should treat fairness guarantees as strong within a healthy epoch and best-effort across failures and resets; if a feature requires stronger guarantees around resets, that requirement must be called out explicitly in its design and validated against the Redis tail-loss SLOs.

Conceptually, domain services treat Redis locks and leases as **opaque tick-engine concerns**: handlers see only `(tenantId, regionId, region_epoch, tickId, effectKey)` plus their own idempotency state. They never read `tick:{tenantRegionTag}:lock:<entityId>` or `tick-executor-lease:{tenantRegionTag}` to make application-level decisions, nor do they depend on Cache/Rate-Limit Redis keys (for example `inventory:*`, `view:*`, `ratelimit:*`) for correctness or ordering. Cache usage, when present, is encapsulated inside domain services and affects only latency, not the tick engine’s notion of “what happened” or “in which order”.

### Coordination-Loss Observation and Tick Replay

Redis coordination exposure is measured by the environment-specific unreplicated-write-window SLO, not a tick-derived product RPO. Tick-driven designs tolerate delayed, rebuilt, terminalized, or replayed coordination projections while preserving ADR 0058's class-specific durable outcomes. Region leases may be lost and re-acquired under the same or a new `region_epoch`; scheduler fairness is best effort across that reset boundary and resumes from authoritative source work plus durable scheduler/batch state.

## Tick Coordination-Loss Contract

The tick system and Redis coordination-loss observations combine into a simple contract:

- For each durably claimed or staged effect `(tenantId, regionId, region_epoch, tickId, effectKey)` there must eventually be exactly one **terminal** outcome in PostgreSQL (`APPLIED` or `ABANDONED`), even if:
  - Recent Redis scheduling/staging projections are lost or replayed, or
  - Executors crash and re-acquire leases under the same `region_epoch`.
- Execution attempts are physically at least once. Owner-local identity/digest guards permit at most one logical mutation, and `REPLAY_NOOP` is recorded as outcome evidence beneath terminal `APPLIED`; the contract does not promise one physical handler invocation.
- An accepted command that never becomes durably claimed or staged has no effect-ledger row to abandon. Its command record instead converges to `executionOutcome = LOST_BEFORE_STAGING` and `gameplayResult = NOT_APPLIED` under the command-lifecycle contract.
- Any work that cannot be safely replayed after Redis loss or tick re-execution must be:
  - Guarded with idempotency checks that detect and short-circuit replays, or
  - Intentionally marked `ABANDONED` in the tick effect ledger with a precise reason (for example, reset scopes as described in the failures and operations doc).

Players may observe delay, explicit non-application, or duplicated presentation feedback around failover boundaries, but committed authoritative state does not roll back, logical effects do not double-apply, and effect-ledger status remains distinct from the derived command result.

The measured unreplicated-write-window SLO is defined in `system-architecture-redis-operations.md`; ADR 0058 defines product outcomes. This section captures only their relationship to tick replay invariants.

### Isolation Within a Tick

Per-tick isolation uses bounded semantic-phase visibility rather than a universal speculative overlay:

- The tick records a stable committed pre-tick causal base for its tenant, game instance, region, epoch, and prior committed tick. This is logical resolution evidence, not a claim of distributed MVCC, equal cross-service versions, or the presentation-only causal floor from ADR 0059.
- Start-of-tick passive and inbound effects execute first and must have authoritative durable results before actor resolution begins.
- Root actor actions use one persisted stable post-passive resolution basis. They do not observe mutations from other root actor actions in the same tick, regardless of manifest order or completion timing.
- Generated effects may depend only on their own parent's durable confirmed result and use the parent's persisted order plus deterministic child ordinals. They do not gain arbitrary visibility into unrelated root actions.
- Owner services still enforce exact scope, epoch, location, holder, identity, request-digest, and aggregate-version preconditions. Recorded ordering or phase evidence never substitutes for mutation guards.
- Raw Redis `pending`, uncommitted staged intent, independently fresh mixed-fence reads, other regions, and future ticks are never valid resolution inputs. Missing required evidence produces a bounded wait, failure, or retry.
- Replay uses the recorded causal base, post-passive resolution basis, manifest order, parent result, and child ordinals. It does not re-resolve from newer state.

Consequently, passive poison may prevent an actor action and a confirmed attack may generate immediate lifesteal, but one root actor opening a door, dropping an item, buffing an ally, or stunning another actor ordinarily affects that other actor on the next tick. A future feature may opt into a catalogued cross-root same-tick dependency only through a separate design that records its dependency, ordering, partial-failure, and replay semantics.

## Locking and Multi-Entity Commands (Conceptual)

Distributed locking in the initial tick system uses a hard one-entity-lock boundary so Lua scripts remain small and Redis coordination is not mistaken for domain atomicity:

- **Exactly one entity lock per lock-acquiring script**
  - A tick Lua invocation acquires at most one `tick:{tenantRegionTag}:lock:<entityId>`. There is no initial multi-entity whitelist or cap above one, and piecemeal acquisition across invocations is prohibited.
  - Multi-entity commands decompose into per-entity legs keyed by the same region-scoped timeline `(region_epoch, tickId)` plus effect identifiers; cross-entity consistency is enforced at the PostgreSQL layer via idempotency guards and coordinator records rather than by holding multiple Redis locks at once.
  - When affected aggregates share one domain owner, that owner may enforce the complete invariant in one PostgreSQL transaction. Split-authority work uses exact identity/version/location preconditions, durable effect legs, bounded retry, and reconciliation or saga/outbox handling appropriate to its invariant class.
- **Registry-backed lock and lease management only**
  - Tick and lease keys such as `tick:{tenantRegionTag}:lock:<entityId>` and `tick-executor-lease:{tenantRegionTag}` are created, renewed, and released exclusively via Lua scripts registered in the shared Lua Script Registry.
  - Ad-hoc Redis commands (for example `SET NX PX` or direct deletes) must not be used to manipulate these coordination keys; all flows go through the registry helpers so lock tokens and lease epochs are validated and updated consistently across services.
- **Registry and CI enforcement (see Redis docs)**
  - The Lua Script Registry records `max_entity_locks` and requires it to be `0` or `1`; CI rejects any script or call shape that can acquire more than one entity lock.
  - Redis locking remains a coordination optimization. It never replaces the current executor fence, exact owner preconditions, request-digest idempotency, or the owning database transaction.

Trades, grapples, group movement, capacity checks, and multi-target combat remain expressible through owner transactions and durable effects. What is initially forgone is only a low-latency multi-entity Redis reservation optimization. Adding atomic bounded multi-lock Lua later requires a new ADR with measured concurrent contention, Redis event-loop/TTL/failure proof, and the dependency-wave model for concurrent regional execution; piecemeal acquisition remains forbidden.

## Timers and Time Scaling (Conceptual)

Tick timers (cooldowns, regeneration, delayed effects) are:

- Stored in per-region sorted sets such as `timer:{tenantRegionTag}`, where the score is an absolute millisecond timestamp and each member encodes the target entity/effect.
- Evaluated using a single, consistent application time source (NTP-synchronized wall clock); Redis server time is not used for timer comparisons.
- Treated as absolute wall-clock due times: changing `tick_interval_ms` does not rescale existing timer scores, and any time-scaling logic must be applied when scheduling (producing a new `due_ms`), not by rewriting past timers.
- Drained with bounded work per tick (for example, up to `game.tick-max-timers` timers per region per tick) so delayed or bursty timers do not turn a single tick into unbounded work.
- Implemented using deterministic, idempotent Lua scripts that accept `now_ms` as a caller-supplied `ARGV` value; scripts must not call Redis `TIME`, and AOF replay reuses the same `ARGV` values.

Every authored timer declares its clock unit and recovery class:

- Clock is `wall_clock` (persisted absolute due time) or `tick_game_time` (committed tick cadence). A cadence change does not rescale an existing absolute wall-clock deadline; future tick/game-time intervals intentionally follow the new real-world cadence.
- `correctness_one_shot` persists intent outside Redis and converges to one logical execution or an explicit terminal outcome.
- `durable_recurring` declares `SKIP_MISSED` or `COALESCE_ONE`. Coalescing admits at most one synthetic firing for that logical schedule per durable resume window, subject to one deterministic fair global cap; excluded candidates are audited rather than deferred.
- `advisory_cosmetic` may drop missed occurrences and resume at a future occurrence.

Features that need elapsed downtime to matter compute one bounded deterministic aggregate effect rather than replaying every missed firing.

All writes to timer keys (`timer:{tenantRegionTag}`) are performed under the same region lease and Lua scripts as tick processing; domain services must not modify timer keys via ad-hoc Redis commands. This keeps timers and command queues in the same concurrency domain.

Timer and retry keys are **volatile coordination structures**, not durable schedules. After coordination resets or data loss, only schedules that are also represented durably elsewhere (for example PostgreSQL-backed automation schedules or explicit domain state) are expected to be recovered or re-derived; the existence of a `timer:{tenantRegionTag}` entry is never the only record of a correctness-bearing or durable-recurring timer.

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
- `tick_budget_ms` – the soft execution budget for a tick (how long the tick engine is allowed to hold locks and perform work). The shared bootstrap derivation is:
  - `tick_budget_ms = tick_interval_ms * 0.8`
- `lock_ttl_ms` – the TTL used for per-entity locks (for example `tick:{tenantRegionTag}:lock:<entityId>`). The shared bootstrap derivation is:
  - `lock_ttl_ms = clamp(tick_budget_ms * 8, 500, 5_000)`

These formulas are safe bootstrap defaults, not permanent production evidence. They are implemented once in shared tick/Redis helpers; individual services must not define alternate derivations. `tick_interval_ms` is gameplay cadence, configured only at its declared game/operator levels within caps and fixed for one live epoch. `tick_budget_ms` and `lock_ttl_ms` are operator safety settings within platform hard bounds. Production values are calibrated from p95/p99 execution, participant RPC latency/error, GC and scheduler pauses, cleanup lag, representative backlog, recovery objectives, and fault tests. Lower-level overrides are accepted only when that individual key declares eligibility and still satisfy operator caps and platform bounds.

Lease possession, the durable executor fence, exact owner preconditions, and idempotency guards provide correctness after expiry. Lock TTL instead controls liveness, duplicate attempt frequency, contention duration, and takeover delay.

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

At runtime, health reports cadence/execution-budget pressure, lock-expiry risk, durable commit progress, cleanup lag, and work/recovery backlog separately. Prometheus-facing series such as `tick_execution_time_ms_p95` and `tick_execution_time_ms_p99` may feed both execution-versus-budget and execution-versus-lock-TTL ratios, but one ratio must not stand in for the other dimensions.

### Canonical Region Health States and Threshold Source

This table is the single source of truth for region health state names and threshold intent across tick, Redis, and scaling docs:

| State | Meaning | Primary triggers |
| --- | --- | --- |
| `RUNNING` | Region is making normal forward progress. | Execution-versus-budget and lock-expiry risk remain below their separate degraded thresholds, cleanup and backlog remain bounded, and commit progress is advancing. Solo ticks use their corresponding solo budget and TTL dimensions. |
| `DEGRADED` | Region is still progressing but close to safety limits. | Execution-budget pressure, lock-expiry risk, cleanup lag, or remote/retry/recovery backlog exceeds its sustained threshold while commits still advance. Solo ticks use their corresponding solo budget and TTL dimensions. |
| `STALLED` | Region lease may still be held but progress has stopped. | No successful commits for multiple `tick_interval_ms` windows, repeated failed ticks, or persistent stuck cleanup/ledger signals. |
| `PAUSED` | Region is intentionally paused by control plane or maintenance flow. | Operator/control-plane pause for reset, migration, or incident mitigation. |

Threshold values and alert windows are environment-calibrated within platform bounds, emitted through the canonical metrics defined in `system-architecture-redis-operations.md`, and enforced through `tick_status{status="RUNNING|DEGRADED|STALLED|PAUSED"}`. Environments must retain separate signals rather than raising one threshold to conceal failure in another dimension.

In addition to timing-based health, Game Session tracks **forward progress** for each `<tenantId, regionId>`:

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
