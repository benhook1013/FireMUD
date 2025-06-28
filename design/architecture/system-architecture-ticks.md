# ⏱️ FireMUD System Architecture: Tick System and Runtime Flow

📄 This document expands on the [Game Loop / Tick Model](./system-architecture-overview.md#⏱️-game-loop--tick-model) section of the FireMUD System Architecture Overview. It provides a complete view of how ticks execute, manage concurrency, handle crashes, and preserve deterministic, fair game logic under real-time load.

---

FireMUD uses a **Hybrid Tick Model (Model C)** to balance real-time responsiveness with deterministic action resolution:

- **Player inputs arrive in real-time**, rate-limited and queued in per-session **command queues**
- At regular **tick intervals** (e.g., 1s):  
  - One action (if any) is pulled from each entity's command queue  
  - Actions are resolved in a fair, ordered cycle  
  - State changes are applied in a single coordinated pass  

This model ensures:

- A responsive feel to players  
- Deterministic resolution of conflicts (e.g., item pickups, spell interrupts)  
- Equal participation for AI and players  
- Consistent scheduling for effects like cooldowns, buffs, patrols, and regeneration  

---

## 🌍 Room-Based Tick Regions

Ticks are **region- or room-scoped**, not globally synchronized. Each tick region (e.g., room or map segment) runs its own tick cycle independently, enabling:

- **Scalability**: ticks run in parallel across threads or servers  
- **Fault isolation**: slow rooms (e.g., large combats) don’t block others  
- **Flexible pacing**: different tick rates for different gameplay styles  
- **Elastic execution**: any Game Session instance can tick any region  

---

## 🔄 Tick Execution Model

Each tick executes the following steps:

1. **Collect Actions**  
   From the command queues of active entities in the region  

2. **Resolve Fairly**  
   - Ordered by stats, timestamps, or priority  
   - Typically one action per entity (configurable per game design)  

3. **Apply Effects**  
   - Modify entity state (HP, status, position, inventory, etc.)  

4. **Trigger Events**  
   - Regeneration, room scripts, NPC decisions  
   - AI and scripting systems may inject actions via the same queue, treated equally

Each action is processed via the **Game Logic Service**, while orchestration and Redis commit are managed by the **Game Session Service**.

---

## 🔐 Distributed Entity Locking

Parallel ticks may target the same entity (e.g., shared pet). To prevent conflicts, FireMUD uses **distributed locks in Redis**:

- Locks are acquired **on-demand** during action execution  
- Locks may include **entities, items, rooms**, or other mutable components  
- Keys are structured as `tick:lock:{entityId}` and acquired using `SET NX PX` for exclusive ownership with expiry  
- Lock keys are namespaced and shard-friendly for efficient Redis clustering  

If a required lock is unavailable:

- The action is **timed out and skipped**  
- It **fails atomically**, rolls back any staged updates, and is **re-queued** for a future retry  
- Lock conflict metadata is reported to **Game Session**, which uses it to reorder future submissions intelligently

This system ensures **safe concurrency**, **minimal contention**, and **progressive conflict resolution**.

---

## 🧮 Tick Staging, Timeout, and Retry

Tick results are **staged in Redis** and only **committed** once all successful actions have completed:

- Changes are stored as pending deltas (e.g., `tick:pending:{entityId}`)  
- The Game Session Service commits results at the end of the tick  
- Timed-out or failed actions are **excluded from commit** and **rescheduled with priority**

Actions are **idempotent**, and must retry cleanly.

### ⏳ Timeout Policy

- Each tick has a **soft execution window** (e.g., 100ms)
- Actions exceeding this window (due to lock contention or heavy logic):
  - Are deferred
  - Are re-executed in a dedicated, exclusive follow-up tick  
  - Will not retry in parallel with the same conflicting action

To manage fairness:

- **Oldest actions win**
- **Newer conflicting actions are delayed**
- **Conflict metadata** is logged and used to detect hotspots or livelocks

### 🧠 Smart Retry Scheduling

When an action fails due to lock contention:

- The system records the **conflicting lock** and responsible tick region  
- This enables Game Session to:
  - Reschedule the action in a tick that owns the blocking region  
  - **Throttle or stagger** other ticks that target the same resource  
  - Avoid cascading retries or wasted CPU

Optional improvements include:

- **Backoff windows and retry caps**
- **Graph-based conflict detection**
- **Surface metrics and alerts for hotspots**

---

## 🔍 Inter-Tick Visibility and Isolation

FireMUD maintains **strict tick isolation**. Actions may only rely on consistent, tick-local state:

- Within a tick:
  - Actions see **state staged earlier in that same tick**
  - If an action depends on updated data, it must **read the new value** or **retry if unsafe**
- Across ticks:
  - **Staged changes from other tick regions are invisible**
  - An action seeing partial state from an in-flight or rolled-back tick **fails and retries**

This model ensures **deterministic, race-free logic**, composability across regions, and **clean replays on failure**.

---

## ⏱️ Timers and Time Scaling

FireMUD uses **real-world time** for durations and cooldowns:

- Durations (e.g., cooldowns) are stored as `5000ms`, not "5 ticks"  
- Each tick checks timer expirations and triggers effects if elapsed  
- If a tick is delayed (e.g., CPU spike), **multiple timers may fire together**

### 🕒 Time Scaling

Time-based effects can be scaled dynamically without adjusting tick intervals:

- Examples:
  - Global slow/fast mode
  - Local pacing changes (e.g., time bubbles, spells)
  - Buff/debuff modifiers

Scaling uses a multiplier (e.g., `cooldown = 5000ms * 0.9 = 4500ms`) and applies per-effect or globally.

---

## 💥 Crash Safety and Recovery

If the **Game Session Service crashes mid-tick**:

- Redis contains:
  - All **tick locks**
  - **Staged (but uncommitted) changes**
  - **Timer state**
- Redis uses **Append-Only File (AOF) persistence** for crash recovery
- Redis writes are followed by a `WAIT` command (e.g., `WAIT 1 100`) to ensure at least one replica has confirmed persistence before proceeding
- All staged updates are stored in **Lua-executed, atomic scripts**, which guarantee isolation and safety during reprocessing

Upon restart, the Game Session Service:

- Detects in-flight ticks that didn’t commit
- Rolls forward or replays tick with same staged inputs
- Discards conflicting changes if needed, ensuring idempotent recovery

This model allows **safe recovery**, **no double-execution**, and **no corruption of Redis state**.

---

## 🧠 Responsibilities by Service

| Service                   | Role                                                                 |
|---------------------------|----------------------------------------------------------------------|
| **Game Session**          | Owns and executes tick cycles, manages retries, lock coordination, finalization, tick pacing |
| **Game Logic**            | Processes each queued action deterministically and requests locks     |
| **Automation & Scripting**| Injects NPC or scripted actions into command queues                   |
| **World Management**      | Defines tick regions and room layout metadata; does not execute ticks |
| **Redis**                 | Stores locks, timers, pending results, conflict metadata, and retry queues |

---

## 🛡️ Model Benefits

- ✅ True parallel ticks with safe entity updates  
- ✅ Lock-on-demand prevents race conditions without needing region ownership  
- ✅ Partial success and clean retries with no starvation  
- ✅ Tick resilience across crashes and soft failures  
- ✅ Region-based execution with no sticky routing  
- ✅ Time scaling supports flexible pacing and effect dynamics  
- ✅ Redis-backed safety guarantees (AOF, `WAIT`, Lua)  
- ✅ Smart lock conflict handling with automated reordering and isolation

---

> FireMUD treats time as **localized pulses**, not a global clock. Each tick is a self-contained transaction — safely composing gameplay logic in a world that never stops evolving.
