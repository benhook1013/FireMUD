# 🏗️ FireMUD System Architecture Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

---

## 🧩 Core Architecture Principles

- **Microservices-based** domain-driven architecture with clearly separated responsibilities  
- **Spring Cloud Gateway** serves as the unified HTTP/WebSocket entry point for all clients  
- **TCP Proxy Service** accepts Telnet connections and upgrades them to WebSocket for the Gateway  
- **Consistent end-to-end WebSocket flow**: TCP Proxy → Gateway → Game Session Service  
- **All client traffic is routed through the Gateway**, ensuring centralized authentication, monitoring, and routing  
- **Reconnection logic is distributed across layers** to preserve connection integrity and session continuity:  
  - The **TCP Proxy** buffers Telnet input and reconnects to the Gateway when needed  
  - The **Gateway** re-establishes downstream WebSocket connections to backend services  
  - The **Game Session Service** restores gameplay context using external Redis state  
- **Internal microservice communication uses gRPC**, bypassing the Gateway for backend-to-backend calls  
- **Kubernetes DNS and IPVS-based load balancing** provide scalable, resilient service discovery and routing  
- **Session state is externalized (e.g., Redis)** to keep services stateless and allow for graceful reconnection  
- **Game treated as data**, with the Game Design Service enabling live editing and versioning without code deployment  
- **Game Session Service orchestrates live game instances**, including runtime configuration, feature flags, and published version tracking  
- **Feature flags are defined at design-time but toggled at runtime**, enabling temporary or contextual behavior changes without altering the underlying game definition  

---

## 🔁 Reconnection Strategy by Layer

Robust reconnection support is critical for maintaining seamless player experiences across various clients and network conditions. Reconnection responsibilities are intentionally distributed across system layers:

### 🛰️ TCP Proxy Service

- **Manages Telnet TCP connections**  
- Buffers player input to avoid loss during short disconnects  
- Attempts to reconnect to the Spring Cloud Gateway automatically  

### 🌐 Spring Cloud Gateway

- **Maintains persistent WebSocket connections** to the Game Session Service  
- Reconnects to backend session layer transparently if underlying service restarts or connection drops  
- Ensures authenticated context and routing is preserved across reconnects  

### 🎮 Game Session Service

- **Owns gameplay session continuity**  
- Retrieves player session data from Redis upon reconnect  
- Rebinds player socket connection to restored session state  
- Tracks and applies the active published version ID for each running game instance  
- Stores and manages runtime feature flags (e.g., double XP, test mode) which may temporarily override design-time defaults  

Each layer handles the reconnection logic appropriate to its scope, ensuring fault tolerance and a smooth player experience.

---

## 🔗 Major Components and Their Roles

| Component                          | Purpose                                                                 |
|-----------------------------------|-------------------------------------------------------------------------|
| **Web Clients**                   | Modern browser clients using WebSocket or HTTP to access the platform  |
| **MUD Clients**                   | Traditional Telnet clients connecting via TCP, proxied into the system |
| **TCP Proxy Service**             | Accepts Telnet connections, buffers input, forwards over WebSocket     |
| **Spring Cloud Gateway**          | Handles WebSocket termination, routing, auth, monitoring                |
| **Game Session Service**          | Manages player sessions, game instance lifecycle, runtime flags, published version state, input command validation, rate limiting, and action queues |
| **Account Service**               | Manages player accounts, login, auth, subscriptions, and bans          |
| **Entity Management Service**     | Handles all entity data: players, NPCs, items, stats, inventories      |
| **World Management Service**      | Owns the structure and logic of maps, rooms, and pathfinding; also responsible for persistent room state |
| **Game Logic Service**            | Executes command parsing and gameplay mechanics; processes all entity-driven actions including combat, trading, movement, and skill usage |
| **Automation & Scripting Service**| Executes custom scripts and AI that actively trigger functionality in the Game Logic Service or cause entities to take autonomous actions |
| **Social and Groups Service**     | Manages chat, mail, guilds, and player-driven social systems. Also includes player presence, friend/block lists, and social graphs, enabling dynamic player interactions, group discovery, and social filtering mechanisms |
| **Logging & Admin Service**       | Hosts admin tools, metrics, moderation policies, audit logging, and feature flag toggling interfaces |
| **Game Design Service**           | Passive authoring tool for creating and publishing game data, configurations, and default flag definitions |

---

## 🌐 Communication Flows

