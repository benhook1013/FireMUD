# 📚 FireMUD Microservices Documentation

This directory contains detailed design documents for each core microservice in the FireMUD Game Platform. These documents outline the responsibilities, APIs, data models, and interactions of each service.

---

## 🧩 Core Microservices

| Microservice                    | Purpose                                                    |
|----------------------------------|------------------------------------------------------------|
| [Game Management Service](./game-management-service/)    | Manages game creation, rules, templates, and moderation policies. |
| [World Management Service](./world-management-service/)  | Handles world maps, regions, procedural generation, and locations. |
| [Account Service](./account-service/)                    | Manages user accounts, authentication, profiles, and sessions. |
| [Entity Management Service](./entity-management-service/) | Controls player characters, NPCs, items, and inventory management. |
| [Game Logic Service](./game-logic-service/)              | Implements core gameplay mechanics, command parsing, and actions. |
| [Automation & Scripting Service](./automation-scripting-service/) | Handles AI behaviors, event scripting, and dynamic interactions. |
| [Social and Groups Service](./social-groups-service/)    | Manages chat, guilds, and cross-game social networking features. |
| [Logging & Admin Service](./logging-admin-service/)      | Provides centralized logging, analytics, and administration tools. |
| [Networking & Gateway Service](./networking-gateway-service/) | Manages real-time networking, TCP bridging, and API gateway access. |
| [Game Design Service](./game-design-service/)            | Provides tools for designing worlds, actions, items, and game events. |

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
