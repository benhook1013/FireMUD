# ⏱️ Tick System and Runtime Flow

FireMUD employs a **Hybrid Tick Model (Model C)** to balance real-time responsiveness with deterministic, fair action resolution. In this model:

- **Player inputs are accepted in real-time**, rate-limited, and queued in per-session command buffers
- At regular **tick intervals** (e.g., 1s), the system:
  - Pulls one action (if any) from each entity's queue
  - Resolves them in a consistent, fair order
  - Applies all resulting state changes simultaneously

This approach provides:

- A **responsive feel** to players
- Deterministic conflict resolution (e.g. who picks up an item, interrupting spells)
- Equal opportunity for AI and player-controlled entities
- Tick-driven scheduling for cooldowns, buffs, environment updates, patrols, and status effects

---

## 🌍 Room-Based Ticked Regions

Ticks are **not globally synchronized across the entire game world**. Instead, FireMUD uses **region- or room-scoped tick zones**. Each room or small area operates on its own tick cycle, enabling:

- **Scalability**: multiple regions tick independently across threads or servers
- **Fault isolation**: computationally expensive actions (e.g. large combat) in one room do not block or delay updates in another
- **Flexibility**: different regions can operate at different tick rates depending on content (e.g., slow-paced puzzle room vs fast-paced combat arena)

This model encourages sharded game loop execution and avoids global locks or cascading lag.

---

## 🔄 Tick Execution Model

Each tick (per region/room):

1. **Collect Actions**  
   From the command queues of all active entities in the region (players, NPCs, AI scripts)

2. **Resolve Fairly**  
   - Order may be based on stats (initiative, speed), timestamps, or priority flags
   - Only one action per entity is processed per tick by default (configurable)

3. **Apply Effects**  
   - Entity stats updated (HP, status effects)
   - Position changes, inventory changes, skill effects

4. **Trigger Events**  
   - Environmental effects, regeneration, scripted events, and room-wide triggers
   - AI decisions and queued behaviors may generate new actions

---

## 🔐 Cross-Tick Entity Ownership and Locking

Since tick regions operate independently, it is possible for multiple ticks to target the same entity (e.g., a player in one room and their pet in another). To prevent concurrent updates to shared entities, FireMUD uses a **distributed lock-based ownership model**.

During tick execution, each domain service must **acquire a lock** before applying changes to an entity. This is done **at the point of action processing**, allowing tick batches to be submitted asynchronously and resolved dynamically as locks become available.

- Locks are stored in Redis using namespaced keys like `tick:lock:{entityId}`
- Acquired using `SET NX PX` semantics to ensure:
  - **Only one tick region owns an entity at a time**
  - Locks automatically expire (e.g. after 1 second) to prevent deadlock

If a lock cannot be acquired:

- The domain service **skips or defers** the action until a future tick
- Game Session may optionally be notified to prioritize deferred actions

This ensures entity updates are **serialized across tick regions**, enabling **safe asynchronous execution** of tick batches while preventing race conditions across the distributed system.

---

## 🧮 Tick Commitment Model

To prevent mid-tick inconsistencies, **actions do not apply changes directly to live game state**. Instead:

- **Each domain service stages the results** of its actions into temporary Redis structures (e.g., `tick:pending:{entityId}`)
- Once all actions in a tick batch have completed (or reached timeout), the **Game Session Service finalizes the tick**, applying all staged updates in a single pass
- If any action in the batch fails or times out, the entire staged tick is **discarded or retried**, ensuring consistency

This model allows ticks to execute in parallel across services while preserving **atomicity and deterministic resolution**.

---

## ⏱️ Timers, Countdown Logic, and Time Scaling

While the **tick cycle determines when updates are processed**, **actual durations are tracked using real-world time** rather than tick counts.

- A cooldown might last `5000ms`, not “5 ticks”
- Each tick checks real time against stored timers to decide what to expire or apply

If multiple time intervals have passed since the last tick (e.g. due to a pause, lag, or slow region), **multiple time-based effects are processed together in the next tick**, ensuring consistent game state even when ticks fall behind.

This approach:

- Avoids the need for very high-frequency ticks (e.g., 10ms ticks)
- Enables smooth interaction between low-frequency ticks and high-resolution timing
- Allows consistent game logic even if ticks fluctuate under load

### 🕒 Time Scaling

In many MUDs, **tick speed itself is scaled** to simulate effects like haste, slow, or global world acceleration (e.g. 100 tick cooldown becomes 90 ticks with a 10% speedup). FireMUD instead uses a **time scale factor** applied to all **timer-based mechanics**.

- Each timer (cooldowns, status durations, regen intervals, etc.) is **multiplied by a time scale factor**
- For example, a `5000ms` cooldown with a `0.9` time scale becomes `4500ms`
- This allows speed-ups or slow-downs to be applied:
  - Globally (e.g. “double speed weekend”)
  - Per room (e.g. “time-dilated dungeon”)
  - Per entity (e.g. a haste buff on one player)

This method keeps the **tick system stable and predictable**, while allowing **precise control over gameplay tempo** via timer scaling.

---

## 🧾 Tick Atomicity and Microservice Resilience

Each tick functions as an **atomic boundary for execution and error handling**. Ticks are **not used as full state rollback points**, but rather as **safe units of progress**:

- Services **stage tick results in temporary Redis state**
- Only once all required work is done does the **Game Session Service finalize the tick**
- If a tick fails to complete due to a transient microservice issue (e.g. Entity Service outage), the tick is **retried without committing partial results**

This ensures:

- No half-applied game logic corrupts live state
- Game Session or Game Logic services can pause/resume/resync with Redis and downstream services
- Tick actions can be re-fetched or deferred until all dependencies are reachable

Possible future enhancements:

- Record tick input logs (player commands, AI outputs) for replay or debugging
- Support diff-based snapshots for optional rollback on critical faults
- Isolate room ticks to avoid cascading failure across unrelated gameplay areas

---

## 🧠 Responsibilities by Service

| Service                   | Tick Role                                                                 |
|---------------------------|---------------------------------------------------------------------------|
| **Game Session Service**  | Schedules ticks for connected players; buffers inputs; coordinates finalization |
| **Game Logic Service**    | Executes actions for all entities in tick order; core rules and resolution |
| **Automation & Scripting**| Responds to tick events for active NPCs and scripts; submits actions to be executed |
| **World Management**      | Manages room tick partitioning and dynamic tick region ownership           |
| **Redis**                 | Stores ephemeral runtime state, tick locks, and staged tick state for processing |

---

## 🛡️ Benefits of This Model

- ✅ Prevents race conditions by synchronizing action resolution
- ✅ Avoids over-centralization via region-based tick isolation
- ✅ Balances fairness with real-time input flow
- ✅ Supports scaling up (more tick workers), or sharding across rooms/zones
- ✅ Uses real-time precision for accurate timers and cooldowns
- ✅ Allows speed-altering mechanics without touching tick frequency
- ✅ Keeps game logic consistent and testable even under load
- ✅ Treats ticks as atomic, retry-safe checkpoints for system resilience
- ✅ Supports safe concurrent tick execution through distributed entity locking

---

> FireMUD treats time not as a global clock, but as **parallel pulses across regions**, ensuring that gameplay remains fair, scalable, and immersive — with real-time accuracy, dynamic speed control, and fault isolation built into every tick.
