# 📚 FireMUD Microservices Documentation

This directory contains detailed design documents for each core microservice in the FireMUD Game Platform. These documents outline the responsibilities, APIs, data models, and interactions of each service.

---

## 🧩 Core Microservices

| Microservice                    | Purpose                                                    |
|----------------------------------|------------------------------------------------------------|
| [World Management Service](./world-management-service/)  | Handles world maps, regions, procedural generation, and locations. |
| [Account Service](./account-service/)                    | Manages user accounts, authentication, profiles, and sessions. |
| [Game Session Service](./game-session-service/)          | Orchestrates live gameplay sessions and tick execution. |
| [Game Design Service](./game-design-service/)            | Provides tools for designing worlds, actions, items, and game events. |
| [Entity Management Service](./entity-management-service/) | Controls player characters, NPCs, items, and inventory management. |
| [Game Logic Service](./game-logic-service/)              | Implements core gameplay mechanics, command parsing, and actions. |
| [Automation & Scripting Service](./automation-scripting-service/) | Handles AI behaviors, event scripting, and dynamic interactions. |
| [Social & Groups Service](./social-groups-service/)    | Manages chat, guilds, and cross-game social networking features. |
| [Logging & Admin Service](./logging-admin-service/)      | Provides centralized logging, analytics, and administration tools. |
| [TCP Proxy Service](./tcp-proxy-service/)                | Bridges Telnet clients into the WebSocket-based backend. |
| [Spring Cloud Gateway](./spring-cloud-gateway/) | Routes WebSocket and HTTP traffic to backend services. |
| [Service Template](./service-template.md) | Template for creating new microservice docs. |

---

## 🧭 Usage

Each microservice document follows a consistent structure, covering:

- **Service Overview**
- **Architecture and Key Responsibilities**
- **Key Features and APIs**
- **External and Internal Dependencies**
- **Future Enhancements and Roadmap**

For cross-service systems (e.g., networking, infrastructure), refer to:

> See [**Infrastructure Overview**](../infrastructure/README.md) for shared architecture, deployment environments, and networking patterns.
