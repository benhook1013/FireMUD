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
- **One action per entity per tick** – fairness is enforced by limiting how many commands a single entity can execute per tick. This applies equally to player commands, AI scripts, and automation.
- **No cross-region locks** – cross-region interactions are modeled as messages, not shared locks or multi-region transactions.
- **Idempotent side effects** – tick IDs and effect guards must be used so that replays after failure do not double-apply mutations.

The main tick document contains the detailed rules and Redis key shapes behind each of these points.

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
