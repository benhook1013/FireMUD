# ⏱️ FireMUD System Architecture: Tick System and Runtime Design

📄 This document expands on the [Game Loop / Tick Model](./system-architecture-overview.md#⏱️-game-loop--tick-model) section of the FireMUD System Architecture Overview. It defines how ticks execute, resolve concurrency, handle crashes, and preserve deterministic, fair game logic under load. Cross-service operations triggered by ticks rely on Redis scripts and gRPC; sagas are unnecessary for these gameplay actions as explained in [Transaction Strategies](./system-architecture-transactions.md).

> 🔗 For Redis keys, Lua-based atomicity, and operational guarantees, see the [Redis Architecture](./system-architecture-redis.md).
---

## 🧠 Hybrid Tick Model

FireMUD uses a **Hybrid Tick Model** to balance real-time responsiveness with deterministic action resolution:

- **Actions are queued per entity** (players, NPCs, and scripted automation) in individual **command queues** (TODO: Not yet implemented)
- At regular **tick intervals** (e.g., 1s):
  - A `TickScheduler` in the Game Session Service triggers `processTick` for each active tick region
  - One action (if any) is pulled from each entity's command queue (TODO: Not yet implemented)
  - Actions are resolved in FIFO order; stat-based prioritization is planned (TODO: Not yet implemented)
  - Only **one action per entity per tick** is executed for fairness (TODO: Not yet implemented)
  - State changes are applied in a single coordinated pass (TODO: Not yet implemented)

This model ensures:

- Responsive feel for players
- Deterministic conflict resolution (e.g., pickups, interrupts)
- Equal treatment of AI and player actions
- Scheduled updates for effects like cooldowns, patrols, regeneration (TODO: Not yet implemented)

> 🔗 Overview of this model appears in [System Architecture Overview](./system-architecture-overview.md#⏱️-game-loop--tick-model)

---

## 🔐 Distributed Locking

To prevent concurrent entity updates, ticks acquire **distributed locks** in Redis using:

- `tick:lock:{tenantId}:{entityId}` (see [Redis Key Reference](./system-architecture-redis.md#🗂️-key-naming-and-shard-discipline))
- `SET NX PX` with expiry for exclusive ownership
- Lua-based atomic checks to avoid race conditions

If a required lock is unavailable:

- The action **fails immediately**
- All staged changes are rolled back via Lua script
- The action is **rescheduled for retry** by the Game Session Service (TODO: Not yet implemented)

Conflict metadata is recorded and reported to the Game Session Service, which **reorders future submissions** intelligently. (TODO: Not yet implemented)

> 🔗 See [Atomicity and Concurrency Control](./system-architecture-redis.md#🔒-atomicity-and-concurrency-control)

---

## 🔁 Smart Retry and Conflict Resolution

FireMUD includes a robust system for **lock contention**, **timeouts**, and **retries**, coordinated by the Game Session Service and powered by Redis.

### 🧠 Retry Scheduling

When an action fails due to contention:

- Redis logs the **blocking lock** and conflicting region (TODO: Not yet implemented)
- The Game Session Service:
  - Reschedules the action within the blocked region (TODO: Not yet implemented)
  - **Prioritizes retries** to minimize player-visible delays (TODO: Not yet implemented)
  - Staggers or delays conflicting ticks to avoid churn (TODO: Not yet implemented)
  - Prevents retry storms and wasted CPU (TODO: Not yet implemented)

Future enhancements may include:

- Backoff windows and retry caps (TODO: Not yet implemented)
- Graph-based conflict resolution (TODO: Not yet implemented)
- Hotspot surface metrics and adaptive throttling (TODO: Not yet implemented)

---

## 🌍 Tick Regions and Parallel Execution

Ticks are **region-scoped**, not globally synchronized. Each **tick region** (typically a room or room cluster) runs its own independent cycle, enabling: (TODO: Not yet implemented)

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
> for a full description of this pattern. (TODO: Not yet implemented)
> 🌀 **Global effects are dispatched by fan-out.** Tick regions remain idle
> unless explicitly triggered, so the Game Session Service injects commands into
> each affected shard and forces a tick there. This guarantees the event is
> applied even if a region would not tick on its own. See
> [Global Effects and Region-Wide Coordination](./system-architecture-redis.md#🌀-global-effects-and-region-wide-coordination)
> for details on the underlying Redis pattern. (TODO: Not yet implemented)
> 🔄 **Regions still execute a lightweight background tick** (for example every
> second) so queued timers, cooldowns, and delayed events progress even when no
> players are present. (TODO: Not yet implemented)
> 🧠 Tick regions are mapped to Redis shards for atomicity and lock discipline. (TODO: Not yet implemented)
---

## 🔄 Tick Execution Flow

Each tick proceeds as follows:

1. **Collect Actions**
   From the command queues of active entities in the tick region (TODO: Not yet implemented)

2. **Resolve Fairly**
   Sort by timestamp, stat priority, or custom policy; only one action per entity is executed per tick (TODO: Not yet implemented)

3. **Apply Effects**
   Mutate entity state (e.g., HP, inventory, buffs, position) (TODO: Not yet implemented)

4. **Trigger Events**
   Run regeneration, room scripts, NPC behaviors, AI-driven commands — all use the same command queue model (TODO: Not yet implemented)

> Note: Game Logic Service resolves each action statelessly and does not participate in commit or rollback phases — those are fully managed by the Game Session Service via Redis.

The **Game Session Service** manages orchestration, while gameplay rules are resolved via the **Game Logic Service**, and **final commit flow is also handled by Game Session Service**.

---

## 🧮 Tick Staging and Commit Flow

State changes are first **staged in Redis** under keys like `tick:pending:{tenantId}:{regionId}`:

- Only committed if **all actions succeed** (TODO: Not yet implemented)
- Timeout or failed actions are **excluded** and **rescheduled with priority** (TODO: Not yet implemented)
- Commit and rollback are coordinated by Game Session Service using Lua scripts in Redis

This ensures:

- Atomic per-tick updates (TODO: Not yet implemented)
- Partial failure recovery (TODO: Not yet implemented)
- Conflict-free shared state across retries (TODO: Not yet implemented)

---

## ⏳ Timeout and Fairness Policy

Each tick enforces a **soft execution budget** (e.g., 100ms):

- Slow actions are **deferred** to retry in exclusive follow-up ticks (TODO: Not yet implemented)
- These retries are **not executed in parallel** (TODO: Not yet implemented)
- **Oldest actions win** in conflict scenarios (TODO: Not yet implemented)
- Newer submissions are backlogged until resolved (TODO: Not yet implemented)
- Conflict metadata enables **hotspot detection** and future livelock prevention (TODO: Not yet implemented)
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

## 🔍 Isolation and Replay Safety

To prevent cross-tick contamination and support deterministic replay:

- Actions may **only see staged state from the current tick**
- Changes from other tick regions or future ticks are **invisible**
- Changes staged earlier in the same tick **are composable**
- Missing dependencies cause the action to **fail and retry**

This guarantees **clean, deterministic ticks** and safe replays after crash or restart.

---

## ⏱️ Timers and Time Scaling

Time-based effects (e.g., cooldowns, regeneration) are managed with **real-time timers in milliseconds**, stored as: (TODO: Not yet implemented)

- `timer:{tenantId}:{entityId}:{effectId}`

Each tick scans timers for expirations and triggers corresponding events. If delayed, multiple may fire at once. (TODO: Not yet implemented)

### 🕒 Dynamic Time Scaling

Durations can be modified on the fly: (TODO: Not yet implemented)

- `scaled = base * multiplier`
- Used for spell effects, world modifiers (e.g., slow motion), or status changes
- Time scaling affects **durations**, not tick rate (TODO: Not yet implemented)

> Runtime feature flags controlling global pace or status effects are applied by the Game Session Service before tick execution.
> For how these flags are defined and edited, see [Versioning & Runtime Configuration](./system-architecture-versioning-runtime.md).

---

## 💥 Crash Recovery and Replay

If a tick crashes mid-flight (e.g., Game Session Service restart), Redis preserves:

- Tick locks (`tick:lock:*`)
- Staged effects (`tick:pending:*`)
- Timers (`timer:*`)
- Retry and conflict state

Recovery is coordinated by Game Session Service and backed by:

- **Lua-based atomic updates**
- **AOF (Append-Only File)** persistence
- `WAIT 1 100` for durable replication

This supports **idempotent, replayable** ticks — without risk of duplicate effects or inconsistent state.

---

## 📡 Cross-Region Command Execution and Result Relay

Some commands originate in one region but affect targets in another — for
example, remote attacks, cross-world spells, or administrative inventory moves.
The process is **asynchronous and multi-phased**: (TODO: Not yet implemented)

1. A tick-local command executes in the **origin region**. (TODO: Not yet implemented)
2. A follow-up action — including the `sourceEntityId` — is enqueued in the
   **target region's** command queue (often under a `remote:{tenantId}:{entityId}` key; see
   [Redis Key Naming](./system-architecture-redis.md#🗂️-key-naming-and-shard-discipline)). (TODO: Not yet implemented)
3. The target region processes the action during its next tick and determines
   the outcome locally. (TODO: Not yet implemented)
4. The Game Session Service uses the `sourceEntityId` to route the result back
   to the origin region or directly to the player's active session. (TODO: Not yet implemented)

Players experience a smooth flow:

```text
🕒 You cast Fireball...
🔥 Your Fireball hits Player B for 12 damage!
```

No region waits synchronously for another shard, preserving responsiveness and
deterministic replay. (TODO: Not yet implemented)

### ⛓️ Tick Chaining and Reentrant Effect Control

Each action carries a `tickChainDepth`. When follow-up effects (stuns,
explosions, scripted traps) spawn additional actions, the depth is incremented.
If `MAX_TICK_CHAIN_DEPTH` (default **8**) is exceeded, the new action is
aborted and a warning is logged. Prior steps remain committed, and the player
may be notified that the chain was halted. (TODO: Not yet implemented)

---

## 🧠 Service Responsibilities

| Service                   | Role                                                                 |
|---------------------------|----------------------------------------------------------------------|
| **Game Session Service**          | Orchestrates tick regions, lock acquisition, retries, commit flow    |
| **Game Logic Service**            | Resolves each queued action deterministically                         (TODO: Not yet implemented)|
| **Automation & Scripting**| Injects AI or scripted commands into queues                          |
| **World Management**      | Defines tick region layout and room segmentation (TODO: Not yet implemented)                     |
| **Redis**                 | Stores locks, timers, staged changes, retry metadata; executes Lua   |

> Game Session Service manages all tick lifecycle logic and delegates atomic operations to Redis via Lua.

---

## ✅ Model Benefits

- ✅ Parallel, fault-isolated tick execution with room-level isolation
- ✅ Lock-on-demand using Redis avoids fixed thread ownership
- ✅ Atomic staging and safe rollback via Lua
- ✅ Deterministic recovery using AOF + `WAIT`
- ✅ Conflict metadata avoids livelocks and enables adaptive pacing (TODO: Not yet implemented)
- ✅ Flexible time scaling without affecting system cadence (TODO: Not yet implemented)
- ✅ No in-service volatile state — everything recoverable via Redis

> FireMUD treats time as **localized pulses**, not a global clock. Each tick is a self-contained transaction — safely composing gameplay logic in a world that never stops evolving.

---

## 📚 Related Documentation

- [Redis Architecture](./system-architecture-redis.md)
- [Transaction Strategies](./system-architecture-transactions.md)
- [System Architecture Overview](./system-architecture-overview.md)
