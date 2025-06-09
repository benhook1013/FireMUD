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
- **Game treated as data**, with the Game Design Service enabling live editing without code deployment

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
- Avoids gameplay resets or inconsistencies even if upstream connections fail temporarily

Each layer handles the reconnection logic appropriate to its scope, ensuring fault tolerance and a smooth player experience.

---

## 🔗 Major Components and Their Roles

| Component                          | Purpose                                                                 |
|-----------------------------------|-------------------------------------------------------------------------|
| **Web Clients**                   | Modern browser clients using WebSocket or HTTP to access the platform  |
| **MUD Clients**                   | Traditional Telnet clients connecting via TCP, proxied into the system |
| **TCP Proxy Service**             | Accepts Telnet connections, buffers input, forwards over WebSocket     |
| **Spring Cloud Gateway**          | Handles WebSocket termination, routing, auth, monitoring                |
| **Game Session Service**          | Core gameplay relay and session management layer                       |
| **Game Management Service**       | Orchestrates game templates, rules, instances, and moderation policies |
| **Account Service**               | Manages player accounts, login, auth, subscriptions, and bans          |
| **Entity Management Service**     | Handles all entity data: players, NPCs, items, stats, inventories      |
| **World Management Service**      | Owns the structure and logic of maps, rooms, and pathfinding           |
| **Game Logic Service**            | Central engine for command parsing and rule-based mechanics            |
| **Automation & Scripting Service**| AI behavior and runtime scripting engine for custom server logic       |
| **Social and Groups Service**     | Manages chat, mail, guilds, and player-driven social systems           |
| **Logging & Admin Service**       | Central logging, metrics, moderation dashboards, and admin tooling     |
| **Game Design Service**           | Enables world/ability/item creation and balancing through the editor   |

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

## 📈 System Architecture Diagram

```plaintext
      +------------+      TCP (Telnet)         +-------------------+
      | MUD Client | <-----------------------> | TCP Proxy Service |
      +------------+                           +---------+---------+
                                                         |
                                                         | WebSocket (wss)
                                                         |
                                                         v
      +------------+      WebSocket/HTTP     +----------------------+
      | Web Client | <----------------------> | Spring Cloud Gateway |
      +------------+                          +----------+-----------+
                                                         |
                                                         | WebSocket (wss)
                                                         |
                                                         v
                                          +----------------------------+
                                          | Game Session Service       |
                                          +--------------+-------------+
                                                         |
       +--------------------+----------------------------+--------------------+
       |                    |                            |                    |
       v                    v                            v                    v
Game Management      Account Service              Entity Management     World Management
    Service              (Auth)                    Service (Players,        Service
 (Backups, Rules)                                  NPCs, Items)          (Maps/Rooms)

                              +-----------+-------------+
                              | Game Logic Service      |
                              | (Rules, Commands, etc.) |
                              +-----------+-------------+
                                          |
                                          v
                             +--------------------------------+
                             | Automation & Scripting Service |
                             +--------------------------------+

             +-------------------------+      +-------------------------+
             | Social & Groups Service |      | Logging & Admin Service |
             +-------------------------+      +-------------------------+

                       +--------------------------------------+
                       | Game Design Service (Passive Editor) |
                       +--------------------------------------+
```

---

## 📦 Data and State Management

- **Persistent data** (accounts, entities, world data) is owned by domain-aligned services with dedicated PostgreSQL databases.
- **Volatile state** (player sessions, transient room state) is stored in Redis by the Game Session Service.
- Services remain **stateless**, promoting scalability and resilience in failover scenarios.

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
