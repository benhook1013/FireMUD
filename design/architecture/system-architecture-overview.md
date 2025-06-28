# 🏗️ FireMUD System Architecture Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

---

## 🧩 Core Architecture Principles

- **Microservices-based** domain-driven architecture with clearly separated responsibilities  
- **Spring Cloud Gateway** serves as the unified HTTP/WebSocket entry point for all clients  
- **TCP Proxy Service** accepts Telnet connections and upgrades them to WebSocket for the Gateway  
- **Consistent end-to-end WebSocket flow**: Telnet (TCP) → TCP Proxy (WebSocket upgrade) → Spring Cloud Gateway → Game Session Service  
- **All client traffic is routed through the Spring Cloud Gateway**, ensuring centralized **traffic routing, monitoring, and observability**.  
  > 🛑 **Authentication is not performed at the Gateway** — all `LOGIN` handling and session validation occurs in the **Game Session Service**.
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

🖼️ See also: [System Architecture Diagram](./system-architecture/system-architecture-diagram.md)

---

## 🔁 Reconnection Strategy

FireMUD supports multi-layer reconnection handling to ensure gameplay continuity across network interruptions, client restarts, or backend service failures. Each layer contributes to a robust recovery experience:

- **TCP Proxy Service**  
  Manages Telnet input at the raw TCP layer. Input is assembled per character and forwarded as full commands. Input buffers are cleared on disconnect and not retained across sessions.

- **Spring Cloud Gateway**  
  Acts as a stateless WebSocket entry point. Automatically reconnects clients to backend services. Maintains no gameplay or authentication state; simply routes traffic.

- **Game Session Service**  
  Restores gameplay context using Redis-stored session data, rebinds player socket, and resumes participation in ticks and action queues. Supports deterministic recovery of ticks and timers.

> 🔑 Clients must **always re-authenticate** using a `LOGIN` command after disconnect.  
> If the account and character match a previous session, **Game Session may restore** the prior session state from Redis and resume gameplay automatically.  
> Clients do **not** store or reuse tokens — session restoration is purely server-side.
> 🔗 For full reconnection flows, recovery edge cases, and resume vs reload behavior, see [Reconnection Strategy](./system-architecture-reconnection.md)

---

## 🔗 Major Components and Their Roles

| Component                          | Purpose                                                                 |
|-----------------------------------|-------------------------------------------------------------------------|
| **Web Clients**                   | Modern browser clients using WebSocket or HTTP to access the platform  |
| **MUD Clients**                   | Traditional Telnet clients connecting via TCP, proxied into the system |
| **TCP Proxy Service**             | Accepts Telnet connections, buffers input, forwards over WebSocket     |
| **Spring Cloud Gateway**          | Handles WebSocket termination, routing, auth, monitoring                |
| **Game Session Service**          | Manages player sessions, game instance lifecycle, runtime flags, published version state, input command validation, rate limiting, tick region orchestration, and action queue execution |
| **Account Service**               | Manages player accounts, login, auth, subscriptions, and bans          |
| **Entity Management Service**     | Handles all entity data: players, NPCs, items, stats, inventories      |
| **World Management Service**      | Owns the structure and logic of maps, rooms, and tick regions; manages persistent room state |
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
- Redis acts as a **real-time coordination buffer**, not a source of truth — yet it is **critical** for gameplay execution and recovery.  
- **Game configuration is versioned and published via the Game Design Service**, and consumed by runtime services locally.  
- **Design-time feature flags** are defined in the Game Design Service; **runtime flags** are managed in the Game Session Service for temporary overrides.  
- **Logging & Admin Service** provides UI/API tools to view and toggle active flags and audit historical changes.

📤 **Game Configuration Rollout:**  
When a new game version is published by the Game Design Service, the relevant domain services (e.g., Entity, World, Logic) update their PostgreSQL data accordingly. The Game Session Service assigns version IDs to active game instances and notifies participating services when needed — avoiding complex pub-sub for now.

> 🔗 For Redis durability, Lua atomicity, and tick-level logic, see [Redis Architecture](./system-architecture-redis.md) and [Tick System](./system-architecture-ticks.md).

