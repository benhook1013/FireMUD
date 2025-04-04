# 🏗️ FireMUD System Architecture Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

---

## 🧩 Core Architecture Principles

- **Microservices-based** platform design
- **Spring Cloud Gateway** as the API and WebSocket entry point
- **TCP Proxy Service** for Telnet (TCP) support and protocol bridging
- **Unified backend session management** for all clients
- **Distributed service responsibilities** (game logic, world data, player accounts, etc.)

---

## 🔗 Major Components and Their Roles

| Component                     | Purpose                                                              |
|--------------------------------|----------------------------------------------------------------------|
| **Web Clients**                | Browser-based modern clients connecting via WebSocket               |
| **MUD Clients**                | Traditional Telnet clients connecting via TCP                       |
| **Spring Cloud Gateway**       | HTTP/WebSocket API routing, filtering, and authentication           |
| **TCP Proxy Service**          | Accepts raw Telnet TCP connections and bridges them into WebSocket  |
| **Game Session Service**       | Manages active player sessions and gameplay over WebSocket          |
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

| Flow                                | Protocol                  |
|-------------------------------------|----------------------------|
| Web Clients → Spring Cloud Gateway  | WebSocket (wss) / HTTP (https) |
| MUD Clients → TCP Proxy Service     | Raw TCP (Telnet)           |
| TCP Proxy Service → Spring Cloud Gateway | WebSocket (wss)         |
| Gateway → Game Session Service      | WebSocket (internal)       |
| Session Service → Other Microservices | gRPC / REST (internal)   |

---

## 📈 System Architecture Diagram

```plaintext
+-----------------+            WebSocket/HTTP             +------------------------+
| Web Client (WS) | <------------------------------------> | Spring Cloud Gateway    |
+-----------------+                                        +-----------+------------+
                                                                      |
                                                                      | WebSocket
                                                                      v
+-----------------+            TCP (Telnet)                +------------------------+
| MUD Client (TCP) | <------------------------------------> | TCP Proxy Service       |
+-----------------+                                        +-----------+------------+
                                                                      |
                                                                      | WebSocket
                                                                      v
                                                        +----------------------------+
                                                        | Game Session Service        |
                                                        +-----------+----------------+
                                                                    |
  +------------------------------+----------------+----------------+----------------+
  |                              |                |                                |
  v                              v                v                                v
Account Service   Entity Management Service   World Management Service   Game Logic Service
    (Auth)                 (Players/NPCs)           (Rooms/Maps)               (Gameplay Rules)
```

---

## ⚙️ Deployment Layers

| Layer                   | Technology                         |
|--------------------------|------------------------------------|
| Client Layer             | Browser, Telnet MUD Clients        |
| API Gateway Layer        | Spring Cloud Gateway + TCP Proxy Service |
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

> ✅ This System Architecture Overview provides a conceptual map for understanding how clients interact with the FireMUD platform, how services are structured, and how data and communication flows are handled internally.
