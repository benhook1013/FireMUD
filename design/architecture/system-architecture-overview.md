# 🏗️ FireMUD System Architecture Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

---

## 🧩 Core Architecture Principles

- **Microservices-based** domain-driven architecture with clearly separated responsibilities  
- **Spring Cloud Gateway** serves as the unified HTTP/WebSocket entry point for all clients  
- **TCP Proxy Service** accepts Telnet connections and upgrades them to WebSocket for the Gateway  
- **Consistent end-to-end WebSocket flow**: TCP Proxy → Gateway → Game Session Service  
- **All client traffic is routed through the Gateway**, ensuring centralized authentication, monitoring, and routing  
- **Spring Cloud Gateway is fully horizontally scalable and stateless**, with no sticky session requirements. Game sessions are stored externally, allowing any Gateway instance to serve any authenticated client.  
- **Telnet clients maintain sticky TCP connections only to the TCP Proxy**, which buffers input and handles reconnects. Once upgraded to WebSocket, traffic flows through stateless layers — allowing transparent failover and reconnection.  
- **Reconnection logic is distributed across layers** to preserve connection integrity and session continuity:  
  - The **TCP Proxy** buffers Telnet input and reconnects to the Gateway when needed  
  - The **Gateway** re-establishes downstream WebSocket connections to backend services  
  - The **Game Session Service** restores gameplay context using external Redis state  
- **Internal microservice communication uses direct backend-to-backend gRPC communication (excluding the Gateway for internal traffic)**  
- **Kubernetes DNS and IPVS-based load balancing** provide scalable, resilient service discovery and routing  
- **Session state is externalized (e.g., Redis)** to keep services stateless and allow for graceful reconnection  
- **Game definitions and rules are data-driven and editable via tooling without redeploying code**, with the Game Design Service enabling live editing and versioning  
- **Game Session Service orchestrates live game instances**, including runtime configuration, feature flags, and published version tracking  
- **Feature flags are defined at design-time but toggled at runtime**, enabling temporary or contextual behavior changes without altering the underlying game definition  

🖼️ See also: [System Architecture Diagram](./system-architecture-diagram.md) for a visual representation of these components and flows.

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
- Binds newly reconnected socket to a recovered gameplay context  
- Tracks and applies the active published version ID for each running game instance  
- Stores and manages runtime feature flags (e.g. double XP, test mode) which may temporarily override design-time defaults  

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
- **Design-time feature flags** are defined and versioned within the Game Design Service.  
- **Live runtime flags** are managed in the Game Session Service, enabling temporary overrides of published defaults without requiring a new design publish.  
- **Logging & Admin Service** provides UI/API tools to view and toggle active flags during gameplay and audit historical changes.  

📤 **Game Configuration Rollout:**  
When a new game version is published by the Game Design Service, the relevant domain services update their internal PostgreSQL data for that version (e.g. updated world, entities, commands). The Game Session Service tracks and assigns version IDs to active game instances and notifies participating services of version changes at runtime when needed — avoiding complex pub-sub requirements for now.

⚠️ **Redis Volatility Note:**  
Redis is only used for **transient, non-authoritative data** (e.g., session context, in-flight actions, volatile effects).  
All canonical player data, stats, inventories, and world definitions are stored in PostgreSQL within the appropriate domain services.

### 🧠 Redis Scalability

- Redis is used for volatile state across sessions and runtime data, including player session context and ephemeral gameplay state.  
- Redis clustering, partitioning, and key namespacing should be employed to handle high cardinality and throughput.  
- ❗**Key Design Note**: Avoid Redis key bloat by using **structured and namespaced keys** (e.g., `session:{playerId}`, `room:{roomId}:occupants`) instead of dynamically generated long keys.  

---

## ⏱️ Game Loop / Tick Model

FireMUD uses a **Hybrid Tick Model** that combines real-time responsiveness with fair, deterministic processing of entity actions. Player inputs are queued immediately, and each tick interval selects and resolves one action per entity in a consistent order.

### ⚙️ Stateless Tick Execution Model

FireMUD's tick system is built around a **stateless execution model**, where all volatile game state (rooms, entities, cooldowns, effects, etc.) resides in **Redis**, allowing any service instance to execute a tick for any room or area without needing persistent ownership.

Each tick cycle:

- Retrieves relevant state from Redis  
- Executes game logic to process actions  
- Writes updated state back to Redis atomically  

To avoid race conditions or double-processing:

- **Concurrency control is enforced directly inside Redis** using **Lua scripts** that execute atomically.  
- These scripts:
  - Acquire short-lived tick locks per room  
  - Ensure only one worker can begin execution at a time  
  - Safely release locks or validate ownership before unlocking  

⚠️ **Redis provides single-threaded, atomic Lua execution**, which guarantees safety for distributed tick workers.

This design enables:

- **Elastic scalability** with no fixed ownership  
- **Robust fault tolerance** with no risk of state loss  
- **Simplified orchestration** with no sticky routing or sharded tick managers

---

### 📈 Future: Room Sharding and Ownership

As concurrency demands increase, FireMUD’s tick system may evolve to include **runtime sharding and region ownership**, where services take exclusive responsibility for a subset of rooms or zones for the duration of their activity.

Potential benefits include:

- **Local in-memory caching** of hot state to reduce Redis overhead  
- **Predictable locality and affinity**, improving scheduling efficiency  
- **Scalable throughput** in dense or high-interaction areas  

Such a model would use Redis-backed leases or lightweight elections to coordinate shard ownership, with automatic failover and rebalancing as needed. This path remains fully compatible with the stateless foundation, enabling phased adoption without architectural overhaul.

