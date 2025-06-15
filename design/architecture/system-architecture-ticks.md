# ⏱️ Tick System and Runtime Flow

FireMUD employs a **Hybrid Tick Model (Model C)** to balance real-time responsiveness with deterministic, fair action resolution. In this model:

- **Player inputs are accepted in real-time**, rate-limited, and queued in per-session command buffers
- At regular **tick intervals** (e.g., 1s), the system:
  - Pulls one action (if any) from each entity's queue
  - Resolves them in a consistent, fair order
  - Applies all resulting state changes in a single, coordinated pass

This approach provides:

- A **responsive feel** to players
- Deterministic conflict resolution (e.g., who picks up an item, interrupting spells)
- Equal opportunity for AI and player-controlled entities
- Tick-driven scheduling for cooldowns, buffs, environment updates, patrols, and status effects

---

## 🌍 Room-Based Ticked Regions

Ticks are **not globally synchronized across the entire game world**. Instead, FireMUD uses **region- or room-scoped tick zones**. Each room or small area operates on its own tick cycle, enabling:

- **Scalability**: multiple regions tick independently across threads or servers
- **Fault isolation**: expensive operations (e.g., large-scale combat) in one room do not block others
- **Flexibility**: regions can tick at different frequencies based on design needs (e.g., slow-paced puzzles vs fast combat)

This model supports concurrent and isolated execution across game regions, promoting efficient resource usage.

---

## 🔄 Tick Execution Model

Each tick (per region/room):

1. **Collect Actions**  
   From the command queues of all active entities in the region (players, NPCs, AI scripts)

2. **Resolve Fairly**  
   - Ordering may use stats (initiative, speed), timestamps, or priority flags
   - By default, only one action per entity is processed per tick (configurable)

3. **Apply Effects**  
   - Updates to entity state (HP, status, cooldowns)
   - Position, inventory, and skill effects

4. **Trigger Events**  
   - Environmental effects, regeneration, scripted room events
   - AI decisions or reactions, which may enqueue new actions

---

## 🔐 Cross-Tick Entity Ownership and Locking

Tick regions operate in parallel, so **multiple ticks may attempt to affect the same entity** (e.g., a shared NPC or pet). To avoid conflicting updates, FireMUD uses a **distributed entity locking model**:

- Domain services **must acquire a lock** before applying tick effects to an entity
- Lock acquisition happens **during tick execution**, enabling asynchronous batch submission
- Locks are stored in Redis using namespaced keys like `tick:lock:{entityId}`
- Locks use `SET NX PX` to ensure:
  - **Exclusive ownership per tick region**
  - Automatic expiry (e.g., 1s) to prevent deadlocks

If a lock cannot be acquired:

- The affected action is **skipped or deferred**
- The Game Session Service may reprioritize deferred actions in future ticks

This strategy ensures that all ticked state updates are **serialized and safe**, enabling **true parallelism** without sacrificing consistency.

---

## 🧮 Tick Commitment Model

To ensure **consistency and rollback safety**, domain services **do not apply changes directly** during action resolution. Instead:

- Each domain service **stages its intended changes** in temporary Redis structures (e.g., `tick:pending:{entityId}`)
- Once all actions in a tick batch complete (or timeout), the **Game Session Service finalizes the tick** by applying all staged updates to live state
- If any action fails or is unreachable:
  - The tick is **aborted and retried** later
  - No partial updates are committed

This guarantees **atomicity and isolation** for each tick, while supporting safe retries and fault recovery.

---

## ⏱️ Timers, Countdown Logic, and Time Scaling

While ticks determine **when** updates are evaluated, the **actual duration of effects** is tracked in real-world time:

- Cooldowns, durations, and countdowns are measured in milliseconds (e.g., `5000ms`)
- Ticks **check timers** each cycle and process effects whose expiration has elapsed
- If time gaps occur (e.g., due to lag or pause), **multiple effects may be processed together** in the next tick

This model provides:

- Smooth time-accurate behavior even under low tick frequencies
- Reduced CPU cost by avoiding ultra-high-frequency ticking
- Graceful recovery when ticks fall behind

### 🕒 Time Scaling

Rather than scaling tick frequency (which affects the entire system), FireMUD supports **per-effect time scaling**:

- Each timer is multiplied by a **time scale factor**
  - Example: A `5000ms` timer with a scale of `0.9` becomes `4500ms`
- Time scaling can apply at various scopes:
  - **Global** (e.g., “double speed weekend”)
  - **Per-region** (e.g., “time-dilated dungeon”)
  - **Per-entity** (e.g., speed buff or slow debuff)

This provides **fine-grained control** over pacing without risking system-wide instability or timer misalignment.

---

## 🧾 Tick Atomicity and Microservice Resilience

Ticks form a **transactional unit of execution** across distributed services. They act as **atomic checkpoints** rather than full rollback boundaries:

- Tick logic is staged and committed only once all steps succeed
- If a tick cannot be finalized (e.g., due to a failing service), it is **retried without corrupting state**

This provides:

- Guaranteed **consistency** even under partial failure
- Clear **error isolation** (only the current tick is affected)
- The ability to **resync, resubmit, or delay** ticks until dependencies are healthy

### Future Considerations

- Action replay logs for debugging or deterministic simulation
- Optional diff-based snapshots for rollback on severe faults
- Tick "quarantine" to prevent a faulty region from affecting global systems

---

## 🧠 Responsibilities by Service

| Service                   | Tick Role                                                                 |
|---------------------------|---------------------------------------------------------------------------|
| **Game Session Service**  | Orchestrates ticks, buffers input, and finalizes tick commitment          |
| **Game Logic Service**    | Executes per-entity actions in tick order and resolves effects            |
| **Automation & Scripting**| Triggers NPC behaviors and scripted logic based on tick events            |
| **World Management**      | Defines tick regions and manages tick distribution and ownership          |
| **Redis**                 | Stores locks, timers, and staged results for pending ticks                |

---

## 🛡️ Benefits of This Model

- ✅ Prevents race conditions with distributed locking
- ✅ Shards logic via region-based ticks for better scalability
- ✅ Balances fairness, responsiveness, and determinism
- ✅ Cleanly isolates tick faults from wider system impact
- ✅ Uses real-time precision for accurate mechanics
- ✅ Supports time manipulation via scaling instead of frequency
- ✅ Promotes parallelization while keeping logic testable
- ✅ Ensures atomicity and resilience without needing full rollbacks

---

> FireMUD treats time not as a single global clock, but as **parallel pulses** across localized gameplay regions. Each tick is a synchronized beat that ensures fair, flexible, and fault-tolerant gameplay — no matter how chaotic the world becomes.
