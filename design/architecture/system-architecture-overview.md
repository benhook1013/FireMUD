# 🏗️ FireMUD System Architecture Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

---

## 🧩 Core Architecture Principles

- **Microservices-based** domain-driven architecture with clearly separated responsibilities  
- **Spring Cloud Gateway** serves as the unified HTTP/WebSocket entry point for all clients  
- **TCP Proxy Service** accepts Telnet connections and upgrades them to WebSocket for the Gateway  
- **Consistent end-to-end WebSocket flow**: Telnet (TCP) → TCP Proxy (WebSocket upgrade) → Spring Cloud Gateway → Game Session Service  
- **All client traffic is routed through the Spring Cloud Gateway**, ensuring centralized authentication, monitoring, and routing  
- **Spring Cloud Gateway is fully horizontally scalable and stateless**, with no sticky session requirements. Game sessions are stored externally, allowing any Gateway instance to serve any authenticated client.  
- **Telnet clients maintain sticky TCP connections only to the TCP Proxy**, which buffers input and handles reconnects. Once upgraded to WebSocket, traffic flows through stateless layers — allowing transparent failover and reconnection.  
- **Reconnection logic is distributed across layers** to preserve connection integrity and session continuity:  
  - The **TCP Proxy** buffers Telnet input and reconnects to the Gateway when needed  
  - The **Spring Cloud Gateway** re-establishes downstream WebSocket connections to backend services  
  - The **Game Session Service** restores gameplay context using external Redis state  
- **All internal service-to-service communication from the Game Session Service onward uses gRPC**, with strict schema enforcement and low latency  
- **Kubernetes DNS and IPVS-based load balancing** provide scalable, resilient service discovery and routing  
- **Session state is externalized (e.g., Redis Cluster)** to keep services stateless and allow for graceful reconnection  
- **Game definitions and rules are data-driven and editable via tooling without redeploying code**, with the Game Design Service enabling live editing and versioning  
- **Game Session Service orchestrates live game instances**, including runtime configuration, feature flags, published version tracking, and tick execution  
- **Feature flags are defined at design-time in the Game Design Service and toggled at runtime by the Game Session Service**, enabling temporary or contextual behavior changes without altering the underlying game definition  

🖼️ See also: [System Architecture Diagram](./system-architecture/system-architecture-diagram.md) for a visual representation of these components and flows.

---

## 🔁 Reconnection Strategy by Layer

Robust reconnection support is critical for maintaining seamless player experiences across various clients and network conditions. Reconnection responsibilities are intentionally distributed across system layers:

### 🛰️ TCP Proxy Service

- **Manages Telnet TCP connections**  
- Buffers player input to avoid loss during short disconnects  
- Attempts to reconnect to the Spring Cloud Gateway automatically  

### 🌐 Spring Cloud Gateway

- **Maintains persistent WebSocket connections** to the Game Session Service  
- Reconnects to backend session layer transparently if the underlying service restarts or a connection drops  
- Ensures authenticated context and routing are preserved across reconnects  

### 🎮 Game Session Service

- **Owns gameplay session continuity**  
- Retrieves player session data from Redis upon reconnect  
- Binds newly reconnected socket to a recovered gameplay context  
- Tracks and applies the active published version ID for each running game instance  
- Stores and manages runtime feature flags (e.g. double XP, test mode) which may temporarily override design-time defaults  
- **Resumes or replays in-progress tick cycles** after restart or crash, using Redis-backed durability guarantees  

Each layer handles reconnection logic appropriate to its scope, ensuring fault tolerance and a smooth player experience.

---

## 🔗 Major Components and Their Roles

| Component                          | Purpose                                                                 |
|-----------------------------------|-------------------------------------------------------------------------|
| **Web Clients**                   | Modern browser clients using WebSocket or HTTP to access the platform  |
| **MUD Clients**                   | Traditional Telnet clients connecting via TCP, proxied into the system |
| **TCP Proxy Service**             | Accepts Telnet connections, buffers input, forwards over WebSocket     |
| **Spring Cloud Gateway**          | Handles WebSocket termination, routing, auth, monitoring                |
| **Game Session Service**          | Manages player sessions, game instance lifecycle, runtime flags, published version state, input command validation, rate limiting, tick execution, and action queues |
| **Account Service**               | Manages player accounts, login, auth, subscriptions, and bans          |
| **Entity Management Service**     | Handles all entity data: players, NPCs, items, stats, inventories      |
| **World Management Service**      | Owns the structure and logic of maps, rooms, and pathfinding; also responsible for persistent room state |
| **Game Logic Service**            | Executes command parsing and gameplay mechanics; resolves queued actions deterministically |
| **Automation & Scripting Service**| Executes custom scripts and AI that actively trigger functionality in the Game Logic Service or cause entities to take autonomous actions |
| **Social and Groups Service**     | Manages chat, mail, guilds, and player-driven social systems           |
| **Logging & Admin Service**       | Hosts admin tools, metrics, moderation policies, audit logging query UI |
| **Game Design Service**           | Passive authoring tool for creating and publishing game data           |