📄 A separate document titled **[Tick System and Runtime Design](./system-architecture-ticks.md)** provides full details on tick cycle orchestration, inter-region locking, timer scaling, and tick safety mechanisms.

---

## 🔐 Authentication and Authorization Flow

FireMUD supports multiple client types (Telnet, WebSocket, HTTP) and provides a consistent authentication model using the **Spring Cloud Gateway** as the single point of entry and verification.

### 🧭 Authentication Entry Points

- **Web Clients (WebSocket or HTTP)** connect directly to the **Spring Cloud Gateway**, which authenticates the user and establishes a persistent session context.
- **Traditional MUD Clients (Telnet)** connect via the **TCP Proxy Service**, which upgrades the raw TCP stream to WebSocket and forwards it to the Gateway. The Gateway authenticates this stream identically to Web clients.

> ✅ All client traffic flows through the Spring Cloud Gateway, which acts as a secure funnel to downstream services.

### 🔑 Token Format

- FireMUD uses **JWTs (JSON Web Tokens)** to represent authenticated *accounts*, not individual characters.
- JWTs are issued by the **Account Service** upon successful login and contain signed claims identifying the account and its access rights.
- Typical claims include:
  - `accountId` – Unique identity for the user account
  - `roles` – Account-level roles (e.g., `user`, `admin`, `moderator`)
  - `tenants[]` – List of game worlds the account can access
  - `features[]` – Optional account-level feature flags
- Player and world context are **not embedded** in the JWT; they are selected post-login during game session setup.

### 🧠 Session Handling

- After authentication, the **Spring Cloud Gateway** passes the validated JWT to the **Game Session Service**.
- The **Game Session Service stores the JWT** as part of the account session context.
- When a player selects a character within a world, this state is tracked independently of the JWT.
- On each command or action:
  - The session-scope `playerId` and `worldId` are resolved
  - The stored JWT is used to validate the originating account and check applicable permissions
  - The combined context is passed downstream (e.g., to Game Logic Service)

> 🛑 Backend services trust the Game Session Service to represent authenticated users and their selected in-game identity. They do not revalidate JWTs.

### 🛂 Authorization and Roles

- The `roles` claim determines whether an account has elevated access to admin tools, game management APIs, or moderator commands.
- Services like the Game Session Service and Admin & Logging Service use roles to control feature exposure.
- Enforcement is performed **locally per service**, based on the decoded claims in the JWT.

---

## 📊 Observability and Monitoring

FireMUD adopts a unified observability strategy built on **centralized logging to the ELK stack**. All logs — including debug output, error reports, gameplay events, and auditable admin actions — are emitted by services and routed to Elasticsearch via standard log collection agents.

### 🔍 Logging

- **All services log directly to stdout/stderr**, emitting structured JSON logs enriched with metadata such as `traceId`, `playerId`, `sessionId`, and `serviceName`.
- Host-level agents (e.g., **Fluent Bit**, **Filebeat**, or similar) collect and forward these logs to a centralized **Elasticsearch** cluster.
- **No logs are stored in service-local databases.** All logging is out-of-band, and services are not aware of or coupled to any logging consumers.
- Once logs are forwarded and indexed by ELK, they are considered persistently recorded and queryable.

> 🛑 No services call the Admin & Logging Service to log. Logging is asynchronous and decoupled from core business logic.

### 🧾 Admin & Logging Service

- Provides UI/API access to search, filter, and review logs via the ELK backend (e.g., using Kibana or OpenSearch Dashboards).
- Supports advanced filtering (e.g., by player, session, room, command) and moderation workflows using centralized log data.
- Does **not persist or modify logs** — it only queries what’s already stored in ELK.

### 📈 Metrics and Tracing

- Application metrics are exported via **Prometheus-compatible `/metrics` endpoints** and scraped centrally.
- **Grafana** dashboards visualize gameplay performance (tick latency, player load, Redis ops, etc.).
- Distributed tracing (via **OpenTelemetry**) allows end-to-end command lifecycle tracking, with optional integration into **Jaeger** or **Tempo**.

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

## 🔎 Notes on Responsibility Alignment

- Functional responsibilities for each service are centralized in the [Responsibility Matrix](./responsibility-matrix.md) and referenced implicitly here.  
- This architecture overview focuses on runtime behavior and structural composition. Refer to the matrix for a granular breakdown of what each service handles.  
- Game instance control and runtime state (version, flags) are owned by the Game Session Service, while design and configuration versioning is authored and published via the Game Design Service.  
- Combat, trading, and all other player or NPC-initiated actions are handled via the **Game Logic Service**, based on data retrieved from the Entity and World services and commands triggered by users or scripts.  
- Scripts and AI behaviors are executed via the **Automation & Scripting Service**, which may drive entities or initiate actions in the game world through the Game Logic Service.  

🧠 **Why Game Session vs Game Logic?**  
The Game Logic Service acts as a deterministic engine — it processes commands based on inputs and state but doesn’t manage session-specific concerns.  
The Game Session Service, in contrast, owns the player’s session context and command queue. It’s the appropriate layer for input validation, rate limiting, and controlling command submission to the logic layer.

---

## 📚 Related Documentation

- [Microservices Responsibility Matrix](./responsibility-matrix.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Gateway Architecture](./infrastructure/gateway-architecture.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Protocol Bridging](./infrastructure/protocol-bridging.md)
- [System Architecture Diagram](./system-architecture-diagram.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
