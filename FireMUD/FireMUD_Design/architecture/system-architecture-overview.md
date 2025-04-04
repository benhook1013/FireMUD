# 🏗️ FireMUD System Architecture Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

---

## 🧩 Core Architecture Principles

- **Microservices-based** platform design
- **Spring Cloud Gateway** as the API and WebSocket entry point for all clients
- **TCP Proxy Service** for Telnet (TCP) support, acting as a bridge to the Gateway
- **WebSocket sessions are terminated at the Gateway**; internal communication uses HTTP/gRPC
- **Unified backend session management** with stateless services and externalized session storage
- **Distributed service responsibilities** (game logic, world data, player accounts, etc.)

**Important:**  
Traditional Telnet clients connect first to the **TCP Proxy Service**, which **forwards traffic to Spring Cloud Gateway** using WebSocket.  
This **ensures all client traffic is consistently secured, authenticated, monitored, and routed through the Gateway**.

---

## 🔗 Major Components and Their Roles

| Component                     | Purpose                                                              |
|--------------------------------|----------------------------------------------------------------------|
| **Web Clients**                | Browser-based modern clients connecting via WebSocket or HTTP       |
| **MUD Clients**                | Traditional Telnet clients connecting via TCP (Telnet protocol)     |
| **TCP Proxy Service**          | Accepts Telnet TCP connections, translates to WebSocket for Gateway |
| **Spring Cloud Gateway**       | Terminates WebSocket sessions; routes internal HTTP/gRPC calls      |
| **Game Session Service**       | Stateless management of active player sessions and gameplay        |
| **Account Service**            | Handles player accounts, authentication, session data              |
| **Entity Management Service**  | Manages player characters, NPCs, items, and inventory               |
| **World Management Service**   | Manages world data such as rooms, locations, and maps               |
| **Game Logic Service**         | Centralized game rules, command parsing, and action handling        |
| **Automation & Scripting Service** | Manages AI behavior and custom server scripting                 |
| **Social and Groups Service**  | Chat, guilds, and cross-game social systems                         |
| **Logging & Admin Service**    | Centralized logging, analytics, and admin moderation tools         |
| **Game Design Service**        | Provides tooling for designing worlds, abilities, and items         |

---

## 🌐 Communication Flows

| Flow                                  | Protocol                  |
|---------------------------------------|----------------------------|
| Web Clients → Spring Cloud Gateway    | WebSocket (wss) / HTTP (https) |
| MUD Clients → TCP Proxy Service       | Raw TCP (Telnet)           |
| TCP Proxy Service → Spring Cloud Gateway | WebSocket (wss)         |
| Spring Cloud Gateway → Game Session Service | HTTP/gRPC (internal)  |
| Game Session Service → Other Microservices | gRPC (internal)         |

---

## 📈 System Architecture Diagram

```plaintext
+-----------------+            TCP (Telnet)               +-------------------+
| MUD Client (TCP) | <----------------------------------> | TCP Proxy Service |
+-----------------+                                       +---------+---------+
                                                                    |
                                                                    | WebSocket
                                                                    v
+-----------------+            WebSocket/HTTP            +----------------------+
| Web Client (WS) | <----------------------------------> | Spring Cloud Gateway |
+-----------------+                                      +----------+-----------+
                                                                    |
                                                                    | WebSocket
                                                                    v
                                                     +----------------------------+
                                                     | Game Session Service       |
                                                     +--------------+-------------+
                                                                    |
       +---------------------+---------------------------+----------+-----------+
       |                     |                           |                      |
       v                     v                           v                      v
Account Service   Entity Management Service   World Management Service   Game Logic Service
    (Auth)              (Players/NPCs)              (Rooms/Maps)          (Gameplay Rules)
```

---

## 📝 Notes

- **Web Clients** connect to **Spring Cloud Gateway** via WebSocket/HTTP.
- **MUD Clients** (Telnet) connect first to the **TCP Proxy Service**, which forwards traffic to **Spring Cloud Gateway** via WebSocket.
- **Spring Cloud Gateway terminates WebSocket sessions** and forwards gameplay traffic internally using **HTTP/gRPC**.
- **All backend services are stateless**; session state is stored externally (e.g., in Redis).
- **This design ensures centralized control, observability, scalability, and failure recovery across all client types.**

---

## ⚙️ Deployment Layers

| Layer                   | Technology                         |
|--------------------------|------------------------------------|
| Client Layer             | Browser, Telnet MUD Clients        |
| Proxy Layer              | TCP Proxy Service                 |
| API Gateway Layer        | Spring Cloud Gateway              |
| Gameplay Session Layer   | Game Session Service              |
| Service Layer            | Microservices (Account, Entity, World, etc.) |
| Infrastructure Layer     | Kubernetes, Docker Compose        |

---

## 📚 Related Documentation

- [Infrastructure Overview](../infrastructure/_index.md)
- [Gateway Architecture](../infrastructure/gateway-architecture.md)
- [Deployment Environments](../infrastructure/deployment-environments.md)
- [Protocol Bridging](../infrastructure/protocol-bridging.md)

---