---

## 🌐 Communication Flows

| Flow                                        | Protocol                       |
|---------------------------------------------|--------------------------------|
| Web Clients → Spring Cloud Gateway          | WebSocket (wss) / HTTP (https) |
| MUD Clients → TCP Proxy Service             | Raw TCP (Telnet)               |
| TCP Proxy Service → Spring Cloud Gateway    | WebSocket (wss)                |
| Spring Cloud Gateway → Game Session Service | WebSocket (wss)                |
| Game Session Service → Other Microservices  | gRPC (internal)                |

✅ All internal communication between services from the Game Session Service onward uses **gRPC** with strict schema enforcement and minimal latency overhead.

---

## 📦 Data and State Management

- **Persistent data** (accounts, entities, world data including rooms) is owned by domain-aligned services with dedicated PostgreSQL databases.  
- **Volatile state** (player sessions, transient gameplay state, ticks) is externalized to Redis Cluster and managed by the Game Session Service.  
- Redis is treated as a **transient coordination layer**, enabling scalable reconnection, concurrent tick execution, and in-flight action tracking.  
- **Game configuration is versioned and published via the Game Design Service**, and consumed by runtime services locally.  
- **Design-time feature flags** are defined in the Game Design Service; **runtime flags** are managed in the Game Session Service for temporary overrides.  
- **Logging & Admin Service** provides UI/API tools to view and toggle active flags and audit historical changes.

📤 **Game Configuration Rollout:**  
When a new game version is published by the Game Design Service, the relevant domain services (e.g., Entity, World, Logic) update their PostgreSQL data accordingly. The Game Session Service assigns version IDs to active game instances and notifies participating services when needed — avoiding complex pub-sub for now.

> 🔗 For Redis durability, Lua atomicity, and key discipline, see [Redis Architecture](./system-architecture-redis.md).

---

## ⏱️ Game Loop / Tick Model

FireMUD uses a **Hybrid Tick Model** that balances real-time responsiveness with deterministic and fair processing of queued actions.

Key design aspects:

- Each **tick region** (e.g., room or map segment) runs independently to maximize parallelism.
- **Player and NPC actions are pulled from per-entity queues** and resolved at a consistent interval.
- **Game Session Service** coordinates the tick lifecycle and delegates logic resolution to the **Game Logic Service**.
- Tick safety is enforced via **Redis-based locking and state staging**, allowing resilient recovery and retry logic.

> 🔗 For full execution semantics, isolation guarantees, and rollback strategy, see [Tick System and Runtime Design](./system-architecture/system-architecture-ticks.md).

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

- Metrics are exported via **Prometheus-compatible `/metrics` endpoints**.
- **Grafana dashboards** provide visibility into tick latency, action queue depth, Redis contention, etc.
- **Redis metrics** (e.g., lock contention, Lua latency) are exported via Prometheus and visualized in Grafana for hotspot detection and tick health.
- Tracing via **OpenTelemetry** enables end-to-end action flow debugging across ticks and services.

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

- Functional responsibilities for each service are centralized in the [Responsibility Matrix](./system-architecture/responsibility-matrix.md) and referenced implicitly here.  
- This architecture overview focuses on runtime behavior and structural composition. Refer to the matrix for a granular breakdown of what each service handles.  
- Game instance control and runtime state (version, flags) are owned by the Game Session Service, while design and configuration versioning is authored and published via the Game Design Service.  
- Combat, trading, and all other player or NPC-initiated actions are handled via the **Game Logic Service**, based on state and data retrieved from the Entity and World services. During each tick, the **Game Session Service dequeues actions** from session command queues and invokes the **Game Logic Service** to process them.  
  The Game Logic Service:
  - Retrieves required state from the Entity and World services (e.g., room layout, entity stats)
  - Applies deterministic gameplay rules to resolve the action
  - Returns updated game state transitions  
- Scripts and AI behaviors are executed via the **Automation & Scripting Service**, which may inject commands into queues or trigger autonomous behavior through the Game Logic Service.

🧠 **Why Game Session vs Game Logic?**  
The Game Logic Service acts as a deterministic engine — it processes commands based on inputs and state but doesn’t manage session-specific concerns.  
The Game Session Service owns the player’s session context, tick execution, command queuing, and lock coordination.

---

## 📚 Related Documentation

- [Tick System and Runtime Design](./system-architecture/system-architecture-ticks.md)
- [Redis Architecture](./system-architecture/system-architecture-redis.md)
- [Microservices Responsibility Matrix](./system-architecture/responsibility-matrix.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Gateway Architecture](./infrastructure/gateway-architecture.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Protocol Bridging](./infrastructure/protocol-bridging.md)
- [System Architecture Diagram](./system-architecture/system-architecture-diagram.md)
