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

Conflict metadata is logged and used to intelligently reorder future submissions.

> 🔗 See [Redis Lock Behavior](./system-architecture-redis.md#🔒-atomicity-and-concurrency-control-with-lua) for script structure.

### 🧠 Retry Scheduling

When an action fails due to contention:

- The system records the **blocking lock** and region
- The Game Session Service:
  - Reschedules the action where the conflict originated
  - Staggers or delays conflicting ticks
  - Prevents retry storms and CPU waste

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
   - From command queues of active entities in the region

2. **Resolve Fairly**
   - Sort by timestamp, stat priority, or game-defined rule
   - One action per entity (default behavior)

3. **Apply Effects**
   - Update HP, inventory, position, status, etc.

4. **Trigger Events**
   - Fire regeneration, AI moves, scripted logic
   - Injected actions are treated equally and staged for future ticks

Orchestration is owned by the **Game Session Service**, while gameplay rules are enforced by the **Game Logic Service**.

---

## 🧮 Tick Staging and Commit Model

All state changes are staged in Redis:

- Stored under keys like `tick:pending:{regionId}`
- Only committed when the entire tick succeeds
- Partial failures are excluded from commit

If the tick times out:

- Only successful actions are finalized
- Others are retried later

> 🔗 See [Redis Key Naming](./system-architecture-redis.md#🗂️-key-naming-and-shard-discipline) for full structure.

---

## ⏳ Timeout and Fairness Policy

Each tick operates under a **soft time budget** (e.g., 100ms):

- Slow actions are deferred to **exclusive follow-up ticks**
- These retries will **not execute in parallel** with the original action
- **Oldest actions win** in conflict scenarios
- **Newer conflicting actions** are delayed to maintain fairness
- **Conflict metadata is tracked** to detect **hotspots** and potential **livelocks**

This avoids starvation, ensures responsiveness under load, and prevents runaway retries.

---

## 🔍 Isolation and Visibility Rules

To prevent cross-tick contamination:

- Actions only access staged state from the **current tick**
- **Other regions’ in-progress data is invisible**
- If partial state is detected, the action **fails and retries**

This ensures composable, race-free logic across distributed tick regions.

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

Replay is deterministic:

- Staged updates are idempotent
- Locks are validated before resuming
- Lua scripts ensure atomicity

> 🔗 See [Redis Safety Guarantees](./system-architecture-redis.md#🛡️-redis-availability-consistency-and-safety-guarantees) for details.

---

## 🧠 Service Responsibilities

| Service                   | Role                                                                 |
|---------------------------|----------------------------------------------------------------------|
| **Game Session**          | Orchestrates tick cycles, retries, locks, pacing, and tick state     |
| **Game Logic**            | Processes each queued action deterministically                       |
| **Automation & Scripting**| Injects NPC or scripted actions into queues                          |
| **World Management**      | Defines room layout and tick regions; does not execute ticks         |
| **Redis**                 | Stores locks, staged results, timers, conflict data, retry queues    |

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
