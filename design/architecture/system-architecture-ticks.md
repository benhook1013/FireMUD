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
- Consistent scheduling for effects like cooldowns, buffs, patrols, and regen

---

## 🌍 Room-Based Tick Regions

Ticks are **region- or room-scoped**, not globally synchronized. Each area runs its own tick cycle, enabling:

- **Scalability**: ticks run in parallel across threads or servers  
- **Fault isolation**: slow rooms (e.g., large combats) don’t block others  
- **Flexible pacing**: different tick rates for different gameplay styles  

This promotes sharded execution and efficient resource use.

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
- It is **guaranteed retry** in the next tick, run in isolation  

This ensures **safe parallelism** and protects shared state integrity.

---

## 🧮 Tick Commitment and Timeout

Tick results are **staged** during execution and only **committed** once successful actions complete:

- Changes are stored temporarily in Redis (e.g., `tick:pending:{entityId}`)  
- The Game Session Service finalizes the tick by applying successful updates  
- Timed-out actions are **excluded** from commitment and **re-queued for retry**

This allows partial success and keeps the game progressing:

- ✅ Good actions are not discarded  
- ✅ Problematic actions are retried fairly  
- ✅ No risk of partial application corrupting state

---

## ⏳ Timeout and Retry Model

Each tick has a **soft execution limit** (e.g., 100ms). Actions exceeding it (due to computation or locking) are:

- **Timed out**  
- **Re-queued for the next tick**, where they’re given **exclusive execution**

This keeps ticks responsive while guaranteeing eventual progress for slow actions.

Optional enhancements:

- Retry backoff and limits  
- Logging or metrics on frequent timeouts  
- Batching safe-to-run-together deferred actions

---

## ⏱️ Timers and Time Scaling

Effect durations use **real-time tracking**, not tick counts:

- E.g., cooldown = `5000ms`, not "5 ticks"  
- Ticks check timers and trigger effects whose time has elapsed  

If time has passed since the last tick (due to lag), **multiple effects may process together**.

### 🕒 Time Scaling

Time-based effects are adjusted via **scale factors**, not tick frequency:

- Example: `5000ms` cooldown × `0.9` scale → `4500ms`  
- Applies globally, per-room, or per-entity (e.g., haste/slow)

This keeps tick timing stable while supporting nuanced pacing.

---

## 🧾 Atomicity and Resilience

Ticks act as **atomic units** for safe and isolated progress:

- Tick actions are committed together only when complete  
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
| **Game Session**          | Owns and executes all tick cycles; handles scheduling, timeout enforcement, and committing state |
| **Game Logic**            | Executes ordered actions and computes state changes                 |
| **Automation & Scripting**| Drives NPC behaviors and scripted logic triggered by ticks          |
| **World Management**      | Maintains tick region boundaries and metadata; informs Game Session which regions exist but does not execute ticks |
| **Redis**                 | Stores locks, timers, and staged results for tick processing        |

---

## 🛡️ Model Benefits

- ✅ No race conditions via distributed locking  
- ✅ Region-based ticks support horizontal scaling  
- ✅ Deterministic action resolution with real-time feel  
- ✅ Clean fallback for slow or blocked actions  
- ✅ Supports time dilation and pacing variation  
- ✅ Robust to partial failure or retry  
- ✅ Minimal tick overhead with high accuracy  
- ✅ Clear state management and testability

---

> FireMUD treats time as **localized pulses**, not a single clock. Each tick is a reliable beat in a chaotic world—resolving actions fairly, scaling cleanly, and keeping gameplay smooth across shards.