| Flow                                        | Protocol                       |
|---------------------------------------------|--------------------------------|
| Web Clients → Spring Cloud Gateway          | WebSocket (wss) / HTTP (https) |
| MUD Clients → TCP Proxy Service             | Raw TCP (Telnet)               |
| TCP Proxy Service → Spring Cloud Gateway    | WebSocket (wss)                |
| Spring Cloud Gateway → Game Session Service | WebSocket (wss)                |
| Game Session Service → Other Microservices  | gRPC (internal)                |

✅ All internal communication uses **gRPC** with strict schema enforcement and minimal latency overhead.

---

## 📦 Data and State Management

- **Persistent data** (accounts, entities, world data including rooms) is owned by domain-aligned services with dedicated PostgreSQL databases.  
- **Volatile state** (player sessions, transient gameplay state) is stored in Redis by the Game Session Service.  
- **Game configuration is versioned and published via the Game Design Service**, and consumed by runtime services locally.  
- All services remain **stateless**, promoting scalability and resilience in failover scenarios.  
- **Design-time feature flags** are defined and versioned within the Game Design Service.  
- **Live runtime flags** are managed in the Game Session Service, enabling temporary overrides of published defaults without requiring a new design publish.  
- **Logging & Admin Service** provides UI/API tools to view and toggle active flags during gameplay and audit historical changes.  

### 🧠 Redis Scalability

- Redis is used for volatile state across sessions and runtime data, including player session context and ephemeral gameplay state.  
- Redis clustering, partitioning, and key namespacing should be employed to handle high cardinality and throughput.  
- ❗**Key Design Note**: Avoid Redis key bloat by using **structured and namespaced keys** (e.g., `session:{playerId}`, `room:{roomId}:occupants`) instead of dynamically generated long keys.  
  This approach:
  - Keeps memory usage predictable
  - Makes it easier to scan/query related keys
  - Prevents clutter and performance issues from overly dynamic or nested keys  

---

## ⏱️ Tick System and Runtime Flow

FireMUD employs a **Hybrid Tick Model (Model C)** to balance real-time responsiveness with deterministic, fair action resolution. In this model:

- **Player inputs are received immediately**, rate-limited, and added to per-session command queues
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

### 🌍 Room-Based Ticked Regions

Ticks are **not globally synchronized across the entire game world**. Instead, FireMUD uses **region- or room-scoped tick zones**. Each room or small area operates on its own tick cycle, enabling:

- **Scalability**: multiple regions tick independently across threads or servers
- **Fault isolation**: computationally expensive actions (e.g. large combat) in one room do not block or delay updates in another
- **Flexibility**: different regions can operate at different tick rates depending on content (e.g., slow-paced puzzle room vs fast-paced combat arena)

This model encourages sharded game loop execution and avoids global locks or cascading lag.

---

### 🔄 Tick Execution Model

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
   - Regeneration, environmental effects, room-wide events, scripts
   - AI decisions and queued behaviors may generate new actions

---

### 🔐 Cross-Tick Entity Ownership and Locking

Since tick regions operate independently, it is possible for multiple ticks to target the same entity (e.g., a player in one room and their pet in another). To prevent concurrent updates to shared entities, FireMUD uses a **distributed lock-based ownership model**.

Before a domain service processes an action affecting an entity, it must **acquire a lock** on that entity:

- Locks are stored in Redis using namespaced keys like `tick:lock:{entityId}`.
- Acquired using `SET NX PX` semantics to ensure:
  - **Only one tick region owns an entity at a time**
  - Locks automatically expire (e.g. after 1 second) to prevent deadlock

If a lock cannot be acquired:

- The domain service **skips or defers** the action until a future tick
- Game Session may optionally be notified to prioritize deferred actions

This ensures entity updates are **serialized across tick regions**, enabling **safe asynchronous execution** of tick batches while preventing race conditions across the distributed system.

---

### ⏱️ Timers, Countdown Logic, and Time Scaling

While the **tick cycle determines when updates are processed**, **actual durations are tracked using real-world time** rather than tick counts.

- A cooldown might last `5000ms`, not “5 ticks”
- Each tick checks real time against stored timers to decide what to expire or apply

If multiple time intervals have passed since the last tick (e.g. due to a pause, lag, or slow region), **multiple time-based effects are processed together in the next tick**, ensuring consistent game state even when ticks fall behind.

This approach:

