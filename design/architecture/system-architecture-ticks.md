# FireMUD System Architecture: Tick System and Runtime Design

📄 This document expands on the [Game Loop / Tick Model](./system-architecture-overview.md#⏱️-game-loop--tick-model) section of the FireMUD System Architecture Overview. It defines how ticks execute, resolve concurrency, handle crashes, and preserve deterministic, fair game logic under load.

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

---

## Hybrid Tick Model

FireMUD uses a **hybrid tick model** to balance real-time responsiveness with deterministic action resolution:

- Actions are queued per entity (players, NPCs, scripted automation).
- At each tick, the region executor pulls at most one action per entity and resolves them in a fair order so player commands, AI, and automation are treated equivalently.
- State changes are applied as an atomic “tick transaction” per region.

Conceptually, FireMUD treats time as **localized pulses** rather than a single global clock: each tick is a self-contained transaction for its region that composes safely with others.

See `system-architecture-tick-concepts-and-invariants.md` for the full description of fairness guarantees and queueing rules.

---

## Tick Events & Heartbeat Stream

Two related concepts:

- **Tick execution** – the authoritative per-region loop inside the Game Session Service.
- **Tick heartbeat** – a gRPC stream (`StreamTickHeartbeats`) exposing `tickId` progression so external services (for example Automation & Scripting) can align timers and quotas to the canonical tick timeline.

In addition to the gRPC heartbeat, the Game Session Service exposes a **tick event stream** for schedulers and observers:

- Events are keyed by `<tenantId, regionId>` and include:
  - `tickId` (monotonic per region).
  - `regionId` / shard metadata.
  - The timestamp when the tick began.
  - The `activeVersionId` pinned for that tick.
- Consumers (for example, schedulers or reconnection logic) typically:
  - Acquire a small lease such as `tick-events-lease:{tenantRegionTag}` to avoid duplicate processing.
  - Persist their last-processed offset in Redis so they can resume from the correct `tickId` after restarts.
- The stream may be implemented via Redis Streams or pub/sub as long as:
  - Per-region ordering is preserved.
  - Consumers can replay from the stored offset.

This event stream powers “every N ticks” scheduling in Automation & Scripting, reconnection timer replay hints, and other out-of-band reporting. Tick execution itself never depends on these observers.

Automation & Scripting Service instances typically:

- Establish long-lived gRPC streams to `StreamTickHeartbeats` for the tenants/regions they own.
- Maintain per-region scheduler state in Redis (for example `script-scheduler:{tenantRegionTag}:lastTickId`) so they can compute which “every N ticks” boundaries have elapsed and enqueue `onInterval` and other tick-derived triggers under the same tick timeline.

Tick execution never depends on external buses; external services consume the heartbeat stream and/or tick event stream only. See `system-architecture-tick-concepts-and-invariants.md` and `system-architecture-scripting-dsl-and-lifecycle.md` for details.

---

## Region Authority and Tick Executor

For each `<tenantId, regionId>` there is exactly one active tick executor (Game Session Service worker) at any given time. It:

- Owns tick queues, timers, and retries for that region.
- Holds the region lease in Redis.
- Drives staging and commit for that region’s ticks.

Other workers may be running but do not process ticks for that region while the lease is held. See `system-architecture-tick-concepts-and-invariants.md` for the full authority and lease model.

---

## Distributed Locking

Tick execution uses **per-entity locks** in Redis to coordinate concurrent actions within a region. Locks:

- Are acquired in deterministic order to avoid deadlocks.
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

- A scheduler or consistent-hash layer maps `<tenantId, regionId>` to Game Session instances.
- To rebalance load, the current executor:
  - Stops renewing the region lease for selected regions.
  - Drains in-flight work to a safe boundary (for example, after the current `pending` tick is committed or recovered).
- Another instance then acquires the lease and continues tick processing from the existing Redis and PostgreSQL state for that region.

For most deployments, region topology changes (splits, merges, or reassignments between executors) are applied between game instances or during maintenance windows so active sessions are not disrupted.

World Management owns region topology (layout and `<regionId>` assignments) and may, over time, support “drain and split” or “merge” flows:

- Split flows mark a region for split, allow existing ticks to complete, and then move entities plus queues under new `<tenantId, regionId>` prefixes before ticks resume.
- Merge flows consolidate lightly used regions into a single region to reduce overhead.

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
- Commit: call into domain services, which apply changes using `tickId` and effect guards to ensure idempotency.

Full commit-pattern details are in `system-architecture-tick-execution-flows.md` and the Redis docs.

The Game Session Service and Redis own the full tick transaction lifecycle (staging, commit, and rollback); the Game Logic Service remains stateless with respect to tick transactions and is responsible only for deterministic resolution of actions, not for managing tick commit or rollback.

Some commands (for example, heavy runtime procedural generation) declare `requiresSoloTick: true`. For these commands:

- The scheduler runs the command alone in its own tick so it does not compete with other player actions.
- The tick may be allowed a larger execution budget (for example, up to a few hundred milliseconds) while still respecting the region’s lease and TTL rules.

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
- A shared coordination timeline `(region_epoch, tickId)` per `<tenantId, regionId>` as described in the Redis architecture docs.
- Domain-level idempotency rules keyed by `tickId` and effect identifiers.

Crash recovery replays staged ticks safely by re-invoking domain handlers; replays must not double-apply logical effects. Even when Redis loses or replays up to a few ticks within the tail-loss envelope, the combination of the coordination timeline, the tick effect ledger, and per-service idempotency guards ensures that each `(tenantId, regionId, region_epoch, tickId, effectKey)` converges to a single terminal outcome (`APPLIED` or `ABANDONED`). See `system-architecture-tick-failures-and-operations.md` for the detailed story.

---

## Timers and Time Scaling

Tick timers (cooldowns, regeneration, delayed effects) are:

- Stored and scheduled via Redis timer keys.
- Aligned with the tick heartbeat and tick cadence.
- Subject to time-scaling rules that speed up or slow down perceived time while preserving ordering.

Details of timer key shapes and scaling strategies live in `system-architecture-tick-concepts-and-invariants.md` and `system-architecture-scripting-dsl-and-lifecycle.md`.

---

## Crash Recovery and Replay

On executor crash or failover, a new worker:

- Acquires the region lease.
- Inspects staged tick metadata and timers.
- Replays or resumes work based only on persisted state (`tick:{tenantRegionTag}:pending`, `retry:{tenantRegionTag}`, and domain idempotency tables).

See `system-architecture-tick-failures-and-operations.md` for the full crash-recovery algorithm and failure modes.

---

## Domain Idempotency Rules (TickId in PostgreSQL)

Domain services must ensure that tick-driven effects are **idempotent** with respect to `tickId`:

- Single-aggregate updates use a `last_tick_id` pattern.
- Multi-aggregate operations use effect guard tables with stable effect identifiers.

### Tick Effect Identity and Idempotency Contract

Effect identity and idempotency rules are defined jointly by:

- The `tickId` carried on tick-driven calls.
- A stable, structured effect identity (for example including `tenantId`, `tickId`, `effectKey`, aggregate type, and aggregate id) derived deterministically from the command payload and tick context.

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
