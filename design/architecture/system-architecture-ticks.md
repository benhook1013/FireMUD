# ⏱️ FireMUD System Architecture: Tick System and Runtime Design

📄 This document expands on the [Game Loop / Tick Model](./system-architecture-overview.md#⏱️-game-loop--tick-model) section of the FireMUD System Architecture Overview. It defines how ticks execute, resolve concurrency, handle crashes, and preserve deterministic, fair game logic under load. Cross-service operations triggered by ticks rely on Redis scripts and gRPC; sagas are unnecessary for these gameplay actions as explained in [Transaction Strategies](./system-architecture-transactions.md).

> 🔗 For Redis keys, Lua-based atomicity, and operational guarantees, see the [Redis Architecture](./system-architecture-redis.md).

---

## 🧠 Hybrid Tick Model

FireMUD uses a **Hybrid Tick Model** to balance real-time responsiveness with deterministic action resolution:

- **Player inputs arrive in real-time**, rate-limited and queued in per-session **command queues**
- At regular **tick intervals** (e.g., 1s):
  - One action (if any) is pulled from each entity’s command queue
  - Actions are resolved in a fair, ordered cycle (e.g. by timestamps or stat priority)
  - Only **one action per entity per tick** is executed for fairness
  - State changes are applied in a single coordinated pass

This model ensures:

- Responsive feel for players
- Deterministic conflict resolution (e.g., pickups, interrupts)
- Equal treatment of AI and player actions
- Scheduled updates for effects like cooldowns, patrols, regeneration

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
- The action is **rescheduled for retry** by the Game Session Service

Conflict metadata is recorded and reported to the Game Session Service, which **reorders future submissions** intelligently.

> 🔗 See [Atomicity and Concurrency Control](./system-architecture-redis.md#🔒-atomicity-and-concurrency-control)

---

## 🔁 Smart Retry and Conflict Resolution

FireMUD includes a robust system for **lock contention**, **timeouts**, and **retries**, coordinated by the Game Session Service and powered by Redis.

### 🧠 Retry Scheduling

When an action fails due to contention:

- Redis logs the **blocking lock** and conflicting region
- The Game Session Service:
  - Reschedules the action within the blocked region
  - **Prioritizes retries** to minimize player-visible delays
  - Staggers or delays conflicting ticks to avoid churn
  - Prevents retry storms and wasted CPU

Future enhancements may include:

- Backoff windows and retry caps
- Graph-based conflict resolution
- Hotspot surface metrics and adaptive throttling

---

## 🌍 Tick Regions and Parallel Execution

Ticks are **region-scoped**, not globally synchronized. Each **tick region** (typically a room or room cluster) runs its own independent cycle, enabling:

- **Parallelism** across threads and servers
- **Fault isolation** from slow or overloaded regions
- **Configurable pacing** per region (tick rate or delay)
- **Elastic execution** across worker instances

> 🧠 Tick regions are mapped to Redis shards for atomicity and lock discipline.

---

## 🔄 Tick Execution Flow

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

## 🧮 Tick Staging and Commit Flow

State changes are first **staged in Redis** under keys like `tick:pending:{tenantId}:{regionId}`:

- Only committed if **all actions succeed**
- Timeout or failed actions are **excluded** and **rescheduled with priority**
- Commit and rollback are coordinated by Game Session Service using Lua scripts in Redis

This ensures:

- Atomic per-tick updates
- Partial failure recovery
- Conflict-free shared state across retries

---

## ⏳ Timeout and Fairness Policy

Each tick enforces a **soft execution budget** (e.g., 100ms):

- Slow actions are **deferred** to retry in exclusive follow-up ticks
- These retries are **not executed in parallel**
- **Oldest actions win** in conflict scenarios
- Newer submissions are backlogged until resolved
- Conflict metadata enables **hotspot detection** and livelock prevention

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

Time-based effects (e.g., cooldowns, regeneration) are managed with **real-time timers in milliseconds**, stored as:

- `timer:{tenantId}:{entityId}:{effectId}`

Each tick scans timers for expirations and triggers corresponding events. If delayed, multiple may fire at once.

### 🕒 Dynamic Time Scaling

Durations can be modified on the fly:

- `scaled = base * multiplier`
- Used for spell effects, world modifiers (e.g., slow motion), or status changes
- Time scaling affects **durations**, not tick rate

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

## 🧠 Service Responsibilities

| Service                   | Role                                                                 |
|---------------------------|----------------------------------------------------------------------|
| **Game Session Service**          | Orchestrates tick regions, lock acquisition, retries, commit flow    |
| **Game Logic Service**            | Resolves each queued action deterministically                        |
| **Automation & Scripting**| Injects AI or scripted commands into queues                          |
| **World Management**      | Defines tick region layout and room segmentation                     |
| **Redis**                 | Stores locks, timers, staged changes, retry metadata; executes Lua   |

> Game Session Service manages all tick lifecycle logic and delegates atomic operations to Redis via Lua.

---

## ✅ Model Benefits

- ✅ Parallel, fault-isolated tick execution with room-level isolation
- ✅ Lock-on-demand using Redis avoids fixed thread ownership
- ✅ Atomic staging and safe rollback via Lua
- ✅ Deterministic recovery using AOF + `WAIT`
- ✅ Conflict metadata avoids livelocks and enables adaptive pacing
- ✅ Flexible time scaling without affecting system cadence
- ✅ No in-service volatile state — everything recoverable via Redis

> FireMUD treats time as **localized pulses**, not a global clock. Each tick is a self-contained transaction — safely composing gameplay logic in a world that never stops evolving.

---

## 📚 Related Documentation

- [Redis Architecture](./system-architecture-redis.md)
- [Transaction Strategies](./system-architecture-transactions.md)
- [System Architecture Overview](./system-architecture-overview.md)
