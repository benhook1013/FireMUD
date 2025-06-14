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
