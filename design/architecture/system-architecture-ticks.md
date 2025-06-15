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
   - AI and scripting systems may inject actions via the same queue, treated equally

---

## 🔐 Distributed Entity Locking

Parallel ticks may target the same entity (e.g., shared pet). To prevent conflicts, FireMUD uses **distributed locks**:

- Locks are acquired dynamically **as-needed** during action execution  
- Locks may include **entities, items, rooms**, or other mutable components  
- Locks are stored in Redis as `tick:lock:{entityId}`  
- Acquired with `SET NX PX` for exclusive ownership and expiry safety  

If a required lock is unavailable:

- The action is **timed out and skipped**  
- It **fails atomically**, rolls back any staged updates, and is **re-queued** for isolated execution in the next tick  
- Lock conflict metadata is reported to **Game Session**, which uses it to intelligently order future retries (e.g., oldest wins)

This ensures safe concurrency and progressive resolution of overlapping updates.

---

## 🧮 Tick Staging, Timeout, and Retry

Tick results are **staged** during execution and only **committed** once successful actions complete:

- Changes are stored in Redis (e.g., `tick:pending:{entityId}`)  
- The Game Session Service commits **only successful actions** after tick execution  
- Timed-out or failed actions are **excluded from commit** and **rescheduled** with priority  

Actions are **idempotent** and must retry cleanly.

### ⏳ Timeout Policy

- Each tick has a **soft execution window** (e.g., 100ms)  
- Actions that exceed this window (due to logic or lock contention) are:
  - Timed out and deferred  
  - Retriggered **exclusively** in a dedicated follow-up tick  
  - Scheduled to execute **without contention**, ensuring eventual success

The **Game Session Service** ensures that when two actions conflict:

- The **oldest action wins**
- The **newest action is delayed**, ensuring at least one completes
- Lock failure metadata allows Game Session to **detect and manage repeated bottlenecks**

### 🧠 Smart Retry Scheduling

When a lock timeout occurs:

- The system **records the lock that caused the failure** and the responsible tick region
- This allows Game Session to:
  - Submit the failed action into a tick that **owns the conflicting region**
  - **Delay resubmission** of the conflicting tick until the retry completes
  - Avoid wasted retries and endless contention loops

**Optional Enhancements:**

- Retry backoff and cap  
- Logging and metrics to flag persistent conflicts or hotspots  
- Detection of mutually exclusive conflict pairs (e.g., A blocks B and vice versa)

---

## 🔍 Inter-Tick Visibility and Isolation

Actions **may only rely on stable state** within their tick. To prevent cross-tick race conditions:

- During a tick, actions **see pending changes** applied earlier in that same tick  
- If an action attempts to update a value already modified by another action in the same tick, it must:
  - **Reread the new pending value**
  - **Re-evaluate its logic**
  - Or **retry later**, if not safe to proceed  

Between ticks:

- **Staged but uncommitted changes from other regions are not visible**
- If an action sees partial updates from another tick, it **fails and is retried**
  - This protects against depending on data that may roll back if the other tick fails

This strict isolation model ensures ticks remain **deterministic, composable, and safe to run in parallel**.

---

## ⏱️ Timers and Time Scaling

Durations use **real-world time**, not tick counts:

- E.g., cooldown = `5000ms`, not "5 ticks"  
- Ticks check timers each pass and trigger effects when time elapses  

If the system pauses or lags, **multiple timers may fire together** on the next tick.

### 🕒 Time Scaling

Effect durations are scaled without changing tick rate:

- Example: `5000ms` cooldown × `0.9` = `4500ms`  
- Scaling can apply to:
  - Global game modes  
  - Individual rooms or events  
  - Player buffs or debuffs  

This allows complex pacing without timing instability.

---

## 💥 Crash Safety and Recovery

If Game Session crashes or restarts mid-tick:

- Redis contains the tick’s **pending state**  
- Domain services will **discard their partial state**
- Game Session can **pick up the last uncommitted tick**, reset, and **re-execute cleanly**

This model supports **resilient ticks** and **durable retries** across failures.

---

## 🧠 Responsibilities by Service

| Service                   | Role                                                                 |
|---------------------------|----------------------------------------------------------------------|
| **Game Session**          | Owns and executes tick cycles, handles retries, lock ordering, finalization |
| **Game Logic**            | Executes ordered actions, requests locks, and applies logic          |
| **Automation & Scripting**| Injects commands from NPCs, scripts, and system tasks via same tick flow |
| **World Management**      | Maintains tick region metadata and layout; informs but does not control ticks |
| **Redis**                 | Stores locks, timers, conflict metadata, and pending results         |

---

## 🛡️ Model Benefits

- ✅ True parallel ticks with safe entity updates  
- ✅ Lock-on-demand protects real game concurrency  
- ✅ Partial success and retry without starvation  
- ✅ Tick resilience across failures or crashes  
- ✅ Inter-tick state protection and rollback safety  
- ✅ Region-based execution for scale and responsiveness  
- ✅ Clean retry logic for both player and scripted actions  
- ✅ Action conflicts surfaced and managed by Game Session intelligently

---

> FireMUD treats time as **localized pulses**, not a global clock. Each tick is a self-contained transaction — safely composing gameplay logic in a world that never stops evolving.