---

## ⏱️ Game Loop / Tick Model

FireMUD uses a **Hybrid Tick Model** that balances real-time responsiveness with deterministic and fair processing of queued actions.

Key design aspects:

- Each **tick region** (typically a room or map segment) runs independently to maximize parallelism and fault isolation
- **One action per entity** is pulled from per-entity queues and resolved in a fair, deterministic order
- **Game Session Service** coordinates the tick lifecycle, lock acquisition, retries, and commit flow
- **Game Logic Service** processes actions using deterministic rules and state from Entity/World services
- Redis is used for **lock acquisition**, **tick staging**, **timer tracking**, and **conflict-safe retries**

> 🔗 For complete execution flow and isolation model, see [Tick System and Runtime Design](./system-architecture-ticks.md)

---

## 🔐 Authentication and Authorization Flow

FireMUD uses a unified authentication model across all client types (Telnet, WebSocket, HTTP), relying on plaintext `LOGIN` commands. All authentication is handled server-side by the **Game Session Service**, and clients never receive or transmit tokens.

### 🧭 Flow Summary

- **Telnet clients** connect via the **TCP Proxy Service**, which upgrades TCP to WebSocket and forwards it to the **Spring Cloud Gateway**
- **Web clients** connect directly to the **Gateway** via WebSocket or HTTP
- The **Spring Cloud Gateway is stateless** and does **not perform authentication** — it simply routes messages
- On receiving `LOGIN`, the **Game Session Service** validates credentials via the **Account Service** and obtains a **backend-only JWT**

### 🔑 Internal Token Handling

- The **initial JWT** represents the **authenticated account** only
- Claims include:
  - `accountId`
  - `roles[]` (e.g. `admin`, `moderator`, `player`)
- Once the player selects a character and world, a new **augmented JWT** is issued including:
  - `playerId`
  - `worldId`
- The updated token allows downstream services to enforce **character-level and world-specific access control**

### ✅ Design Notes

- All services enforce authorization based on claims in the current JWT
- Clients must **explicitly re-authenticate** using `LOGIN` if disconnected
- Game Session is the **trusted authority** on identity, character selection, and access rights
- JWTs are used only within the backend and are **never sent to clients**

> 🔗 See [Authentication & Authorization](./system-architecture-authentication.md) for full login flow, token claims, and session propagation.

---

## 📊 Observability and Monitoring

FireMUD uses a unified observability pipeline:

### 🔍 Logging

- All services log structured JSON to stdout/stderr with metadata like `traceId`, `playerId`, `sessionId`
- Logs are collected via **Fluent Bit** or similar and indexed into **Elasticsearch**
- No logs are written to databases; logging is decoupled and async

### 🧾 Admin & Logging Service

- Provides dashboards and moderation tools using ELK backend
- Does not persist logs; queries indexed data only

### 📈 Metrics and Tracing

- All services export **Prometheus metrics**
- Dashboards track tick latency, retry storms, Redis contention
- **Redis-specific metrics** (e.g., Lua latency, lock failure) are described in [Redis Architecture](./system-architecture-redis.md#📈-observability-and-reliability)
- **OpenTelemetry** spans trace actions end-to-end across tick regions

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

- Functional responsibilities are detailed in the [Responsibility Matrix](./system-architecture/responsibility-matrix.md)  
- Game Session controls runtime instance logic and tick orchestration  
- Game Logic resolves deterministic actions  
- Redis acts as the coordination and execution substrate for ticks and timers

🧠 **Why Game Session vs Game Logic?**  
Game Logic is stateless and deterministic — it resolves a single action given state.  
Game Session manages context, pacing, retries, and tick lifecycle orchestration across distributed regions.

---

## 📚 Related Documentation

- [Tick System and Runtime Design](./system-architecture/system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Microservices Responsibility Matrix](./system-architecture/responsibility-matrix.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Gateway Architecture](./infrastructure/gateway-architecture.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Protocol Bridging](./infrastructure/protocol-bridging.md)
- [System Architecture Diagram](./system-architecture/system-architecture-diagram.md)
