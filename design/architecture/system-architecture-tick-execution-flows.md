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
   - Game Session accepts commands from Telnet/WebSocket clients, AI, or automation.
   - Commands are enqueued into per-entity (or occasionally per-region) queues such as `tick:{tenantRegionTag}:queue:<entityId>`.
2. **Target Resolution (read-only)**
   - During the tick, the executor resolves targets from the pinned snapshot for that `<tenantId, regionId>`:
     - Single-target actions select a specific entity or room.
     - Multi-target actions derive a bounded set of entity IDs from local region state (room occupants, threat lists, groups).
   - This phase is read-only with respect to durable state; it decides *what* to touch without mutating Redis or PostgreSQL.
3. **Region-Local Mutations**
   - For purely local effects, the executor acquires the relevant entity lock(s) under `tick:{tenantRegionTag}:lock:<entityId>` and stages effects into `tick:{tenantRegionTag}:pending` via Lua.
   - Domain services apply changes under local transactions and idempotency rules keyed by `(tenantId, regionId, tickId, effectKey)`.
4. **Cross-Region Effects (if any)**
   - For cross-region commands, the origin region:
     - Applies local-only effects first (for example, text feedback, animations).
     - Records durable follow-up work in PostgreSQL for the target entities (tick effect ledger / follow-up tables), with a stable effect identity.
     - Optionally writes a best-effort Redis hint marker such as `remote:<tenantId>:<targetEntityId>` to reduce latency when the target region drains follow-ups.
   - The target region later drains these follow-ups into its own tick pipeline and applies them under its lease and locks.
5. **Completion / Finalization (optional)**
   - Many commands do not need global awareness of “all regions finished”; origin and target regions can operate independently with eventual consistency.
   - For flows that truly require end-to-end completion (for example, complex cross-region trades), the origin region may track success/failure from participating regions and apply a final status (success, partial, failed) once all responses or timeouts are observed.

Every phase must be idempotent with respect to `tickId` and effect identity so replays after failure do not double-apply effects.

## Tick Execution Flow

At each tick for a `<tenantId, regionId>`, the executor:

1. Collects work:
   - Pulls at most one queued command per active entity.
   - Pulls a bounded number of due timers and retries (up to configured caps per tick).
   - Optionally includes a bounded “remote follow-up drain” step (see below).
2. Orders fairly:
   - Ensures at most one action per entity per tick to preserve fairness.
   - Orders commands using a combination of arrival time, stat-based priority, and any configured scheduling policy.
3. Stages effects:
   - Under the region lease and entity locks, calls Lua scripts to write intended effects into `tick:{tenantRegionTag}:pending`.
4. Applies and commits:
   - Invokes domain services to apply effects under idempotent rules.
   - Runs a final Lua commit/cleanup script to reconcile Redis state, clear `pending`, and release locks.

Commands that cannot complete inside the configured tick budget are deferred via retry queues rather than blocking the current tick.

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

Best-effort hint markers (`remote:<tenantId>:<entityId>`) are only allowed to influence latency. Correctness is derived solely from the durable follow-up records in PostgreSQL and the idempotent handling of those records in the target region’s tick pipeline.

## Tick Chaining and Reentrant Effect Control

Some effects (for example, explosions, chained spells, scripted traps) spawn follow-up actions. To keep this behavior bounded:

- Each action tracks a `tickChainDepth` that increments whenever it spawns follow-up work.
- A configuration such as `MAX_TICK_CHAIN_DEPTH` (default 8) defines the maximum allowed chain depth for a single originating action.
- When a follow-up would exceed `MAX_TICK_CHAIN_DEPTH`:
  - The new action is not enqueued.
  - Existing committed effects remain in place.
  - A warning is logged so designers can adjust the feature or its tuning, and the player may receive a message indicating the chain was halted.

Because chained effects still respect the “one action per entity per tick” rule, this depth guard is primarily a protection against runaway re-entrancy and unbounded script-driven chain reactions.
