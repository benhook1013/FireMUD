# 🏗️ FireMUD System Architecture: Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

---

## 🧩 Core Architecture Principles

- **Microservices-based** domain-driven architecture with clearly separated responsibilities
- **Spring Cloud Gateway** serves as the unified HTTP/WebSocket entry point for all clients
- **TCP Proxy Service** accepts Telnet connections and upgrades them to WebSocket for the Gateway
- **Consistent end-to-end WebSocket flow**: Telnet (TCP) → TCP Proxy Service (WebSocket upgrade) → Spring Cloud Gateway → Game Session Service
- **All client traffic is routed through the Spring Cloud Gateway**, ensuring centralized **traffic routing, monitoring, and observability**. See [Gateway Architecture](./system-architecture-gateway.md) for deployment details and stateless behavior.
   > 🛑 **Gameplay login is handled by the Game Session Service** — the Gateway simply forwards any admin tokens. JWTs are validated by the admin or logging services themselves; gameplay clients connect without tokens. See [Authentication & Authorization](./system-architecture-authentication.md#-login-and-session-flow) for the full login flow.
- **Telnet clients maintain sticky TCP connections only to the TCP Proxy Service**, which buffers **active input**, but **discards it across reconnects**
- **Reconnection logic is handled in layers** to preserve gameplay continuity
- **All internal service-to-service communication from the Game Session Service onward uses gRPC**, with strict schema enforcement and low latency. All calls are encrypted with **mutual TLS**; see [Security Architecture](./system-architecture-security.md)
- **Session state is stored in Redis** to keep services stateless and support gameplay recovery
- **Game definitions and rules are data-driven and editable via tooling without redeploying code**
- **Game Session Service orchestrates live game instances**, including tick execution and runtime configuration
- **Feature flags are defined at design-time in the Game Design Service and toggled at runtime via the Logging & Admin Service**
- 🔁 **One session per character is allowed** — logging in from another client forcibly transfers control to the new session and terminates the old one
- **Multi-tenant architecture shares infrastructure across games; per-game resource quotas are planned to prevent one tenant from exhausting cluster capacity.** (TODO: Not yet implemented)

🖼️ See also: [System Architecture Diagram](./system-architecture-diagram.md) and [System Context Diagram](./system-context-diagram.md)

---

## 🔁 Reconnection Strategy

FireMUD supports seamless gameplay recovery through a layered reconnection model:

| Layer               | Responsibility                                               |
|--------------------|---------------------------------------------------------------|
| TCP Proxy Service          | Buffers Telnet input; clears on disconnect                    |
| Spring Cloud Gateway     | Stateless; re-establishes backend connections on reconnect    |
| Game Session Service | Restores gameplay session using Redis                         |

> 🔗 See [Reconnection Strategy](./system-architecture-reconnection.md) for full details on session resumption, reauthentication, and failure handling.

---

## 🔗 Major Components and Their Roles

| Component                          | Purpose                                                                 |
|-----------------------------------|-------------------------------------------------------------------------|
| **Web Clients**                   | Modern browser clients using WebSocket or HTTP to access the platform  |
| **MUD Clients**                   | Traditional Telnet clients connecting via TCP, proxied into the system |
| **TCP Proxy Service**             | Accepts Telnet connections, buffers input, forwards over WebSocket     |
| **Spring Cloud Gateway**          | Handles WebSocket termination, routing, auth, monitoring                |
| **Game Session Service**          | Manages player sessions, tick orchestration, stores runtime flags, input validation |
| **Account Service**               | Manages player accounts, login, auth, subscription status; ban workflows are planned (TODO: Not yet implemented) |
| **Entity Management Service**     | Handles all entity data: players, NPCs, items, stats, inventories      |
| **World Management Service**      | Owns maps, rooms, and tick region structure; pathfinding APIs and world snapshots are planned (TODO: Not yet implemented) |
| **Game Logic Service**            | Executes gameplay mechanics; resolves actions deterministically        |
| **Automation & Scripting Service**| Triggers AI and scripted behaviors                                     |
| **Social & Groups Service**     | Manages chat, mail, guilds, and social features                        |
| **Logging & Admin Service**       | Provides admin tools, metrics dashboards, audit logs, and toggles runtime flags via the Game Session Service (TODO: Not yet implemented) |
| **Game Design Service**           | Authoring tool for designing and publishing game data; defines feature flags; publishing workflow copies data to runtime services (TODO: Not yet implemented) |

---

## 🌐 Communication Flows

| Flow                                        | Protocol                       |
|---------------------------------------------|--------------------------------|
| Web Clients → Spring Cloud Gateway          | WebSocket (wss) / HTTP (https) |
| MUD Clients → TCP Proxy Service             | Raw TCP (Telnet)               |
| TCP Proxy Service → Spring Cloud Gateway    | WebSocket (wss)                |
| Spring Cloud Gateway → Game Session Service | WebSocket (wss)                |
| Game Session Service → Other Microservices  | gRPC (internal)                |

✅ All internal communication from the Game Session Service onward uses **gRPC** with strict schema enforcement.

---

## 📦 Data and State Management

- **Persistent data** (accounts, entities, rooms) is stored in PostgreSQL by domain-aligned services
- **Volatile state** (sessions, command queues, timers) is stored in Redis and coordinated by the Game Session Service
- **Redis** is a **non-authoritative coordination buffer** — but **critical** for consistency, ticks, retries, and recovery
- **Tick regions** are shard-aligned in Redis to preserve atomicity

📌 See [Redis Architecture](./system-architecture-redis.md) for key structure and durability strategies.

---

## ⏱️ Game Loop / Tick Model

FireMUD uses a **Hybrid Tick Model** to balance responsiveness and fairness:

- **One action per entity per tick** (pulled from command queues)
- **Region-scoped ticks** execute independently for parallelism
- **Tick state** (locks, queues, timers) is stored and coordinated via Redis

> 🔗 Tick execution, staging/rollback, retry policies, and crash recovery are detailed in [Tick System and Runtime Design](./system-architecture-ticks.md)

---

## 🔐 Authentication and Authorization Flow

Clients authenticate using the `LOGIN` command, processed by the **Game Session Service**.
On disconnect, clients must reauthenticate to resume gameplay.
Session state is stored in Redis and reused for recovery.

> 🔗 See [Authentication & Authorization](./system-architecture-authentication.md) for JWT format and session flow.

---

## 📊 Observability and Monitoring

See [Logging & Monitoring](./system-architecture-logging-monitoring.md) for the full pipeline, including Fluent Bit, Prometheus, and related dashboards.

🔗 Additional Redis metrics are noted in [Redis Architecture](./system-architecture-redis.md#📈-observability-and-reliability).

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

Deployment health checks (readiness and liveness probes) for these layers are
described in detail in
[Deployment Environments](./infrastructure/deployment-environments.md).

Environment-specific routing is configured via Spring profiles defined in
`application.yml` and selected by the `SPRING_PROFILES_ACTIVE` environment
variable. See
[Deployment Environments](./infrastructure/deployment-environments.md#🔁-spring-profile-configuration)
for how the `dev` and `prod` profiles differ between Docker Compose and Kubernetes.

---

## 🔎 Notes on Responsibility Alignment

- Functional responsibilities are defined in the [Service Responsibility Matrix](./service-responsibility-matrix.md)
- **Game Session Service** orchestrates tick lifecycles, retries, and session management
- **Game Logic Service** resolves individual actions deterministically based on input state
- **Redis** acts as a passive, high-speed execution substrate — storing volatile state and enabling atomic coordination via Lua scripts

🧠 **Why Game Session Service vs Game Logic Service?**
Game Logic Service is stateless and deterministic.
Game Session Service governs pacing, conflict handling, and orchestration across distributed tick regions.

---

## 📚 Related Documentation

### Diagrams

- [System Architecture Diagram](./system-architecture-diagram.md)
- [System Context Diagram](./system-context-diagram.md)

### Infrastructure & Deployment

- [Infrastructure Overview](./infrastructure/README.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)
- [Multi-Tenancy Architecture](./system-architecture-multi-tenancy.md)

### Runtime & Security

- [Redis Architecture](./system-architecture-redis.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Authentication & Authorization](./system-architecture-authentication.md)
- [Security Architecture](./system-architecture-security.md)
- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Testing Strategy](./system-architecture-testing.md)

### Responsibilities

- [Microservices Responsibility Matrix](./service-responsibility-matrix.md)
