# ⏱️ FireMUD System Architecture: Tick System and Runtime Flow

📄 This document expands on the [Game Loop / Tick Model](./system-architecture-overview.md#⏱️-game-loop--tick-model) section of the FireMUD System Architecture Overview. It defines how ticks execute, resolve concurrency, handle crashes, and preserve deterministic, fair game logic under load.

> 🔗 For Redis keys, Lua-based atomicity, and operational guarantees, see the [Redis Architecture](./system-architecture-redis.md).

---

## 🧠 Hybrid Tick Model

FireMUD uses a **Hybrid Tick Model (Model C)** to balance real-time responsiveness with deterministic action resolution:

- **Player inputs arrive in real-time**, rate-limited and queued in per-session **command queues**
- At regular **tick intervals** (e.g., 1s):
  - One action (if any) is pulled from each entity’s command queue
  - Actions are resolved in a fair, ordered cycle
  - State changes are applied in a single coordinated pass

This model ensures:

- Responsive feel for players
- Deterministic conflict resolution (e.g., pickups, interrupts)
- Equal treatment of AI and player actions
- Scheduled updates for effects like cooldowns, patrols, regeneration

---

## 🔁 Smart Retry and Conflict Resolution

FireMUD includes a robust system for **lock contention**, **timeouts**, and **retries**, coordinated through Redis.

### 🔐 Distributed Locking

To prevent concurrent entity updates, ticks acquire **distributed locks** in Redis using:

- `tick:lock:{entityId}` (see [Redis Key Reference](./system-architecture-redis.md#🗂️-key-naming-and-shard-discipline))
- `SET NX PX` with expiry for exclusive ownership
- Lua-based atomic checks to avoid race conditions

If a required lock is unavailable:

- The action **fails immediately**
- All staged changes are rolled back
- The action is **rescheduled for retry**

Conflict metadata is recorded and **reported to the Game Session Service**, which uses it to **reorder future submissions intelligently**.

> 🔗 See [Redis Lock Behavior](./system-architecture-redis.md#🔒-atomicity-and-concurrency-control-with-lua) for script structure.

### 🧠 Retry Scheduling

When an action fails due to contention:

- The system logs the **blocking lock** and responsible tick region
- The Game Session Service:
  - Reschedules the action within the blocking region
  - Staggers or delays conflicting ticks
  - Prevents retry storms and wasted CPU
  - **Prioritizes rescheduled retries** to minimize action delay

Future enhancements may include:

- Backoff windows and retry caps
- Graph-based conflict detection
- Surface metrics and alerts for hotspots

---

## 🌍 Room-Based Tick Regions

Ticks are **region-scoped**, not globally synchronized. Each room or segment runs its own independent tick cycle, enabling:

- **Parallelism** across threads and servers
- **Fault isolation** from slow or complex rooms
- **Pacing control** (different tick rates per region)
- **Elastic execution** across worker instances

---

## 🔄 Tick Execution Flow

Each tick follows this process:

1. **Collect Actions**  
   From the command queues of active entities in the region.

2. **Resolve Fairly**  
   Sort actions by timestamps, stat priority, or custom logic. Typically, only one action per entity is resolved per tick for fairness.

3. **Apply Effects**  
   Modify entity state such as HP, position, inventory, buffs, or other gameplay attributes.

4. **Trigger Events**  
   Fire regeneration, room scripts, NPC behavior, or automated actions. Scripted and AI-injected actions use the same queue system and are treated equally.

The **Game Session Service** manages orchestration, while gameplay rules are resolved via the **Game Logic Service**, and **final commit flow is also handled by Game Session**.

---

## 🧮 Tick Staging and Commit Model

All state changes are staged in Redis:

- Stored under keys like `tick:pending:{regionId}`
- Only committed when **all successful actions** have completed
- Timed-out or failed actions are **excluded from commit** and **rescheduled with priority**

This guarantees atomicity and ensures that **partial tick progress does not corrupt shared state**.

If the tick times out:

- Only successful actions are finalized
- Deferred actions are retried in follow-up ticks

> 🔗 See [Redis Key Naming](./system-architecture-redis.md#🗂️-key-naming-and-shard-discipline) for full structure.

---

## ⏳ Timeout and Fairness Policy

Each tick operates under a **soft time budget** (e.g., 100ms):

- Slow actions are deferred to **dedicated, exclusive follow-up ticks**
- These retries **do not execute in parallel** with the same conflicting action
- **Oldest actions win** in conflict scenarios
- **Newer conflicting actions** are delayed to maintain fairness
- **Conflict metadata is tracked** to detect **hotspots** and prevent **livelocks**

This avoids starvation, ensures responsiveness under load, and guarantees tick-level determinism.

---

## 🔍 Isolation and Visibility Rules

To prevent cross-tick contamination, FireMUD enforces strict tick isolation:

- Actions only access **staged state from the current tick**
- **Staged changes from other tick regions are invisible**
- Changes staged earlier in the same tick **are visible** and composable
- If an action depends on a value not yet staged or committed:
  - It must **retry**
  - This prevents race conditions and ensures clean replays

This composability model ensures tick transactions are **self-contained, deterministic, and rollback-safe**.

---

## ⏱️ Timers and Time Scaling

Time-based effects (e.g. cooldowns, regeneration) use **real-world durations**:

- Stored as millisecond values (e.g. `5000ms`)
- Keys follow the format `timer:{entityId}:{effectId}`
- Each tick checks for timer expirations and triggers any that have elapsed

If a tick is delayed (e.g., CPU load), multiple timers may fire together.

> 🔗 See [Redis Keyspace](./system-architecture-redis.md#🗂️-key-naming-and-shard-discipline) for structure.

### 🕒 Time Scaling

Durations can be modified dynamically:

- `scaled = base * multiplier`
- Used for global effects (e.g., slow motion), spells, or status conditions
- Scaling affects only **effect timing**, not **tick rate**

---

## 💥 Crash Recovery and Replay

If the **Game Session Service** crashes mid-tick:

- Redis retains:
  - Active locks (`tick:lock:*`)
  - Staged deltas (`tick:pending:*`)
  - Timer state (`timer:*`)

Redis provides durability through:

- **Append-Only File (AOF) persistence**
- `WAIT 1 100` after writes for replica acknowledgment

All staged updates are stored via **Lua-executed, atomic scripts**, enabling:

- **Replay or roll-forward** of incomplete ticks
- **Safe retries** with no double-processing
- **Deterministic recovery**

> 🔗 See [Redis Safety Guarantees](./system-architecture-redis.md#🛡️-redis-availability-consistency-and-safety-guarantees) for details.

---

## 🧠 Service Responsibilities

| Service                   | Role                                                                 |
|---------------------------|----------------------------------------------------------------------|
| **Game Session**          | Orchestrates tick cycles, retries, lock coordination, pacing, and commit flow |
| **Game Logic**            | Processes each queued action deterministically                       |
| **Automation & Scripting**| Injects NPC or scripted actions into queues                          |
| **World Management**      | Defines room layout and tick regions; does not execute ticks         |
| **Redis**                 | Stores locks, timers, pending results, conflict metadata, and retry queues |

---

## ✅ Model Benefits

- ✅ True parallel tick execution with safe entity isolation
- ✅ Lock-on-demand prevents race conditions without fixed ownership
- ✅ Partial success and retry-safe rollback behavior
- ✅ Resilient crash recovery using AOF and `WAIT`
- ✅ Scalable tick orchestration without sticky routing
- ✅ Flexible time scaling for effects and pacing
- ✅ Redis-powered durability with atomic Lua execution
- ✅ Hotspot tracking and livelock avoidance using conflict metadata

> FireMUD treats time as **localized pulses**, not a global clock. Each tick is a self-contained transaction — safely composing gameplay logic in a world that never stops evolving.
