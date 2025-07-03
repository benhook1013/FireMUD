# 🏗️ FireMUD System Architecture Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

---

## 🧩 Core Architecture Principles

- **Microservices-based** domain-driven architecture with clearly separated responsibilities  
- **Spring Cloud Gateway** serves as the unified HTTP/WebSocket entry point for all clients  
- **TCP Proxy Service** accepts Telnet connections and upgrades them to WebSocket for the Gateway  
- **Consistent end-to-end WebSocket flow**: Telnet (TCP) → TCP Proxy Service (WebSocket upgrade) → Spring Cloud Gateway → Game Session Service
- **All client traffic is routed through the Spring Cloud Gateway**, ensuring centralized **traffic routing, monitoring, and observability**  
  > 🛑 **Gameplay login is handled by the Game Session Service** — the Gateway may validate JWTs for admin endpoints, but gameplay clients connect without tokens
- **Spring Cloud Gateway is fully horizontally scalable and stateless**, with no sticky session requirements  
- **Telnet clients maintain sticky TCP connections only to the TCP Proxy Service**, which buffers **active input**, but **discards it across reconnects**
- **Reconnection logic is handled in layers** to preserve gameplay continuity  
- **All internal service-to-service communication from the Game Session Service onward uses gRPC**, with strict schema enforcement and low latency  
- **Session state is stored in Redis** to keep services stateless and support gameplay recovery  
- **Game definitions and rules are data-driven and editable via tooling without redeploying code**  
- **Game Session Service orchestrates live game instances**, including tick execution and runtime configuration  
- **Feature flags are defined at design-time in the Game Design Service and toggled at runtime by the Game Session Service**  
- 🔁 **One session per character is allowed** — logging in from another client forcibly transfers control to the new session and terminates the old one

🖼️ See also: [System Architecture Diagram](./system-architecture-diagram.md)

---

## 🔁 Reconnection Strategy

FireMUD supports seamless gameplay recovery through a layered reconnection model:

| Layer               | Responsibility                                               |
|--------------------|---------------------------------------------------------------|
| TCP Proxy Service          | Buffers Telnet input; clears on disconnect                    |
| Spring Cloud Gateway     | Stateless; re-establishes backend connections on reconnect    |
| Game Session       | Restores gameplay session using Redis                         |

> 🔗 See [Reconnection Strategy](./system-architecture-reconnection.md) for full details on session resumption, reauthentication, and failure handling.

---

## 🔗 Major Components and Their Roles

| Component                          | Purpose                                                                 |
|-----------------------------------|-------------------------------------------------------------------------|
| **Web Clients**                   | Modern browser clients using WebSocket or HTTP to access the platform  |
| **MUD Clients**                   | Traditional Telnet clients connecting via TCP, proxied into the system |
| **TCP Proxy Service**             | Accepts Telnet connections, buffers input, forwards over WebSocket     |
| **Spring Cloud Gateway**          | Handles WebSocket termination, routing, auth, monitoring                |
| **Game Session Service**          | Manages player sessions, tick orchestration, runtime flags, input validation |
| **Account Service**               | Manages player accounts, login, auth, subscriptions, and bans          |
| **Entity Management Service**     | Handles all entity data: players, NPCs, items, stats, inventories      |
| **World Management Service**      | Owns maps, rooms, and tick region structure                            |
| **Game Logic Service**            | Executes gameplay mechanics; resolves actions deterministically        |
| **Automation & Scripting Service**| Triggers AI and scripted behaviors                                     |
| **Social & Groups Service**     | Manages chat, mail, guilds, and social features                        |
| **Logging & Admin Service**       | Provides admin tools, metrics dashboards, audit logs                   |
| **Game Design Service**           | Authoring tool for designing and publishing game data                  |

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
- Redis is a **non-authoritative coordination buffer** — but **critical** for consistency, ticks, retries, and recovery  
- Tick regions are shard-aligned in Redis to preserve atomicity  

📌 See [Redis Architecture](./system-architecture-redis.md) for key structure and durability strategies.

---

## ⏱️ Game Loop / Tick Model

FireMUD uses a **Hybrid Tick Model** to balance responsiveness and fairness:

- One action per entity per tick (pulled from command queues)
- Region-scoped ticks execute independently for parallelism
- Tick state (locks, queues, timers) is stored and coordinated via Redis

> 🔗 Tick execution, staging/rollback, retry policies, and crash recovery are detailed in [Tick System and Runtime Design](./system-architecture-ticks.md)

---

## 🔐 Authentication and Authorization Flow

Clients authenticate using the `LOGIN` command, processed by the **Game Session Service**.  
On disconnect, clients must reauthenticate to resume gameplay.  
Session state is stored in Redis and reused for recovery.

> 🔗 See [Authentication & Authorization](./system-architecture-authentication.md) for JWT format and session flow.

---

## 📊 Observability and Monitoring

FireMUD uses a unified observability pipeline:

### 🔍 Logging

- Structured JSON logs with `traceId`, `playerId`, `sessionId`
- Collected via Fluent Bit and indexed in Elasticsearch
- Visualized with Kibana dashboards for easy log search

### 🧾 Admin & Logging Service

- Provides interactive dashboards for metrics and game health monitoring  
- Offers log query tools for real-time inspection of structured logs (via Elasticsearch)  
- Includes moderation interfaces for managing player reports, bans, and administrative actions  

### 📈 Metrics and Tracing

- Prometheus metrics from all services
- Prometheus Alertmanager triggers alerts for outages or latency spikes
- OpenTelemetry spans across ticks
- 🔗 See [Redis Architecture](./system-architecture-redis.md#📈-observability-and-reliability) for Redis-specific metrics
- 🔗 See [Infrastructure Overview](./infrastructure/README.md#🔀-core-infrastructure-docs) for deployment-specific monitoring integration

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

Environment-specific routing is configured through Spring profiles
(`application-dev.yml`, `application-prod.yml`). See
[Deployment Environments](./infrastructure/deployment-environments.md#🔁-spring-profile-configuration)
for how these profiles differ in Docker Compose versus Kubernetes.

---

## 🔎 Notes on Responsibility Alignment

- Functional responsibilities are defined in the [Service Responsibility Matrix](./service-responsibility-matrix.md)  
- **Game Session** orchestrates tick lifecycles, retries, and session management  
- **Game Logic** resolves individual actions deterministically based on input state  
- **Redis** acts as a passive, high-speed execution substrate — storing volatile state and enabling atomic coordination via Lua scripts

🧠 **Why Game Session vs Game Logic?**  
Game Logic is stateless and deterministic.  
Game Session governs pacing, conflict handling, and orchestration across distributed tick regions.

---

## 📚 Related Documentation

- [Tick System and Runtime Design](./system-architecture-ticks.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Authentication & Authorization](./system-architecture-authentication.md)
- [Microservices Responsibility Matrix](./service-responsibility-matrix.md)
- [System Architecture Diagram](./system-architecture-diagram.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Gateway Architecture](./infrastructure/gateway-architecture.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Protocol Bridging](./infrastructure/protocol-bridging.md)
