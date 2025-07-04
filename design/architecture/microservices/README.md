# 📚 FireMUD Microservices Documentation

This directory contains detailed design documents for each core microservice in the FireMUD Game Platform. These documents outline the responsibilities, APIs, data models, and interactions of each service.

---

## 🧩 Core Microservices

| Microservice | Purpose |
|-------------|---------|
| [Account Service](./account-service/)                    | Manages user accounts, authentication, profiles, and sessions. |
| [Automation & Scripting Service](./automation-scripting-service/) | Handles AI behaviors, event scripting, and dynamic interactions. |
| [Entity Management Service](./entity-management-service/) | Controls player characters, NPCs, items, and inventory management. |
| [Game Design Service](./game-design-service/)            | Provides tools for designing worlds, actions, items, and game events. |
| [Game Logic Service](./game-logic-service/)              | Implements core gameplay mechanics, command parsing, and actions. |
| [Game Session Service](./game-session-service/)          | Orchestrates live gameplay sessions and tick execution. |
| [Logging & Admin Service](./logging-admin-service/)      | Provides centralized logging, analytics, and administration tools. |
| [Social & Groups Service](./social-groups-service/)      | Manages chat, guilds, and cross-game social networking features. |
| [Spring Cloud Gateway](./spring-cloud-gateway/)          | Routes WebSocket and HTTP traffic to backend services. |
| [TCP Proxy Service](./tcp-proxy-service/)                | Bridges Telnet clients into the WebSocket-based backend. |
| [World Management Service](./world-management-service/)  | Handles world maps, regions, pathfinding data, and procedural generation. |
| [Service Template](./service-template.md)                | Template for creating new microservice docs. |

---

## 🧭 Usage

Each microservice document follows a consistent structure, covering:

- **Service Overview**
- **Architecture and Key Responsibilities**
- **Key Features, Data Models, and APIs**
- **External and Internal Dependencies**
- **Future Enhancements and Roadmap**

For cross-service systems (e.g., networking, infrastructure), refer to:

> See [**Infrastructure Overview**](../infrastructure/README.md) for shared architecture, deployment environments, and networking patterns.

All gRPC schema files are organized under the top-level
[`protos/`](../../protos) directory. Individual service documents link to their
corresponding versioned proto folders.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [Infrastructure Overview](../infrastructure/README.md)
