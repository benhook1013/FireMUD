# ⏱️ Tick System and Runtime Flow

FireMUD uses a **Hybrid Tick Model (Model C)** to balance real-time responsiveness with deterministic action resolution:

- **Player inputs arrive in real-time**, rate-limited and queued in per-session command buffers  
- At regular **tick intervals** (e.g., 1s):  
  - One action (if any) is pulled from each entity  
  - Actions are resolved in fair order  
  - State changes are applied in a single coordinated pass  

This model ensures:

- A responsive feel to players  
- Deterministic resolution of conflicts (e.g., item pickups, spell interrupts)  
- Equal participation for AI and players  
- Consistent scheduling for effects like cooldowns, buffs, patrols, and regeneration  

---

## 🌍 Room-Based Tick Regions

Ticks are **region- or room-scoped**, not globally synchronized. Each area runs its own tick cycle, enabling:

- **Scalability**: ticks run in parallel across threads or servers  
- **Fault isolation**: slow rooms (e.g., large combats) don’t block others  
- **Flexible pacing**: different tick rates for different gameplay styles  

This promotes sharded execution and efficient resource usage.

---

## 🔄 Tick Execution Model

Each tick executes the following steps:

1. **Collect Actions**  
   From queued commands of active entities in the region  

2. **Resolve Fairly**  
   - Ordered by stats, timestamps, or priority  
   - Typically one action per entity (configurable)  

3. **Apply Effects**  
   - Modify entity state (HP, status, position, inventory, etc.)  

4. **Trigger Events**  
   - Regeneration, room scripts, NPC decisions  

---

## 🔐 Distributed Entity Locking

Parallel ticks may target the same entity (e.g., shared pet). To prevent conflicts, FireMUD uses **distributed locks**:

- Each service must **acquire a lock** before mutating entity state  
- Locks are stored in Redis as `tick:lock:{entityId}`  
- Acquired with `SET NX PX` for exclusive ownership and expiry safety  

If a lock isn’t acquired:

- The action is **timed out and skipped**
- A **structured conflict report** is returned, including:
  - `conflictedEntityId`
  - `holdingRegionId`
  - `tickId` or `batchId`
- The Game Session uses this information to **coordinate conflict-aware retries**

---

## 🧮 Tick Staging, Timeout, and Smart Retry

Tick results are **staged** during execution and only **committed** once successful actions complete:

- Changes are stored temporarily in Redis (e.g., `tick:pending:{entityId}`)  
- The Game Session Service finalizes the tick by applying only the **successful staged updates**  
- **Timed-out or failed actions** are excluded from commitment and **re-queued for retry**  

### Retry Strategy

The Game Session analyzes conflict reports from failed actions and schedules retries intelligently:

- Conflicting actions are grouped based on entity or tick region conflicts
- **Only one side of a conflict group** is re-attempted immediately
- The other conflicting action(s) are **delayed until the retry batch completes successfully**

This prevents **ping-pong contention** and ensures **eventual success** for all deferred actions.

> ✅ Retries are always isolated and never interfere with ongoing ticks  
> ✅ Retry grouping ensures liveness without thrashing  

Each tick also enforces a **soft execution limit** (e.g., 100ms). Actions exceeding it (due to computation or locking) are:

- Timed out  
- Deferred for retry in the next tick, with exclusive execution

Optional enhancements:

- Retry backoff and maximum retry limits  
- Logging and metrics for timeout diagnostics  
- Batching compatible deferred actions  

> **Note:** Actions must be idempotent across retries. Retried actions should produce consistent results regardless of tick timing.

---

## ⏱️ Timers and Time Scaling

Effect durations use **real-time tracking**, not tick counts:

- E.g., cooldown = `5000ms`, not "5 ticks"  
- Ticks check timers and trigger effects whose time has elapsed  

If time has passed since the last tick (e.g., due to lag), **multiple effects may process together**.

### 🕒 Time Scaling

Time-based effects are adjusted via **scale factors**, not tick frequency:

- Example: `5000ms` cooldown × `0.9` scale → `4500ms`  
- Applies globally, per-room, or per-entity (e.g., haste/slow)  

This maintains stable system timing while supporting nuanced pacing.

---

## 🧾 Atomicity and Resilience

Ticks act as **atomic units** for safe and isolated progress:

- Tick actions are committed together only when successful  
- Timed-out actions are deferred cleanly  
- Failures in one region don’t affect others  

This ensures:

- ✅ Consistent progression  
- ✅ Resilience during partial failures  
- ✅ Easy debugging and modular recovery  

---

## 🧠 Responsibilities by Service

| Service                   | Role                                                                 |
|---------------------------|----------------------------------------------------------------------|
| **Game Session**          | Owns and executes all tick cycles; handles scheduling, timeout enforcement, conflict-aware retry logic, and committing state |
| **Game Logic**            | Executes ordered actions and computes state changes                 |
| **Automation & Scripting**| Drives NPC behaviors and scripted logic triggered by ticks          |
| **World Management**      | Maintains tick region boundaries and metadata; informs Game Session which regions exist but does not execute ticks |
| **Redis**                 | Stores locks, timers, staged results, and conflict metadata         |

---

## 🛡️ Model Benefits

- ✅ No race conditions via distributed locking  
- ✅ Region-based ticks support horizontal scaling  
- ✅ Deterministic action resolution with real-time feel  
- ✅ Clean fallback for slow or blocked actions  
- ✅ Conflict metadata enables smart retry scheduling  
- ✅ Supports time dilation and pacing variation  
- ✅ Robust to partial failure or retry  
- ✅ Minimal tick overhead with high accuracy  
- ✅ Clear state management and testability  
- ✅ Guarantees retry for deferred actions without starvation  

---

> FireMUD treats time as **localized pulses**, not a single clock. Each tick is a reliable beat in a chaotic world—resolving actions fairly, scaling cleanly, and keeping gameplay smooth across shards.
