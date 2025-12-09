# FireMUD System Architecture: Tick System and Runtime Design

📄 This document expands on the [Game Loop / Tick Model](./system-architecture-overview.md#⏱️-game-loop--tick-model) section of the FireMUD System Architecture Overview. It defines how ticks execute, resolve concurrency, handle crashes, and preserve deterministic, fair game logic under load. Cross-service operations triggered by ticks rely on Redis scripts and gRPC; sagas are unnecessary for these gameplay actions as explained in [Transaction Strategies](./system-architecture-transactions.md).

> 🔗 For Redis keys, Lua-based atomicity, and operational guarantees, see the [Redis Architecture](./system-architecture-redis.md).
---

## Hybrid Tick Model

FireMUD uses a **Hybrid Tick Model** to balance real-time responsiveness with deterministic action resolution:

- **Actions are queued per entity** (players, NPCs, and scripted automation) in individual **command queues**
- At regular **tick intervals** (e.g., 1s):
  - A `TickScheduler` in the Game Session Service triggers `processTick` for each active tick region
  - One action (if any) is pulled from each entity's command queue
  - Actions are resolved in FIFO order and support stat-based prioritization
  - Only **one action per entity per tick** is executed for fairness
  - State changes are applied in a single coordinated pass

This model ensures:

- Responsive feel for players
- Deterministic conflict resolution (e.g., pickups, interrupts)
- Equal treatment of AI and player actions
- Scheduled updates for effects like cooldowns, patrols, regeneration

> 🔗 Overview of this model appears in [System Architecture Overview](./system-architecture-overview.md#⏱️-game-loop--tick-model)

---

## Tick Events & Heartbeat Stream

Two related but distinct concepts appear in the design:

- **Tick execution** – the authoritative per-region game loop driven entirely inside the Game Session Service.
- **Tick heartbeat** – a read-only feed of `tickId` progression that external services (such as the Automation & Scripting Service) observe so they can align their own timers and quotas with the canonical tick timeline.

Tick execution itself never depends on external event buses. Instead:

- The Game Session Service owns the tick loop and all Redis keys under `tick:{tenantId}:{regionId}:...`.
- External services interact with tick progression via a **gRPC server-streaming API**, not via direct access to tick queues.

A representative API shape is:

- `rpc StreamTickHeartbeats(TickHeartbeatRequest) returns (stream TickHeartbeat)` on the Game Session Service’s gRPC API surface.
  - `TickHeartbeatRequest` selects which `{tenantId, regionId}` pairs (or tenant-wide selectors) the caller is interested in.
  - Each `TickHeartbeat` carries at least `tenantId`, `regionId`, and `tickId`, plus optional shard metadata for routing.

Automation & Scripting Service instances:

- Establish long-lived gRPC streams to `StreamTickHeartbeats` for the tenants/regions they own.
- For each heartbeat message:
  - Update `script-scheduler:{tenantId}:{regionId}:lastTickId` in Redis.
  - Compute which “every N ticks” boundaries have elapsed since the last processed tick.
  - Enqueue due `onInterval` and other tick-derived script triggers, subject to quotas and budgets.

This makes it explicit that **tick heartbeats and script timers are driven by gRPC streams**, while the **tick loop and command queues remain internal** to the Game Session Service and Redis.

---

## Region Authority and Tick Executor

Tick regions are coordinated by a **single authoritative executor** at any point in time:

- For each `{tenantId, regionId}` there is exactly one active tick executor (a Game Session Service instance / worker) that:
  - Reads commands from `tick:{tenantId}:{regionId}:queue:*`, timers from `timer:{tenantId}:{regionId}`, and retries from `retry:{tenantId}:{regionId}`.
  - Drives `tick:{tenantId}:{regionId}:pending` and the associated commit/rollback scripts.
  - Issues tick-scoped gRPC calls to domain services (Entity Management, World Management, Social Groups, Automation, etc.).
- Other workers may be running but **do not process ticks for that region** while the current executor holds the leadership lease described in the [Redis Architecture](./system-architecture-redis.md#region-leadership-and-tick-executor-lease).

On crash or failover:

- A new Game Session instance acquires the `tick-executor-lease:{tenantId}:{regionId}` key.
- It inspects `tick:{tenantId}:{regionId}:pending`, `retry:{tenantId}:{regionId}`, and `timer:{tenantId}:{regionId}`.
- It resumes tick processing using the existing idempotency rules (`tickId`, `last_tick_id`, `tick_effect_guard`) so replays are safe and partially-applied ticks can be completed or skipped deterministically.

The **region boundary** is therefore the unit of atomicity and authority:

- All tick locks, staging, timers, retry metadata, and session participation for a `{tenantId, regionId}` are owned by that region’s active executor.
- No lock, Lua script, or tick context spans multiple regions. Cross-region flows are modeled as **messages between region executors**, not shared locks.

### Tick Effect Ledger and Replay Guarantees

Tick coordination relies on Redis for the fast staging surface, but PostgreSQL remains the source of truth for whether a tick effect was ever applied. To make this explicit:

- Every tick effect that can be staged in `tick:{tenantId}:{regionId}:pending` or the retry/timer queues must also write a durable row in a “tick effect ledger” table (`tick_effects` or similar) owned by the Game Session Service. Each row records `tenant_id`, `region_id`, `tick_id`, `effect_key`, `command_id`, `status` (`SCHEDULED`, `APPLIED`, `ABANDONED`), a `reason` or `outcome` code, `created_at`, and `updated_at`.
- The ledger enforces the invariant that for any `(tenant_id, region_id, tick_id, effect_key)` exactly one terminal state exists: either `status = APPLIED` (effect successfully committed to domain state) or `status = ABANDONED` (effect intentionally skipped or unrecoverable), and rows may not remain stuck in `SCHEDULED` beyond a configurable grace window.
- On replay (for example after failover or replaying `pending` entries), the executor loads ledger rows with `status = SCHEDULED` for that tick and:
  - Re-queues/re-runs effects whose domain targets do not yet reflect `APPLIED`, then update the row to `APPLIED`.
  - If replay determines the effect is no longer valid (expired session, entity gone, tick de-scheduled, etc.), it updates the row to `ABANDONED` with a precise `reason`.
  - If the effect was already applied (e.g., because of idempotent writes or cached state), it marks the ledger row as `APPLIED` and skips running the Lua script again.
- This ledger makes replay visible:  services expose metrics such as `tick.effects_pending_total`, `tick.effects_applied_total`, `tick.effects_abandoned_total{reason}`, and `tick.effects_replayed_total`. Alerts fire if pending counts remain above thresholds for longer than a tick window or if the abandoned ratio grows unexpectedly.
- Every Lua script that stages effects is required to include the `effect_key` used in the ledger so that matches between Redis and PostgreSQL can be validated. Scripts that cannot be tied back to a ledger row are rejected.

With this contract, operators and automation can detect whether replay occurred, how long effects sat in `SCHEDULED`, and whether any stale `pending` entries were consciously abandoned instead of silently forgotten.

### Region Progress vs Lease Ownership

Region leases are intentionally **short‑lived coordination hints**, not full health indicators. It is possible for an executor to:

- Continue renewing the `tick-executor-lease:{tenantId}:{regionId}` key (Redis and lease logic are healthy), while
- Making little or no **forward progress** because downstream dependencies (PostgreSQL, domain gRPC services) are slow or unreachable.

To distinguish this “lease held but stalled” case from a true lease loss:

- The Game Session Service tracks **per-region progress signals**, such as:
  - The timestamp or tick counter of the **last successful commit** for `{tenantId, regionId}`.
  - A small counter of **consecutive failed ticks** for that region caused by downstream errors or timeouts (not by lock contention alone).
- When a region exceeds simple hardcoded thresholds (for example, several consecutive failures or no successful ticks for many multiples of `tick-duration-ms`), the scheduler treats the region as **stalled** even though it still holds the lease.

Behavior for stalled regions:

- New ticks are **not scheduled** for that `{tenantId, regionId}` until progress recovers.
- New gameplay commands targeting that region are **rejected** with a clear “region unavailable” style error instead of being accepted and stuck behind a non‑advancing executor.
- The executor continues to renew the lease during a short grace period so no other instance takes over and attempts the same failing work against unhealthy dependencies.
- If a large number of regions on the same instance become stalled, the instance’s readiness/liveness can be marked unhealthy so orchestration restarts it once dependencies recover.

Lease expiry and failover remain strictly about **coordination safety** (avoiding split‑brain on Redis state). Stalled region handling is layered on top as a **progress watchdog**: leases tell you who owns a region’s coordination keys, while stalled/healthy tell you whether that owner is actually advancing game state.

---

## Distributed Locking

To prevent concurrent entity updates, ticks acquire **distributed locks** in Redis using:

- `tick:{tenantId}:{regionId}:lock:{entityId}` (see [Redis Key Reference](./system-architecture-redis.md#key-naming-and-shard-discipline))
- `SET NX PX` with expiry for exclusive ownership, via a shared lock helper
- Lua-based atomic checks to avoid race conditions

In practice, the system keeps this simple by exposing a **single primary knob**—the region tick interval—and deriving lock/lease TTLs from that:

- `tick_interval_ms` is the configured target interval between ticks for a region.
- Internally, the Game Session Service computes a soft execution budget (for example `tick_budget_ms = tick_interval_ms * 0.8`) and then derives TTLs using fixed multipliers:
  - `lock_ttl_ms = clamp(tick_budget_ms * 8, 500, 5_000)`
  - `lease_ttl_ms = clamp(tick_budget_ms * 16, 2_000, 15_000)`
- These multipliers are **hard-coded defaults** for hobby/self‑hosted deployments; they are chosen to give generous headroom for GC pauses and hiccups without requiring per‑environment tuning.

This keeps configuration light—typically you only adjust `tick_interval_ms`—while still ensuring locks live long enough for normal work to finish and stale locks are cleared after a bounded window.

Rare, extreme pauses (for example long GC) may still exceed `lock_ttl_ms`. In those cases:

- The original worker may continue executing locally even after its lock expires and is potentially reacquired by another worker.
- Lock-token and pending-key checks (described below) cause any such “late” work to fail safely: the Lua script sees a token or `tickId` mismatch, aborts without applying effects, and surfaces a retry outcome instead.

Operationally:

- **Lock TTL expiry in healthy operation should be rare**. A non-trivial rate of over-TTL ticks in a region is treated as a degradation signal (see the Redis architecture’s degraded/ halted region behavior) and usually indicates a need to adjust tick budgets, GC settings, or shard layout.
- When a tick does run past `lock_ttl_ms`, its work is treated as a failed attempt and rescheduled via the normal retry/backoff mechanism; fairness rules (per-entity FIFO queues and bounded retries) still apply, so these retries are delayed but not starved relative to other commands.

If a required lock is unavailable:

- The action **fails immediately**
- All staged changes are rolled back via Lua script
- The action is **rescheduled for retry** by the Game Session Service using a bounded backoff policy.

Conflict metadata is recorded and reported to the Game Session Service, which **reorders future submissions** intelligently. The scheduler enforces a simple, explicit fairness model:

- Retries are scheduled **no earlier than a future tick**, not within the same tick; the executor never spins waiting for a lock.
- Each rescheduled action carries a **per-command retry counter** and a **next-eligible-tick** value, so retries are delayed using an exponential backoff in ticks (for example, `nextTick = currentTick + min(2^retryCount, MAX_BACKOFF_TICKS)`).
- After a bounded number of failed attempts (for example `MAX_RETRIES`), the command is marked as permanently failed, a player-visible error is emitted, and metrics/logs capture the contention so operators can see hotspots.
- Fairness is guaranteed **per entity**: within a given entity’s queue, commands are processed in FIFO order and retries are appended to the back of that queue. Cross-entity fairness is best-effort and driven by normal tick scheduling plus the backoff rules.

> 🔗 See [Atomicity and Concurrency Control](./system-architecture-redis.md#🔒-atomicity-and-concurrency-control)

### Lock Token Semantics

Each acquired lock stores a **unique token** (for example, a UUID) as its value. The Game Session Service records this token and only releases the lock via a Lua script that verifies the stored value still matches the original token before deleting the key. This prevents one worker from accidentally releasing another worker’s lock if the TTL expires and the lock is reacquired. Before applying any stateful work for a tick, workers:

- Verify that `tick:{tenantId}:{regionId}:pending` either does not exist or, if it exists, corresponds to the `tickId` they are about to process.
- Confirm that the currently held lock’s token still matches the value stored in Redis.
- Perform these validations and any subsequent commit/rollback + lock-release steps within the **same Lua script invocation** that touches the lock and `pending` key; no domain mutation is allowed to rely on lock state checked in a prior, separate script call.

If either check fails (for example, the lock token was lost and reacquired by another worker), the worker aborts processing for that tick and returns a retry outcome so the Game Session Service can reschedule the work. Tick effects are designed to be idempotent so that if a lock expires mid-tick and staged work is replayed, the game state remains consistent.

---

### Multi-Entity Commands and Deadlock Avoidance

Many gameplay commands conceptually touch **multiple entities** (for example trades, area-of-effect spells, or group buffs). FireMUD avoids Redis-level deadlocks and complex multi-lock coordination with the following rules:

- **One entity lock per script invocation (default):**
  - Tick Lua scripts are written to acquire and operate on **at most one** `tick:{tenantId}:{regionId}:lock:{entityId}` per invocation.
  - Multi-entity commands are decomposed into separate, per-entity legs (for example, one tick leg per participant), each executed under a single entity lock and keyed by a shared `tickId` and effect identifiers for idempotency.
  - Coordination between legs (for example ensuring that both sides of a trade succeed or fail together) occurs via PostgreSQL and/or small coordinator records, not by holding multiple Redis entity locks simultaneously.
  - There is an explicit **fan-in cap** per command (for example `MAX_LOCKED_ENTITIES_PER_COMMAND`); features that need to touch more entities must do so over multiple ticks or via chunked follow-up commands.

- **Exceptions require a global lock ordering and all-or-nothing behavior:**
  - If a future command truly cannot be decomposed and must take more than one entity lock inside a single script, it **must** acquire locks in a global, deterministic order (for example, sort all `entityId` values and acquire locks in ascending order) and operate within a single `{tenantId, regionId}` shard; cross-region multi-lock commands are not allowed.
  - If any lock in that ordered set cannot be acquired, the script immediately releases all previously acquired locks, returns a contention result, and the Game Session Service reschedules the command using the standard backoff rules; no partial logical effects are applied for that command.
  - Such scripts are treated as special cases, reviewed carefully, and documented with their lock ordering assumptions and maximum entity counts. The Redis architecture and Lua Script Registry reinforce this by:
    - Declaring, per script, a `max_entity_locks` value in the script descriptor (default `1` for normal tick scripts).
    - Requiring explicit opt-in to an **ordered multi-lock** mode (and associated tests) for any script that needs `max_entity_locks > 1`.
    - Failing CI when a Lua script attempts to use more entity lock keys than declared or bypasses the ordered multi-lock contract.

By treating “one entity lock per script” as the default contract, enforcing a small, explicit fan-in cap, and reserving ordered multi-lock patterns for rare, all-or-nothing cases, the tick system:

- Eliminates classic deadlock patterns where two commands try to acquire the same pair of locks in opposite orders.
- Keeps Lua scripts small and predictable, simplifying reasoning about failure and recovery.
- Leaves complex cross-entity invariants to the database and domain services, which already provide stronger transactional semantics.

---

## Smart Retry and Conflict Resolution

FireMUD includes a robust system for **lock contention**, **timeouts**, and **retries**, coordinated by the Game Session Service and powered by Redis.

### Retry Scheduling

When an action fails due to contention:

- Redis logs the **blocking lock** and conflicting region
- The Game Session Service:
  - Reschedules the action within the blocked region
  - **Prioritizes retries** to minimize player-visible delays
  - Staggers or delays conflicting ticks to avoid churn
  - Prevents retry storms and wasted CPU

The system also provides:

- Backoff windows and retry caps
- Graph-based conflict resolution
- Hotspot surface metrics and adaptive throttling

---

## Tick Regions and Parallel Execution

Ticks are **region-scoped**, not globally synchronized. Each **tick region** (typically a room or room cluster) runs its own independent cycle, enabling:

- **Parallelism** across threads and servers
- **Fault isolation** from slow or overloaded regions
- **Configurable pacing** per region (tick rate or delay)
- **Elastic execution** across worker instances

> 🔄 **Cross-region actions are split into sequential ticks.**
> **Tick&nbsp;A** (on the source shard) performs exit logic and clears local
> state. **Tick&nbsp;B** (on the destination shard) applies entry logic and
> rebinds the session. No lock or Lua script spans shards, and the Game Session
> Service ensures these ticks execute safely without overlap. See
> [Shard Locality and Cross-Region Behavior](./system-architecture-redis.md#🔀-shard-locality-and-cross-region-behavior)
> for a full description of this pattern.
> 🌀 **Global effects are dispatched by fan-out.** Tick regions remain idle
> unless explicitly triggered, so the Game Session Service injects commands into
> each affected shard and forces a tick there. This guarantees the event is
> applied even if a region would not tick on its own. See
> [Global Effects and Region-Wide Coordination](./system-architecture-redis.md#🌀-global-effects-and-region-wide-coordination)
> for details on the underlying Redis pattern.
> 🔄 **Regions still execute a lightweight background tick** (for example every
> second) so queued timers, cooldowns, and delayed events progress even when no
> players are present.
> 🧠 Tick regions are mapped to Redis shards for atomicity and lock discipline.
---

## Region Sizing and Ownership

Region boundaries are a primary tuning knob for scale and performance:

- **Hot regions** (crowded areas or complex encounters) can be split into multiple regions to increase parallelism and reduce per-region tick load.
- **Cold regions** (sparse or low-activity areas) can be merged to reduce overhead and free capacity.

The World Management Service owns region topology:

- Region layout and `{regionId}` assignments are defined from world geometry.
- For most deployments, region changes are applied between game instances or maintenance windows so active sessions are not disrupted.
- Longer term, dynamic partitioning may support:
  - “Drain and split” flows where a region is marked for split, existing ticks are allowed to complete, and entities plus queues are moved under new `{tenantId, regionId}` prefixes before ticks resume.
  - Similar “merge” flows to consolidate lightly used regions.

Region **ownership** (which Game Session instance executes ticks for a region) is flexible:

- A consistent-hashing or scheduler layer maps `{tenantId, regionId}` to Game Session instances.
- The chosen executor acquires the `tick-executor-lease:{tenantId}:{regionId}` key in Redis and becomes the authoritative tick executor for that region.
- To rebalance load:
  - The current executor stops renewing the lease for selected regions and drains in-flight work to a safe boundary (for example, after the current pending tick commits or is recovered).
  - Another instance acquires the lease and continues tick processing from the existing Redis state.

This combination of configurable region size and movable ownership lets FireMUD scale horizontally:

- **More regions** and **more Game Session instances** yield additional parallelism.
- Regions can be re-assigned between instances to balance CPU and memory usage without requiring a global downtime.

---

## Per-Command Execution Phases

Within a region’s tick, each **command** is treated as a small, idempotent workflow executed under that region’s authority. Conceptually, commands proceed through the following phases (not every command uses every phase):

1. **Enqueue**
   - The Game Session Service accepts commands from Telnet/WebSocket clients or automation.
   - It enqueues them into per-entity or per-region queues in Redis (for example `tick:{tenantId}:{regionId}:queue:{entityId}`).

2. **Target Resolution (read-only)**
   - During the relevant tick, the executor computes the target set for the command using the pinned snapshot for that `{tenantId, regionId}`:
     - Single-target actions resolve a specific entity or room.
     - Multi-target actions (AoE, trades, group buffs) derive a bounded list of entity IDs from room occupancy, threat lists, or other region-local state.
   - This phase is read-only from the perspective of durable state: it determines *what* the command intends to affect without yet mutating Redis or PostgreSQL.

3. **Region-Local Mutations**
   - For commands that affect only the origin region:
     - The executor acquires the required entity locks (possibly multiple per command, in deterministic order) under `tick:{tenantId}:{regionId}:lock:{entityId}`.
     - It stages and commits changes via the tick Lua scripts and gRPC calls to domain services, using `tickId` and effect-guard tables for idempotency.
   - For cross-region commands:
     - The origin region applies any purely local effects first (for example, local animations, partial buffs, or immediate messaging).
     - It then enqueues follow-up commands into the target regions’ queues (for example under `remote:{tenantId}:{entityId}`) so remote executors can apply their parts in their own ticks. See [Cross-Region Command Execution and Result Relay](#cross-region-command-execution-and-result-relay).

4. **Completion / Finalization (optional)**
   - Many commands do not require awareness of “all regions finished”; origin and target regions can operate independently with eventual consistency.
   - For rare commands that truly need **end-to-end completion semantics** (for example complex cross-region trades or scripted events), the origin region may act as a simple coordinator:
     - Track success/failure responses from participating regions (for example via Redis keys or a small Postgres table keyed by `tenantId`, `regionId`, `tickId`, and a command identifier).
     - Once all required regions have responded or timeouts elapse, apply a final status to the origin entity (success, partial, failure) and emit any final messages or follow-up commands.
   - This coordination is optional and reserved for high-value flows; most combat and movement commands do not use it and instead rely on the normal cross-region relay mechanism.

Each phase is designed to be **idempotent**:

- Commands carry a `tickId` and effect keys so replays become safe no-ops in domain services.
- Redis staging and commit scripts treat `tick:{tenantId}:{regionId}:pending` as “this tick may need to be (re)applied,” and domain services enforce idempotency at the database layer.
- Retry paths (lock contention, timeouts, missing dependencies) reschedule commands without violating correctness.

---

### Example: Cross-Region Lifesteal Command

To illustrate the phases for a multi-target, cross-region command, consider a **lifesteal spell** where a caster in region A damages a target in region B and heals based on a percentage of the target’s current HP:

1. **Enqueue**
   - The caster issues a `LIFESTEAL <target>` command from a room in `{tenantId, regionA}`.
   - The origin executor enqueues the command under the caster’s queue key in Redis.

2. **Target Resolution (origin region, read-only)**
   - During the next tick for `{tenantId, regionA}`, the executor:
     - Resolves which remote entity (in `{tenantId, regionB}`) is the intended target.
     - Validates that a cross-region action is allowed (line of sight, range, permissions) using the pinned snapshot and metadata.
   - No HP or inventory state is mutated yet; this phase only determines the target and the target region.

3. **Region-Local Mutations (target region)**
   - The origin region enqueues a follow-up “apply lifesteal damage” command into `{tenantId, regionB}` (for example via `remote:{tenantId}:{targetEntityId}`).
   - In the next tick for `{tenantId, regionB}`, the target region’s executor:
     - Computes the damage amount as a percentage of the target’s authoritative current HP.
     - Acquires the target’s lock (`tick:{tenantId}:{regionB}:lock:{targetEntityId}`) and applies damage via Entity Management using the normal tick idempotency rules.
     - Emits a result event back to `{tenantId, regionA}` containing `casterEntityId` and the actual `damageApplied`.

4. **Region-Local Mutations (origin region heal)**
   - When the origin region receives the lifesteal result, it:
     - Enqueues a local “apply lifesteal heal” command for `{tenantId, regionA}`.
   - In a subsequent tick, the origin executor:
     - Acquires the caster’s lock.
     - Applies a heal up to `damageApplied` (subject to its own HP rules) using Entity Management and tick idempotency.

5. **Completion / Finalization (optional)**
   - The origin region may:
     - Immediately show “You cast Lifesteal…” when the initial command is accepted.
     - Show damage and heal messages as the remote and local legs complete.
   - If a stricter “all-or-nothing” semantics is required for a specific spell, the origin region can track whether both the damage and heal legs have reported success and then apply a final status (for example, marking the spell as fully resolved or partially failed), but most combat flows do not require this extra coordination.

Throughout this sequence:

- Each leg is idempotent and keyed by `tickId` / effect keys in the domain services.
- Region executors never hold cross-region locks; they only coordinate via queued commands and result events.
- Retries (for example due to lock contention or transient failures) are handled by the existing retry queues and idempotent handlers in each region.

---

## Tick Execution Flow

Each tick proceeds as follows:

1. **Collect Actions**
   From the command queues of active entities in the tick region

2. **Resolve Fairly**
   Sort by timestamp, stat priority, or custom policy; only one action per entity is executed per tick

3. **Apply Effects**
   Mutate entity state (e.g., HP, inventory, buffs, position)

4. **Trigger Events**
   Run regeneration, room scripts, NPC behaviors, AI-driven commands — all use the same command queue model

> Note: Game Logic Service resolves each action statelessly and does not participate in commit or rollback phases — those are fully managed by the Game Session Service via Redis.

The **Game Session Service** manages orchestration, while gameplay rules are resolved via the **Game Logic Service**, and **final commit flow is also handled by Game Session Service**.

---

## Tick Staging and Commit Flow

State changes are first **staged in Redis** under keys like `tick:{tenantId}:{regionId}:pending`:

- Only committed if **all actions succeed**
- Timeout or failed actions are **excluded** and **rescheduled with priority**
- Commit and rollback are coordinated by Game Session Service using Lua scripts in Redis

Each `tick:{tenantId}:{regionId}:pending` entry represents a **single tick** for that region and carries a monotonically increasing `tickId` plus the staged effects for that tick. The execution model follows the three-phase pattern described in the Redis architecture:

1. **Stage** – Lua scripts under the current region lease and entity locks write the intended effects into `pending` (using `tickId` and effect keys) without calling external services.
2. **Apply** – Game Session issues gRPC calls to domain services based on the staged payload; handlers apply changes under local transactions and idempotency rules keyed by `(tenantId, regionId, tickId, effectKey)`.
3. **Commit / Cleanup** – A final Lua script reconciles Redis state with the outcomes of the domain calls: it validates the lease and lock tokens, removes `pending` and releases locks on success, or leaves/updates `pending` for retry or operator-driven recovery on failure.

If a crash or lock timeout leaves `pending` present, the next executor for that region treats the entry as “this tick may need to be (re)applied”: it re-runs the Apply and Commit/Cleanup phases. Domain updates are written to be idempotent so repeating the same `tickId` does not corrupt state, and successful completion both applies any remaining effects (if needed) and deletes the `tick:{tenantId}:{regionId}:pending` key.

The **TickScheduler** in the Game Session Service enforces this **single in-flight tick per region** rule:

- A region is considered **busy** while `tick:{tenantId}:{regionId}:pending` exists for its current `tickId`.
- The scheduler does not start a new tick for that `{tenantId, regionId}` until the pending entry has been removed as part of a successful commit or explicitly handled during crash recovery.
- Any additional work enqueued for the same region while a tick is in-flight is modeled as a retry or as follow-up work for a later `tickId`, not as a second concurrent tick.

This ensures:

- Atomic per-tick updates
- Partial failure recovery
- Conflict-free shared state across retries

If FireMUD later introduces limited intra-region parallelism (for example, sharding a single region into multiple independent buckets of entities), this model will evolve to use **per-bucket pending keys** (for example, `tick:{tenantId}:{regionId}:{bucketId}:pending`) and matching idempotency/locking rules. Until such a change is explicitly designed, the invariant is **one `pending` entry and one in-flight tick per `{tenantId, regionId}`.**

---

## Timeout and Fairness Policy

Each tick enforces a **soft execution budget** (for example `tick_budget_ms = tick_interval_ms * 0.8`):

- Slow actions are **deferred** to retry in exclusive follow-up ticks
- These retries are **not executed in parallel**
- **Oldest actions win** in conflict scenarios
- Newer submissions are backlogged until resolved
- Conflict metadata enables **hotspot detection** and livelock prevention
- Metrics `tick_conflict_hotspot_detected_total` and `tick_retry_queue_depth`
  help operators monitor these hotspots across regions.
- Lua staging scripts limit how many commands or events move from queue to
  pending lists per tick. The defaults (`game.tick-max-commands` and
  `automation.tick-max-events`) spread heavy workloads across ticks so one
  player or script cannot monopolize the loop.

The Game Session Service continuously **compares observed tick runtime to the configured lock TTL**:

- Per `{tenantId, regionId}`, it tracks histograms of `tick.execution_time_ms` and derives ratios such as:
  - `tick.execution_time_ms_p95 / lock_ttl_ms`
  - `tick.execution_time_ms_p99 / lock_ttl_ms`
- These ratios drive a simple, environment-agnostic health model:
  - **Healthy:** `p99 < 0.5 × lock_ttl_ms` – tick work comfortably fits within the lock budget.
  - **Degraded:** `0.5× ≤ p99 < 0.75× lock_ttl_ms` – region is flagged as degraded; dashboards and logs recommend either increasing `game.tick-interval-ms` or simplifying work for that region.
  - **Unsafe:** `p99 ≥ 0.75 × lock_ttl_ms` over a sustained window – the scheduler treats this as a **configuration/architecture error** for that region:
    - New ticks may be slowed or temporarily halted for the affected `{tenantId, regionId}` until the operator adjusts configuration (for example, larger tick interval) or reduces per-tick work.
    - Logs include explicit guidance that normal tick work must complete well within `lock_ttl_ms` and that repeated over-budget ticks will cause retries and degraded throughput.

These checks complement the static TTL guardrails described in the Redis design (for example, `lock_ttl_ms >= 1.5× tick_interval_ms` and `lease_ttl_ms >= 2× tick_interval_ms`). Together they ensure that:

- Misconfigured environments are caught at startup, and
- Environments that drift over time (for example, as more cross-service work is added to a tick) surface as **runtime warnings or errors** rather than silently operating with constant lock expiries and retries.

Commands flagged with `requiresSoloTick` run in an isolated tick so expensive
operations do not stall other players. See the
[Game Session Service design](./microservices/game-session-service/README.md#tick-execution-model)
for how these solo ticks are orchestrated. For Redis latency, replication, or
memory issues that affect tick execution, the Game Session Service follows the
graceful degradation and halt policy defined in
[Redis Architecture – Graceful Degradation & Redis Outage Policy](./system-architecture-redis.md#graceful-degradation--redis-outage-policy)
rather than introducing tick-specific fallbacks.

Runtime procedural generation commands set `requiresSoloTick: true`. The Game Session Service
detects this flag and schedules the command alone in its own tick, allowing up to
500&nbsp;ms for execution without competing player actions.

---

## Isolation and Replay Safety

To prevent cross-tick contamination and support deterministic replay:

- Actions may **only see staged state from the current tick**
- Changes from other tick regions or future ticks are **invisible**
- Changes staged earlier in the same tick **are composable**
- Missing dependencies cause the action to **fail and retry**

This guarantees **clean, deterministic ticks** and safe replays after crash or restart.

---

## Timers and Time Scaling

Time-based effects (e.g., cooldowns, regeneration) are managed with **real-time timers in milliseconds**, stored per region in a sorted set:

- `timer:{tenantId}:{regionId}` – a ZSET where the score is the expiration timestamp (in ms) and each member encodes the target entity/effect (for example, `entityId:effectId` or a serialized descriptor).

Timer scores are computed using a **single, consistent time source** on the application side:

- The Game Session Service uses wall-clock time from NTP-synchronized application nodes when scheduling and evaluating timers.
- Redis server time is not used for timer comparisons so clock skew is limited to the skew between application nodes, which is kept small via standard time synchronization.

Each tick processes timers for its region by:

- Using a bounded `ZRANGEBYSCORE`/`ZPOPMIN` up to the current time
- Limiting the number of timers handled per tick via configuration (for example, `game.tick-max-timers`)

If a tick is delayed, multiple timers may fire at once; the processing loop remains bounded by the configured per-tick limit to avoid O(N) scans even when a large number of timers are scheduled.

Timer scheduling, rescheduling, and cancellation are coordinated by the same Lua scripts and region-scoped locks that drive tick processing (see [Redis Architecture – Atomicity and Concurrency Control](./system-architecture-redis.md#atomicity-and-concurrency-control)). Domain services do not modify `timer:{tenantId}:{regionId}` directly with ad-hoc Redis commands; all writes to timer keys occur under the region lock so timers and command queues cannot race or diverge.

### Dynamic Time Scaling

Durations can be modified on the fly:

- `scaled = base * multiplier`
- Used for spell effects, world modifiers (e.g., slow motion), or status changes
- Time scaling affects **durations**, not tick rate

### Tick Event Stream

The Game Session Service publishes a **tick event stream** so other services (schedulers, monitoring, leaders) can observe progression without altering the tick loop. Each event is keyed by `{tenantId}:{regionId}` and includes:

- `tickId` (monotonic per region, used by schedulers to count intervals)
- `shardId`/`regionId` metadata
- `timestamp` when the tick began
- `activeVersionId` pinned for that tick

Leaders lease Redis keys (`tick-events-lease:{tenantId}:{regionId}`) before consuming the stream to avoid duplicate processing. The stream can be delivered via Redis Streams or pub/sub; the implementation ensures catch-up by storing the last processed offset in Redis so a recovering scheduler can resume from the right `tickId`. This event stream powers the Automation & Scripting scheduler’s “every N ticks” logic, the reconnection doc’s timer replay hints, and any other out-of-band reporting you need.

> Runtime feature flags controlling global pace or status effects are applied by the Game Session Service before tick execution.
> For design-time definitions see [Game Design Service Feature Flags](./microservices/game-design-service/feature-flags.md);
> runtime editing is described in [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).

---

## Crash Recovery and Replay

If a tick crashes mid-flight (e.g., Game Session Service restart), Redis preserves:

- Tick locks (`tick:{tenantId}:{regionId}:lock:*`)
- Staged effects (`tick:{tenantId}:{regionId}:pending`)
- Timers (`timer:*`)
- Retry and conflict state

Recovery is coordinated by Game Session Service and backed by:

- **Lua-based atomic updates**
- **AOF (Append-Only File)** persistence for best-effort durability of volatile tick/session state
- **Asynchronous replication**, with correctness guaranteed by treating Redis as a volatile coordination layer and relying on idempotent tick replays plus PostgreSQL as the source of truth

This supports **at-least-once, idempotent, replayable** ticks — ticks may be safely replayed after crash or failover without changing observable game state, even though individual effects may execute more than once under rare failure windows.

---

## Domain Idempotency Rules (TickId in PostgreSQL)

Domain services treat `tickId` as the canonical idempotency token for every tick-side effect. Replays of the same `tick:{tenantId}:{regionId}:pending` entry must never apply a logically new effect to PostgreSQL.

### Scope: Which Operations Must Be Idempotent?

The idempotency rules in this section apply to any operation that is:

- Invoked as part of tick execution (driven by commands dequeued from `tick:{tenantId}:{regionId}:queue:{entityId}` or by timers/retries tied to the same `tickId` stream), **and**
- Persists or triggers side effects outside Redis, including:
  - PostgreSQL mutations (rows in Entity Management, World Management, Social, etc.).
  - Durable queues or outboxes consumed by other services.
  - Calls to other services that in turn modify PostgreSQL or external systems.

Explicitly out of scope (no special tick idempotency guard required):

- Pure reads that do not mutate state.
- Best-effort observability (metrics, logs, traces) that can be recorded multiple times without affecting gameplay semantics.

Operations that **cannot** be made idempotent or compensatable at the domain layer — for example payments, emails, or webhooks into third-party systems — **must not** be executed directly inside tick-driven handlers. Those flows must use the saga/outbox patterns in [Transaction Strategies](./system-architecture-transactions.md) so they can tolerate retries and partial failures independently of tick replay.

Every tick-driven effect MUST use one of the following strategies:

- **Per-aggregate last-tick state**
  - Each aggregate root that is updated at most once per tick (for example, a character’s core stats row or a room’s dynamic state row) maintains a shadow tick-state record such as `entity_tick_state` keyed by the aggregate identifier (for example `entity_id`).
  - The shadow table stores at minimum:
    - `tenant_id` / `region_id` (or a foreign key that implies them)
    - `last_tick_id` (monotonic per `{tenantId, regionId}`)
  - When applying a tick effect to the aggregate:
    - The service reads the current tick state.
    - If `last_tick_id >= currentTickId`, the update is treated as a **replay or out-of-order attempt** and becomes a no-op (or, in strict modes, a validation-only check).
    - If `last_tick_id < currentTickId`, the service applies the change and updates `last_tick_id = currentTickId` in the same transaction.

- **Operation-level effect guard**
  - For operations that may touch multiple aggregates or may legitimately apply multiple distinct effects to the same aggregate in a single tick (for example, trades, AoE damage, or multi-target buffs), services use a small guard table such as `tick_effect_guard` keyed by:
    - `tenant_id`
    - `region_id`
    - `tick_id`
    - `effect_key` – a deterministic identifier describing the logical effect (for example `entity:{entityId}:award:achievement:{achievementId}` or `room:{roomId}:drop:item:{itemId}`).
  - Inside the same database transaction as the domain update:
    - The service attempts to insert `(tenant_id, region_id, tick_id, effect_key)` into the guard table.
    - If the insert **succeeds**, the effect is considered **new** for this tick and the service applies all associated state changes.
    - If the insert **conflicts on primary key**, the effect has already been applied for this `(tenantId, regionId, tickId, effectKey)` and the handler treats the call as a **replay**:
      - In the simple case, the handler returns success without reapplying changes.
      - In stricter flows, the handler may verify that current state is consistent with the previously applied effect before returning.

### Examples

- **Per-aggregate last-tick state – single-entity damage**
  - A `ApplyDamage` handler in Entity Management receives `(tenantId, regionId, tickId, entityId, damageAmount)`.
  - It reads `entity_tick_state` for `entityId` and compares `last_tick_id` to `tickId`.
  - If `last_tick_id >= tickId`, the handler treats the request as a replay for this entity and returns without changing HP.
  - If `last_tick_id < tickId`, the handler subtracts `damageAmount` from current HP and updates `last_tick_id = tickId` in `entity_tick_state` within the same transaction.

- **Operation-level effect guard – trade between two entities**
  - A `TradeItem` handler in Entity Management is called with `(tenantId, regionId, tickId, fromEntityId, toEntityId, itemId)`.
  - It computes `effectKey = "trade:" + fromEntityId + ":" + toEntityId + ":" + itemId`.
  - Inside a single transaction it:
    - Attempts to insert `(tenantId, regionId, tickId, effectKey)` into `tick_effect_guard`.
    - If the insert conflicts, it treats the call as a replay and returns success without modifying inventories.
    - If the insert succeeds, it debits the item from `fromEntityId`’s inventory, credits it to `toEntityId`, and commits both the inventory changes and the guard-row insert together.

### Replay Handling and Service Responsibilities

- Tick replays are always driven by the **Game Session Service** based on the presence of `tick:{tenantId}:{regionId}:pending` in Redis and the `tickId` it carries.
- Domain services (Entity Management, World Management, Game Logic hosts, Social Groups, etc.) are responsible for:
  - Persisting tick idempotency state in their own PostgreSQL schema via shadow tables and guard tables.
  - Ensuring that **every tick-driven write path** uses either the per-aggregate `last_tick_id` pattern or the operation-level guard pattern.
  - Treating Redis locks and leases as **opaque tick-engine concerns**: domain handlers do not read `tick:{tenantId}:{regionId}:lock:{entityId}` or `tick-executor-lease:{tenantId}:{regionId}` to make application-level decisions. All coordination based on locks/leases happens inside the Game Session Service via registered Lua scripts; domains see only `(tenantId, regionId, tickId, effectKey)` and their own idempotency state.
- At least one concrete example per service should document:
  - The name of its tick-state table(s).
  - The fields used (`last_tick_id`, `tenant_id`, `region_id`, `effect_key`).
  - How handlers behave when they detect a replay (no-op vs verify-then-no-op).

### Testing Tick Idempotency and Redis Replays

The crash-recovery story depends on domain services implementing these patterns correctly. To catch mistakes early:

- Each service with tick-driven handlers must include **integration tests** that simulate Redis-style replays:
  - Invoke the same handler twice (or more) with identical `(tenantId, regionId, tickId, effectKey, payload)` and assert that:
    - The first call mutates state as expected.
    - Subsequent calls are treated as replays and do not apply additional logical effects (HP changes, inventory moves, etc.).
  - Exercise both idempotency strategies:
    - Per-aggregate `last_tick_id` tables (for single-entity updates).
    - Operation-level `tick_effect_guard` tables (for multi-entity effects).
- A shared test harness (in the common library or individual services) should make it easy to:
  - Construct a synthetic `tick:{tenantId}:{regionId}:pending` payload.
  - Drive the same sequence of domain calls multiple times, mimicking a replay of the same pending tick after a crash.
  - Verify that the final PostgreSQL state is identical regardless of how many times the tick is “reapplied.”
- CI pipelines must run these replay tests; changes to tick handlers that break idempotency should fail tests before reaching production.

### Design Checklist for New Tick-Driven Commands

When introducing a new command type that will run under tick control, implementers must answer the following questions in design docs and code review:

- **Is this command tick-driven?**
  - Does it run because an entry is dequeued from `tick:{tenantId}:{regionId}:queue:{entityId}` or a tick-timer/retry fired?
  - If not, it may follow different idempotency rules and does not belong in this section.
- **What is the idempotency key?**
  - For single-aggregate updates: which `last_tick_id` field and table enforce “at most one update per tick” for that aggregate?
  - For multi-aggregate or multi-effect operations: what is the `effect_key` used in `tick_effect_guard`, and how is it derived deterministically from the command payload?
- **Where is the guard persisted?**
  - Which schema/table holds `last_tick_id` or `tick_effect_guard` entries?
  - Is there a primary-key or unique index that enforces the idempotency key at the database level?
- **What happens on replay?**
  - What does the handler do when it detects that the guard already exists or `last_tick_id >= currentTickId`?
  - Is the “replay” outcome clearly documented and tested (no new logical effects, optional consistency verification)?
- **Are there any non-idempotent external effects?**
  - If the handler sends email, charges a payment method, or calls an external API with irreversible effects, how is that separated from the tick-driven part (for example, via an outbox entry processed by a saga)?

PRs that add new tick-driven commands should link to this checklist and demonstrate how each item is satisfied before the feature is considered complete.

---

## Tick Execution and Redis Integration

Redis is essential for **coordinating tick execution** across distributed worker services. It provides:

- Per-entity **command queues**
- Durable **tick staging** via `tick:{tenantId}:{regionId}:pending`
- Distributed **locks** and **retry tracking**
- **Conflict metadata** for retry prioritization
- Accurate **cooldown and timer tracking**

### Canonical Tick Commit Pattern (Lua + gRPC/DB)

To keep idempotency and crash behavior consistent, all tick execution follows a common **three-phase pattern** that clearly separates Redis staging from external side effects:

1. **Stage effects in Redis (Lua)**
   - Under region leadership and entity locks, a Lua script:
     - Validates the current `tick-executor-lease:{tenantId}:{regionId}` token and lock tokens.
     - Computes the intended tick effects for the region (for example, “apply damage X to entity A” and “move entity B to room R”).
     - Writes a **pure description** of those effects into `tick:{tenantId}:{regionId}:pending` along with the `tickId`. This payload contains only enough data for domain services to re-derive their work (entity IDs, effect keys, parameters), not a separate shadow copy of the entire authoritative state.
   - The script does **not** call out to gRPC or mutate PostgreSQL; it only updates Redis atomically.

2. **Apply effects in domain services (gRPC + PostgreSQL)**
   - The Game Session Service reads the staged `pending` entry and, for each effect:
     - Issues gRPC calls to the owning domain services (Entity Management, World Management, etc.).
     - Each domain handler runs inside a local database transaction that:
       - Uses `tickId` and effect keys plus its own tick-state / guard tables to decide whether this effect is **new** or a **replay**.
       - Applies changes only for new effects, then records the updated idempotency state.
     - If a handler reports a retryable failure (for example, lock contention at the DB layer), the Game Session Service records this as a **failed effect** for the tick; the tick may be retried or split according to the retry rules, but already-applied effects remain safe due to idempotency.
   - This phase may succeed for some effects and fail for others; all such outcomes are reflected in process memory and observability, but Redis state remains unchanged until phase 3.

3. **Commit or roll back in Redis (Lua)**
   - Once all domain calls for a given tick have either succeeded or failed definitively, the Game Session Service invokes a second Lua script that:
     - Re-validates the `tick-executor-lease` token and lock tokens for the region.
     - Checks the current `tick:{tenantId}:{regionId}:pending` entry and `tickId`.
     - Decides, based on the collected outcomes:
       - **Commit path** – if all required effects succeeded:
         - Clears the `pending` entry for that `tickId`.
         - Releases any surviving tick locks for the region.
       - **Rollback / recovery path** – if some effects failed:
         - Leaves or updates `pending` to represent the remaining work to be retried, or marks the tick as failed and allows runbook-driven recovery as described below.
   - No new domain-side mutations occur in this phase; the script reconciles only Redis coordination state with the outcomes that were already durably recorded in PostgreSQL.

This pattern, combined with domain-level idempotency rules described above, yields the following guarantees even when phases fail independently:

- If phase 1 (staging) completes but phase 2 (domain calls) only partially succeeds, **replays of the same `pending` entry** will not double-apply effects, because domain services treat repeated `(tenantId, regionId, tickId, effectKey)` requests as no-ops.
- If phase 2 succeeds fully but phase 3 (commit/cleanup) fails or is interrupted, the next executor that sees the same `pending` entry and `tickId`:
  - Re-runs the same domain calls, which are treated as replays and become no-ops.
  - Eventually completes the commit/cleanup script, clearing `pending` and releasing locks once lease/lock tokens validate.
- If Redis loses the `pending` entry entirely (for example due to tail loss or TTL misconfiguration), domain state remains consistent because all effects were applied under idempotent rules; missing ticks are detected and handled via metrics and recovery runbooks, not by speculative domain reapplication.

### Failure Scenarios and Invariants

The table below summarizes how common failure patterns interact with Redis and PostgreSQL, and which invariants the system preserves:

| Scenario | Redis coordination state | Domain (PostgreSQL) state | Guaranteed invariants | Typical operator / system action |
| --- | --- | --- | --- | --- |
| Primary crash, AOF fully up-to-date | `pending`, locks, timers, retries preserved for recent ticks | All committed effects durably stored | No double-apply; at-most-one executor per region after lease re-acquisition | New executor replays any surviving `pending` entries; ticks complete or are retried automatically. |
| Crash during AOF window (tail loss) | Some recent `pending`/lock/queue keys for the last ticks may be missing | Effects applied before the crash remain in Postgres; very recent, in-flight effects may or may not have been applied | No double-apply; some ticks may be lost or need manual reconstruction | Metrics show gaps/stuck regions; recovery subsystem may mark missing ticks as FAILED/SKIPPED and clear any inconsistent Redis state. |
| GC pause > `lock_ttl_ms` but < `lease_ttl_ms` | Locks may expire and be reacquired; `pending` remains; lease still held by original executor | Any domain effects applied before the pause remain consistent; replays are treated as no-ops | No double-apply; lease ownership unchanged; at-most-one executor per region | Late work that fails token checks is retried; region may be marked degraded if over-TTL behavior persists. |
| GC pause > `lease_ttl_ms` (lease lost) | Lease may move to a new executor; `pending` and queues preserved subject to AOF window | Effects applied by the old executor before losing the lease remain consistent; new executor replays with idempotent handlers | No double-apply; at-most-one active executor per region (enforced by lease tokens) | Old executor’s work is discarded when it resumes; new executor drives recovery; region may be degraded until stable. |
| Redis coordination cluster outage | Coordination keys temporarily unavailable; may lose a tail window of recent state depending on failure + AOF | Postgres remains authoritative; effects committed before outage remain intact | No double-apply; some ticks may be lost or skipped, but already-applied effects are not rolled back | Game Session halts ticks/commands for affected regions; once Redis recovers, recovery subsystem and operators decide whether to skip, retry, or repair missing ticks. |

### Stuck Pending Entries and Recovery

In rare cases where domain code is faulty, a `tick:{tenantId}:{regionId}:pending` entry may remain present even though repeated replays cannot complete successfully. A small tick recovery subsystem handles these situations:

- A background watcher scans metrics or a compact Redis/PostgreSQL index of `pending` entries to identify **stuck ticks** (for example, `pending` keys that have existed for multiple tick intervals with exhausted retries).
- Candidate stuck ticks are enqueued into a `tick_recovery` queue or table with metadata such as `{tenantId, regionId, tickId, firstSeenAt, lastRetryAt}`.
- An automated recovery worker:
  - Marks clearly terminal ticks as `FAILED` or `SKIPPED` in PostgreSQL using the same effect-guard and idempotency rules as normal handlers.
  - Clears `tick:{tenantId}:{regionId}:pending` and associated retry metadata in Redis via a dedicated, idempotent helper path.
  - Emits detailed logs and metrics for audit.
- Operator tooling allows manual override for complex cases (for example, data corruption), and environments can choose between:
  - **Recommendation mode** – operators approve or override proposed recoveries.
  - **Auto-recovery mode** – for clearly defined, low-risk patterns.

Retry and timer queues are protected against unbounded growth by design:

- Retry queues (`retry:{tenantId}:{regionId}`) use ZSETs keyed by `retry:{tenantId}:{regionId}` with scores encoding next-eligible execution time; scripts process at most `N` entries per invocation and enforce a maximum retry budget per action.
- Timer keys (`timer:{tenantId}:{regionId}`) use ZSETs keyed by `timer:{tenantId}:{regionId}` with scores encoding due time; scripts pop at most `N` timers per call and delete processed members.
- Defensive limits (for example, maximum timers per region) trigger alerts or throttling if exceeded so that bugs cannot create unbounded timer growth.

Entity Management provides the reference example for per-aggregate tick state; see [Entity Management Service – Tick Idempotency](./microservices/entity-management-service/README.md#tick-idempotency) for details.

---

## Cross-Region Command Execution and Result Relay

Some commands originate in one region but affect targets in another — for
example, remote attacks, cross-world spells, or administrative inventory moves.
The process is **asynchronous and multi-phased**:

1. A tick-local command executes in the **origin region**.
2. A follow-up action — including the `sourceEntityId` — is enqueued in the
   **target region's** command queue (often under a `remote:{tenantId}:{entityId}` key; see
   [Redis Key Naming](./system-architecture-redis.md#key-naming-and-shard-discipline)).
3. The target region processes the action during its next tick and determines
   the outcome locally.
4. The Game Session Service uses the `sourceEntityId` to route the result back
   to the origin region or directly to the player's active session.

Players experience a smooth flow:

```text
🕒 You cast Fireball...
🔥 Your Fireball hits Player B for 12 damage!
```

No region waits synchronously for another shard, preserving responsiveness and
deterministic replay.

### Tick Chaining and Reentrant Effect Control

Each action carries a `tickChainDepth`. When follow-up effects (stuns,
explosions, scripted traps) spawn additional actions, the depth is incremented.
If `MAX_TICK_CHAIN_DEPTH` (default **8**) is exceeded, the new action is
aborted and a warning is logged. Prior steps remain committed, and the player
may be notified that the chain was halted.

---

## Service Responsibilities

| Service | Role |
| --- | --- |
| **Game Session Service** | Orchestrates tick regions, lock acquisition, retries, and commit flow |
| **Game Logic Service** | Resolves each queued action deterministically, including movement/travel cost computation from World geometry and region metadata |
| **Automation & Scripting** | Injects AI or scripted commands into queues |
| **World Management** | Defines tick region layout and room segmentation |
| **Redis** | Stores locks, timers, staged changes, retry metadata; executes Lua |

> Game Session Service manages all tick lifecycle logic and delegates atomic operations to Redis via Lua.

---

## Model Benefits

- ✅ Parallel, fault-isolated tick execution with room-level isolation
- ✅ Lock-on-demand using Redis avoids fixed thread ownership
- ✅ Atomic staging and safe rollback via Lua
- ✅ Deterministic recovery using AOF and idempotent tick replays
- ✅ Conflict metadata avoids livelocks and enables adaptive pacing
- ✅ Flexible time scaling without affecting system cadence
- ✅ No in-service volatile state — everything recoverable via Redis

> FireMUD treats time as **localized pulses**, not a global clock. Each tick is a self-contained transaction — safely composing gameplay logic in a world that never stops evolving.

---

## Related Documentation

- [Redis Architecture](./system-architecture-redis.md)
- [Transaction Strategies](./system-architecture-transactions.md)
- [System Architecture Overview](./system-architecture-overview.md)
