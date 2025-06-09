# 🏗️ FireMUD System Architecture Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

---

## 🧩 Core Architecture Principles

- **Microservices-based** domain-driven architecture with clearly separated responsibilities
- **Spring Cloud Gateway** serves as the unified HTTP/WebSocket entry point for all clients
- **TCP Proxy Service** accepts Telnet connections and upgrades them to WebSocket for the Gateway
- **Consistent end-to-end WebSocket flow**: TCP Proxy → Gateway → Game Session Service
- **All client traffic is routed through the Gateway**, ensuring consistent authentication, monitoring, and routing
- **Buffered reconnection logic** exists in both the TCP Proxy and the Gateway to handle dropped WebSocket connections gracefully
- **Internal microservice communication uses gRPC**, bypassing the Gateway for efficient backend-to-backend calls
- **Kubernetes DNS and IPVS-based load balancing** provide scalable, resilient service discovery and routing
- **Session state is externalized (e.g., Redis)** to keep services stateless and support reconnections
- **Game treated as data**, with the Game Design Service enabling live editing without code deployment

---

## 🔗 Major Components and Their Roles

| Component                          | Purpose                                                                      |
|------------------------------------|------------------------------------------------------------------------------|
| **Web Clients**                    | Browser-based modern clients connecting via WebSocket or HTTP                |
| **MUD Clients**                    | Traditional Telnet clients connecting via TCP (Telnet protocol)              |
| **TCP Proxy Service**              | Accepts Telnet TCP connections, buffers, translates to WebSocket for Gateway |
| **Spring Cloud Gateway**           | WebSocket termination, routing, security, and monitoring                     |
| **Game Session Service**           | Manages active player sessions, gameplay relay, and reconnection logic       |
| **Game Management Service**        | Orchestrates game instance lifecycles, backups, moderation policies          |
| **Account Service**                | Handles player accounts, authentication, session data                        |
| **Entity Management Service**      | Manages player characters, NPCs, items, and inventory                        |
| **World Management Service**       | Manages world data such as rooms, locations, and maps                        |
| **Game Logic Service**             | Centralized game rules, command parsing, and action handling                 |
| **Automation & Scripting Service** | Manages AI behavior and custom server scripting                              |
| **Social and Groups Service**      | Chat, guilds, and cross-game social systems                                  |
| **Logging & Admin Service**        | Centralized logging, analytics, and admin moderation tools                   |
| **Game Design Service**            | Provides tooling for designing worlds, abilities, and items                  |

---

## 🌐 Communication Flows

| Flow                                        | Protocol                       |
|---------------------------------------------|--------------------------------|
| Web Clients → Spring Cloud Gateway          | WebSocket (wss) / HTTP (https) |
| MUD Clients → TCP Proxy Service             | Raw TCP (Telnet)               |
| TCP Proxy Service → Spring Cloud Gateway    | WebSocket (wss)                |
| Spring Cloud Gateway → Game Session Service | WebSocket (wss)                |
| Game Session Service → Other Microservices  | gRPC (internal)                |

✅ All internal API calls use **gRPC** for high performance, typed schemas, and low overhead.

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

## 📝 Notes

- **Web Clients** connect to **Spring Cloud Gateway** via WebSocket/HTTP.
- **MUD Clients** (Telnet) connect first to the **TCP Proxy Service**, which forwards traffic to **Spring Cloud Gateway** via WebSocket.
- **Spring Cloud Gateway** forwards gameplay traffic to the **Game Session Service** over WebSocket (wss).
- **All internal microservice-to-microservice communication uses gRPC** for efficiency.
- **Kubernetes IPVS load balancing** dynamically distributes connections across pods, including long-lived WebSocket sessions.
- **TCP Proxy and Gateway must implement reconnection logic** to handle dropped WebSocket connections transparently.
- **All backend services are stateless**; session state is stored externally (e.g., in Redis).
- **Persistent data** (e.g., player accounts, entities, world data) is stored in dedicated databases.
- **Volatile player session state** is externalized to Redis by the Game Session Service to support reconnections and high availability.
- This design ensures centralized control, observability, scalability, low-latency gameplay, and failure recovery across all client types.

---

## ⚙️ Deployment Layers

| Layer                  | Technology                                                   |
|------------------------|--------------------------------------------------------------|
| Client Layer           | Browser, Telnet MUD Clients                                  |
| Proxy Layer            | TCP Proxy Service (LoadBalancer Service)                     |
| API Gateway Layer      | Spring Cloud Gateway (LoadBalancer Service)                  |
| Gameplay Session Layer | Game Session Service                                         |
| Service Layer          | Microservices (Account, Entity, World, etc.)                 |
| Infrastructure Layer   | Kubernetes with IPVS, Docker Compose (for local development) |

---

## 📚 Related Documentation

- [Infrastructure Overview](./infrastructure/README.md)
- [Gateway Architecture](./infrastructure/gateway-architecture.md)
- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Protocol Bridging](./infrastructure/protocol-bridging.md)