- Avoids the need for very high-frequency ticks (e.g., 10ms ticks)
- Enables smooth interaction between low-frequency ticks and high-resolution timing
- Allows consistent game logic even if ticks fluctuate under load

#### 🕒 Time Scaling

In many MUDs, **tick speed itself is scaled** to simulate effects like haste, slow, or global world acceleration (e.g. 100 tick cooldown becomes 90 ticks with a 10% speedup). FireMUD instead uses a **time scale factor** applied to all **timer-based mechanics**.

- Each timer (cooldowns, status durations, regen intervals, etc.) is **multiplied by a time scale factor**
- For example, a `5000ms` cooldown with a `0.9` time scale becomes `4500ms`
- This allows speed-ups or slow-downs to be applied:
  - Globally (e.g. “double speed weekend”)
  - Per room (e.g. “time-dilated dungeon”)
  - Per entity (e.g. a haste buff on one player)

This method keeps the **tick system stable and predictable**, while allowing **precise control over gameplay tempo** via timer scaling.

---

### 🧾 Tick Atomicity and Microservice Resilience

Each tick also functions as an **atomic boundary for execution and error handling**. Ticks are **not used as full state rollback points**, but rather as **safe units of progress**: if a tick fails to complete due to a transient microservice issue (e.g. Entity Service outage), the tick may be retried without committing partial results.

This model ensures:

- No half-applied game logic corrupts live state
- Game Session or Game Logic services can pause/resume/resync with Redis and downstream services
- Tick actions can be re-fetched or deferred until all dependencies are reachable

Possible future enhancements:

- Record tick input logs (player commands, AI outputs) for replay or debugging
- Support diff-based snapshots for optional rollback on critical faults
- Isolate room ticks to avoid cascading failure across unrelated gameplay areas

This design provides a **clear, deterministic boundary for consistency**, while preserving service-level resilience in a distributed system.

---

### 🧠 Responsibilities by Service

| Service                   | Tick Role                                                                 |
|---------------------------|---------------------------------------------------------------------------|
| **Game Session Service**  | Schedules ticks for connected players; buffers inputs; coordinates finalization |
| **Game Logic Service**    | Executes actions for all entities in tick order; core rules and resolution |
| **Automation & Scripting**| Responds to tick events for active NPCs and scripts; submits actions to be executed |
| **World Management**      | Manages room tick partitioning and dynamic tick region ownership           |
| **Redis**                 | Stores ephemeral runtime state, tick locks, and staged tick state for processing |

---

### 🛡️ Benefits of This Model

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

---

## 🗂️ Deployment Layers

| Layer                  | Technology                                                   |
|------------------------|--------------------------------------------------------------|
| Client Layer           | Browser, Telnet MUD Clients                                  |
| Proxy Layer            | TCP Proxy Service (LoadBalancer Service)                     |
| API Gateway Layer      | Spring Cloud Gateway (LoadBalancer Service)                  |
| Gameplay Session Layer | Game Session Service                                         |
| Service Layer          | Microservices (Account, Entity, World, Logic, etc.)          |
| Infrastructure Layer   | Kubernetes with IPVS, Docker Compose (for local development) |

---

## 📚 Related Documentation

- [Microservices Responsibility Matrix](./responsibility-matrix.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Gateway Architecture](./infrastructure/gateway-architecture.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Protocol Bridging](./infrastructure/protocol-bridging.md)

---

## 🔎 Notes on Responsibility Alignment

- Functional responsibilities for each service are centralized in the [Responsibility Matrix](./responsibility-matrix.md) and referenced implicitly here.  
- This architecture overview focuses on runtime behavior and structural composition. Refer to the matrix for a granular breakdown of what each service handles.  
- Game instance control and runtime state (version, flags) are owned by the Game Session Service, while design and configuration versioning is authored and published via the Game Design Service.  
- Combat, trading, and all other player or NPC-initiated actions are handled via the **Game Logic Service**, based on data retrieved from the Entity and World services and commands triggered by users or scripts.  
- Scripts and AI behaviors are executed via the **Automation & Scripting Service**, which may drive entities or initiate actions in the game world through the Game Logic Service.  
- **Input Command Execution Flow**:  
  1. Player input is received by the Game Session Service.  
  2. Basic rate limiting and format validation occurs.  
  3. Valid commands are forwarded to the Game Logic Service.  
  4. Game Logic resolves mechanics, using Entity/World services for state.  
  5. Effects and results are applied and optionally persisted.  
