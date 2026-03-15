# FireMUD Microservices Documentation

This directory contains detailed design documents for each core microservice in the FireMUD Game Platform. These documents outline the responsibilities, APIs, data models, and interactions of each service.

Service READMEs must not contradict the canonical contracts in [System Architecture Overview](../system-architecture-overview.md) and [Service Responsibility Matrix](../service-responsibility-matrix.md). If a service-level design needs to change an edge exposure rule, ownership boundary, Redis prefix owner, moderation/operator write path, or other canonical cross-service contract, update the architecture docs first or in the same change. For shared terminology such as `session front-end`, `lease owner`, `canonical room state`, and `bypass-safe workflow`, use the canonical definitions in the [architecture index glossary](../README.md#canonical-terms).

---

## Core Microservices

| Microservice | Purpose |
| --- | --- |
| [Account Service](./account-service/) | Manages user accounts, authentication, profiles, and admin/API session tokens (credentials and JWTs, not gameplay sessions). |
| [Automation & Scripting Service](./automation-scripting-service/) | Handles AI behaviors, event scripting, and dynamic interactions. |
| [Entity Management Service](./entity-management-service/) | Controls player characters, NPCs, items, and inventory management. |
| [Game Design Service](./game-design-service/) | Provides tools for designing worlds, actions, items, and game events. |
| [Game Logic Service](./game-logic-service/) | Implements core gameplay mechanics, command parsing, and actions. |
| [Game Session Service](./game-session-service/) | Orchestrates live gameplay sessions and tick execution; owns gameplay session bindings and tick coordination in Redis plus durable game-instance/runtime control metadata in PostgreSQL. |
| [Logging & Admin Service](./logging-admin-service/) | Provides centralized logging, analytics, and administration tools; owns moderation policy and audit logs; provides operator UX and auditing for quota/limit overrides represented as an overlay on Account Service entitlements. |
| [Social & Groups Service](./social-groups-service/) | Manages chat, guilds, and cross-game social networking features. |
| [Spring Cloud Gateway](./spring-cloud-gateway/) | Routes WebSocket and HTTP traffic to backend services. |
| [TCP Proxy Service](./tcp-proxy-service/) | Bridges Telnet clients into the WebSocket-based backend. |
| [World Management Service](./world-management-service/) | Handles world maps, regions, and pathfinding/procedural-generation metadata publishing; runtime pathfinding algorithms execute in Game Logic, and procedural generation runs in design/publish workflows unless a dedicated runtime design update is accepted. |
| [Service Template](./service-template.md) | Template for creating new microservice docs. |

All services share the same Kubernetes cluster and core datastores. Each PostgreSQL table stores a `tenantId` and Redis keys use a matching prefix so data stays isolated between games. In non-ephemeral environments (including local development), Redis runs as two separate deployments for Coordination vs Cache/Rate-Limit roles; truly ephemeral CI/preview stacks may collapse roles into a single Redis instance only when explicitly documented as an ephemeral topology. See [Multi-Tenancy](../system-architecture-multi-tenancy.md), [Redis Architecture](../system-architecture-redis.md), and [Redis Usage & Profiles](../system-architecture-redis-usage-and-profiles.md) for details. Service-specific Redis behavior (Coordination vs Cache/Rate-Limit roles and key prefixes) is documented in each service README under its **Redis Role and Prefixes** section.

---

## Usage

Each microservice document follows a consistent structure, covering:

- **Service Overview**
- **Architecture and Key Responsibilities**
- **Key Features, Data Models, and APIs**
- **External and Internal Dependencies**
- **Operational Notes and Task Tracking**

For cross-service systems (e.g., networking, infrastructure), refer to:

> See [**Infrastructure Overview**](../infrastructure/README.md) for shared architecture, deployment environments, and networking patterns.

For new services, start from the [Service Template](./service-template.md) so documentation follows the same layout.

All gRPC schema files are organized under the top-level
[`protos/`](../../../protos) directory. Individual service documents link to their
corresponding versioned proto folders.

## Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [Infrastructure Overview](../infrastructure/README.md)
- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)
- [User Journeys](../user-journeys.md)
