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

## Distributed Locking

To prevent concurrent entity updates, ticks acquire **distributed locks** in Redis using:

- `tick:{tenantId}:{regionId}:lock:{entityId}` (see [Redis Key Reference](./system-architecture-redis.md#🗂️-key-naming-and-shard-discipline))
- `SET NX PX` with expiry for exclusive ownership
- Lua-based atomic checks to avoid race conditions

Lock TTLs are derived from the **soft tick execution budget** using the formula described in the Redis architecture (`lock_ttl_ms = clamp(tick_budget_ms * 3, MIN_LOCK_TTL_MS, MAX_LOCK_TTL_MS)`). This gives headroom for brief pauses (GC, CPU spikes) without letting the lock expire while work is still in progress, while still bounding how long a stale lock can block progress. Ticks that approach this bound are treated as misbehaving and deferred or retried rather than allowed to run indefinitely under a single lock.

If a required lock is unavailable:

- The action **fails immediately**
- All staged changes are rolled back via Lua script
- The action is **rescheduled for retry** by the Game Session Service

Conflict metadata is recorded and reported to the Game Session Service, which **reorders future submissions** intelligently.

> 🔗 See [Atomicity and Concurrency Control](./system-architecture-redis.md#🔒-atomicity-and-concurrency-control)

### Lock Token Semantics

Each acquired lock stores a **unique token** (for example, a UUID) as its value. The Game Session Service records this token and only releases the lock via a Lua script that verifies the stored value still matches the original token before deleting the key. This prevents one worker from accidentally releasing another worker’s lock if the TTL expires and the lock is reacquired. Before applying any stateful work for a tick, workers:

- Verify that `tick:{tenantId}:{regionId}:pending` either does not exist or, if it exists, corresponds to the `tickId` they are about to process.
- Confirm that the currently held lock’s token still matches the value stored in Redis.

If either check fails (for example, the lock token was lost and reacquired by another worker), the worker aborts processing for that tick and returns a retry outcome so the Game Session Service can reschedule the work. Tick effects are designed to be idempotent so that if a lock expires mid-tick and staged work is replayed, the game state remains consistent.

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

State changes are first **staged in Redis** under keys like `tick:pending:{tenantId}:{regionId}`:

- Only committed if **all actions succeed**
- Timeout or failed actions are **excluded** and **rescheduled with priority**
- Commit and rollback are coordinated by Game Session Service using Lua scripts in Redis

Each `tick:{tenantId}:{regionId}:pending` entry represents a **single tick** for that region and carries a monotonically increasing `tickId` plus the staged effects for that tick. If a crash or lock timeout leaves this key present, the next tick cycle treats it as “safe to reapply” and replays the staged effects. Domain updates are written to be idempotent so repeating the same `tickId` does not corrupt state, and successful completion both applies the effects and deletes the `tick:{tenantId}:{regionId}:pending` key.

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

Each tick enforces a **soft execution budget** (e.g., 100ms):

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

Commands flagged with `requiresSoloTick` run in an isolated tick so expensive
operations do not stall other players. See the
[Game Session Service design](./microservices/game-session-service/README.md#tick-execution-model)
for how these solo ticks are orchestrated.

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

### Dynamic Time Scaling

Durations can be modified on the fly:

- `scaled = base * multiplier`

### Tick Event Stream

The Game Session Service publishes a **tick event stream** so other services (schedulers, monitoring, leaders) can observe progression without altering the tick loop. Each event is keyed by `{tenantId}:{regionId}` and includes:

- `tickId` (monotonic per region, used by schedulers to count intervals)
- `shardId`/`regionId` metadata
- `timestamp` when the tick began
- `activeVersionId` pinned for that tick

Leaders lease Redis keys (`tick-events-lease:{tenantId}:{regionId}`) before consuming the stream to avoid duplicate processing. The stream can be delivered via Redis Streams or pub/sub; the implementation ensures catch-up by storing the last processed offset in Redis so a recovering scheduler can resume from the right `tickId`. This event stream powers the Automation & Scripting scheduler’s “every N ticks” logic, the reconnection doc’s timer replay hints, and any other out-of-band reporting you need.
- Used for spell effects, world modifiers (e.g., slow motion), or status changes
- Time scaling affects **durations**, not tick rate

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
- **AOF (Append-Only File)** persistence
- `WAIT 1 100` for durable replication

This supports **at-least-once, idempotent, replayable** ticks — ticks may be safely replayed after crash or failover without changing observable game state, even though individual effects may execute more than once under rare failure windows.

---

## Domain Idempotency Rules (TickId in PostgreSQL)

Domain services treat `tickId` as the canonical idempotency token for every tick-side effect. Replays of the same `tick:{tenantId}:{regionId}:pending` entry must never apply a logically new effect to PostgreSQL.

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
- At least one concrete example per service should document:
  - The name of its tick-state table(s).
  - The fields used (`last_tick_id`, `tenant_id`, `region_id`, `effect_key`).
  - How handlers behave when they detect a replay (no-op vs verify-then-no-op).

Entity Management provides the reference example for per-aggregate tick state; see [Entity Management Service – Tick Idempotency](./microservices/entity-management-service/README.md#tick-idempotency) for details.

---

## Cross-Region Command Execution and Result Relay

Some commands originate in one region but affect targets in another — for
example, remote attacks, cross-world spells, or administrative inventory moves.
The process is **asynchronous and multi-phased**:

1. A tick-local command executes in the **origin region**.
2. A follow-up action — including the `sourceEntityId` — is enqueued in the
   **target region's** command queue (often under a `remote:{tenantId}:{entityId}` key; see
   [Redis Key Naming](./system-architecture-redis.md#🗂️-key-naming-and-shard-discipline)).
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
- ✅ Deterministic recovery using AOF + `WAIT`
- ✅ Conflict metadata avoids livelocks and enables adaptive pacing
- ✅ Flexible time scaling without affecting system cadence
- ✅ No in-service volatile state — everything recoverable via Redis

> FireMUD treats time as **localized pulses**, not a global clock. Each tick is a self-contained transaction — safely composing gameplay logic in a world that never stops evolving.

---

## Related Documentation

- [Redis Architecture](./system-architecture-redis.md)
- [Transaction Strategies](./system-architecture-transactions.md)
- [System Architecture Overview](./system-architecture-overview.md)
