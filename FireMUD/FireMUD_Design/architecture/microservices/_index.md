# 📚 FireMUD Microservices Documentation

This directory contains detailed design documents for each core microservice in the FireMUD Game Platform. These documents outline the responsibilities, APIs, data models, and interactions of each service.

---

## 🧩 Core Microservices

| Microservice                    | Purpose                                                    |
|----------------------------------|------------------------------------------------------------|
| [Game Management Service](./game_management_service.md)    | Manages game creation, rules, templates, and moderation policies. |
| [World Management Service](./world_management_service.md)  | Handles world maps, regions, procedural generation, and locations. |
| [Account Service](./account_service.md)                    | Manages user accounts, authentication, profiles, and sessions. |
| [Entity Management Service](./entity_management_service.md) | Controls player characters, NPCs, items, and inventory management. |
| [Game Logic Service](./game_logic_service.md)              | Implements core gameplay mechanics, command parsing, and actions. |
| [Automation & Scripting Service](./automation_scripting_service.md) | Handles AI behaviors, event scripting, and dynamic interactions. |
| [Social and Groups Service](./social_groups_service.md)    | Manages chat, guilds, and cross-game social networking features. |
| [Logging & Admin Service](./logging_admin_service.md)      | Provides centralized logging, analytics, and administration tools. |
| [Networking & Gateway Service](./networking_gateway_service.md) | Manages real-time networking, TCP bridging, and API gateway access. |
| [Game Design Service](./game_design_service.md)            | Provides tools for designing worlds, actions, items, and game events. |

---

## 🧭 Usage

Each microservice document follows a consistent structure, covering:

- **Service Overview**
- **Architecture and Key Responsibilities**
- **Key Features and APIs**
- **External and Internal Dependencies**
- **Future Enhancements and Roadmap**

For cross-service systems (e.g., networking, infrastructure), refer to:

> See [**Infrastructure Overview**](../infrastructure/_index.md) for shared architecture, deployment environments, and networking patterns.
