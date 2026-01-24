# FireMUD System Architecture: Overview

This document provides a high-level view of FireMUD’s system architecture, showing how major services, protocols, and data flows interact across the platform.

---

## Core Architecture Principles

- **Microservices-based** domain-driven architecture with clearly separated responsibilities
- **Spring Cloud Gateway** serves as the unified HTTP/WebSocket entry point for all clients
- **TCP Proxy Service** accepts Telnet connections and upgrades them to WebSocket for the Gateway (in production this is typically fronted by a Telnet edge proxy that forwards to the TCP Proxy using PROXY protocol). The Proxy → Gateway hop is secured with mutual TLS; see [Security Architecture](./system-architecture-security.md#tls-termination-for-gateway), [Protocol Bridging](./system-architecture-protocol-bridging.md#telnet-edge-proxy-and-proxy-protocol), and the TCP Proxy Service design’s **Implementation Status** section for environment-specific wiring details.
- **Consistent end-to-end WebSocket flow**: Telnet (TCP) → TCP Proxy Service (WebSocket upgrade) → Spring Cloud Gateway → Game Session Service
- **All application-level gameplay and admin traffic is routed through the Spring Cloud Gateway**, ensuring centralized **traffic routing, monitoring, and observability**. Raw Telnet TCP terminates at the Telnet edge proxy and TCP Proxy Service before being bridged to the Gateway over WebSocket. See [Gateway Architecture](./system-architecture-gateway.md) for deployment details and stateless behavior.
  - Ordering and delivery guarantees for the combined Telnet/WebSocket path (FIFO where delivered, at-most-once semantics, and explicit drop conditions) are documented in [Protocol Bridging](./system-architecture-protocol-bridging.md#ordering--delivery-invariants).
  - Backpressure and slow-client behavior across the TCP Proxy and WebSocket layers are described in [Protocol Bridging](./system-architecture-protocol-bridging.md#backpressure--slow-clients).
   > 🛑 **Gameplay login is fronted by the Game Session Service**, which handles the `LOGIN` command and binds sessions in Redis. It calls the Account Service to verify credentials and obtain JWTs/tokens. The Gateway simply forwards any admin tokens, and JWTs are validated by the admin or logging services themselves; gameplay clients connect without tokens. See [Authentication & Authorization](./system-architecture-authentication.md#-login-and-session-flow) for the full login flow.
- **Telnet clients maintain sticky TCP connections only to the TCP Proxy Service**, which buffers **active input** but **discards it across reconnects**
- **Reconnection logic is handled in layers** to preserve gameplay continuity
- **All internal service-to-service communication from the Game Session Service onward uses gRPC**, with strict schema enforcement and low latency. All calls are encrypted with **mutual TLS**; see [Security Architecture](./system-architecture-security.md)
- **Session state is stored in Redis** to keep services stateless and enable full reconnect recovery
- **Game definitions and rules are data-driven and editable via tooling without redeploying code**; see the [Game Design Service documentation](./microservices/game-design-service/README.md).
- **Game Session Service orchestrates live game instances**, handling tick execution and runtime configuration
- [**Feature flags**](./microservices/game-design-service/feature-flags.md) are defined at design-time in the Game Design Service and toggled at runtime via the Logging & Admin Service
- **One session per character is enforced** — logging in from another client forcibly transfers control to the new session and terminates the old one
- **Multi-tenant architecture shares infrastructure across games; per-game resource quotas prevent one tenant from exhausting cluster capacity.**
- **Admin and operations tooling communicates with Spring Cloud Gateway over an internal gRPC management API** for route and health management; no gameplay traffic flows over this control-plane path.

### Authentication Modes and Boundaries

FireMUD uses two complementary authentication modes that share a common identity model but differ in how they are presented by clients:

- **Gameplay sessions (players)**  
  - Players authenticate using the `LOGIN` command handled by the **Game Session Service**.  
  - Game Session delegates credential verification (including 2FA, external identity providers, and lockout rules) to the **Account Service**, which owns all credential and account-security decisions.  
  - On success, Game Session creates and maintains a Redis-backed gameplay session binding (tenant, character, tick-region context) and enforces “one session per character”. Gameplay traffic is authenticated by this Redis session context rather than by browser-style JWTs sent on each message.

- **Admin and creator sessions (control plane)**  
  - Admin and creator tools authenticate via HTTP/gRPC using JWTs issued by the **Account Service**, which publishes JWKS and remains the source of truth for token semantics.  
  - Internal services validate JWTs using a shared library and JWKS; they do not make ad-hoc token-parsing decisions.  
  - Spring Cloud Gateway forwards auth headers and can enforce coarse-grained route protections (for example, “admin endpoints require a valid JWT”) but does not own credential verification or authorization policy.

This split keeps gameplay session management and tick-sensitive orchestration in the Game Session Service while ensuring that account security, token issuance, and policy remain centralized in the Account Service. See [Authentication & Authorization](./system-architecture-authentication.md) for detailed flows.

> 🔗 See [System Architecture Diagram](./system-architecture-diagram.md) and [System Context Diagram](./system-context-diagram.md).

---

## Implementation Status

Unless otherwise noted, this document describes the **target-state architecture** for FireMUD. The Telnet edge chain (Telnet client → Telnet edge proxy with PROXY protocol → TCP Proxy Service → Spring Cloud Gateway with mTLS) and related certificate wiring are being rolled out incrementally.

For current rollout and configuration details, refer to:

- The **Implementation Status** section in the [TCP Proxy Service design](./microservices/tcp-proxy-service/README.md)
- The [Telnet Path Degraded Runbook](./system-architecture-telnet-degraded-runbook.md)
- The relevant sections in [Security Architecture](./system-architecture-security.md) and [Protocol Bridging](./system-architecture-protocol-bridging.md)

## Reconnection Strategy

FireMUD supports seamless gameplay recovery through a layered reconnection model:

| Layer | Responsibility |
| --- | --- |
| TCP Proxy Service | Buffers Telnet input; clears on disconnect |
| Spring Cloud Gateway | Stateless; re-establishes backend connections on reconnect |
| Game Session Service | Restores gameplay session using Redis |

Certain failures can affect only the Telnet path while web clients remain healthy, such as misconfigured TLS or mTLS on the TCP Proxy → Gateway WebSocket bridge or issues in the Telnet edge proxy/PROXY-protocol chain. When Telnet is degraded but WebSocket remains healthy, operators should consult the [Telnet Path Degraded Runbook](./system-architecture-telnet-degraded-runbook.md) alongside the general [Reconnection Strategy](./system-architecture-reconnection.md).

> 🔗 See [Reconnection Strategy](./system-architecture-reconnection.md) for full details on session resumption, reauthentication, and failure handling.

---

## Redis Roles, Keyspace Partitioning, and Data Ownership

Persistent, authoritative data and transient coordination state are deliberately separated so gameplay remains consistent under load:

- **Authoritative data** (accounts, world topology, entities, chat history, moderation records, and similar) is stored in PostgreSQL by domain-aligned services.
- **Coordination Redis** holds volatile, gameplay-critical structures (session bindings, tick queues, locks, timers) owned primarily by the Game Session Service and a small number of cooperating services using shared helpers.
- **Cache/Rate-Limit Redis** is used for best-effort caches and rate limiting by Spring Cloud Gateway, the TCP Proxy Service, and selected backend services; these keys use dedicated prefixes and must not share a deployment with coordination keys in player-facing environments.

Within Redis, keys are further partitioned by responsibility and, in production, can be mapped onto different logical databases or clusters:

- **Coordination and session keys (Coordination Redis)**  
  - Examples: gameplay sessions, tick-region leases, command queues, timers, and automation tick coordination.  
  - Canonical prefixes include (non-exhaustive):  
    - `session:*` – gameplay session bindings and takeover metadata.  
    - `coord:*` – distributed locks, leases, and tick/timer coordination.  
    - `tick:*` – tick queues, region scheduling, and pacing-related state.  
    - `automation:*` – automation and scripting tick coordination and work queues.

- **Cache and rate-limit keys (Cache/Rate-Limit Redis)**  
  - Examples: read-side caches, rate-limit counters, and quota tracking for non-critical flows.  
  - Canonical prefixes include (non-exhaustive):  
    - `cache:*` – general-purpose caches for derived data, short-lived lookups, and infrequently updated views.  
    - `ratelimit:*` – per-account or per-IP rate limiting for APIs, login attempts, and abuse prevention.

Coordination Redis and Cache/Rate-Limit Redis should be operated and scaled independently in production so cache or rate-limit spikes cannot degrade tick execution or session coordination.

See [Redis Architecture](./system-architecture-redis.md) and [Redis Usage & Profiles](./system-architecture-redis-usage-and-profiles.md) for the detailed key structure, multi-tenant key design, and allowed patterns, and the [Service Responsibility Matrix](./service-responsibility-matrix.md) for which services participate in each Redis role.

---

## Major Components and Their Roles

| Component | Purpose |
| --- | --- |
| **Web Clients** | Modern browser clients using WebSocket or HTTP to access the platform |
| **MUD Clients** | Traditional Telnet clients connecting via TCP, proxied into the system |
| **[TCP Proxy Service](./microservices/tcp-proxy-service/README.md)** | Accepts Telnet connections, buffers input, forwards over WebSocket; proxy-to-gateway mTLS secures the link |
| **[Spring Cloud Gateway](./microservices/spring-cloud-gateway/README.md)** | Handles WebSocket termination, routing, and observability; enforces coarse-grained admin access controls but does not own gameplay authentication or authorization decisions |
| **[Game Session Service](./microservices/game-session-service/README.md)** | Fronts gameplay login commands and session binding, manages player sessions, tick orchestration, runtime flags, and input validation |
| **[Account Service](./microservices/account-service/README.md)** | Manages player accounts, credentials, authentication, and JWT/JWKS issuance; handles subscriptions and bans |
| **[Entity Management Service](./microservices/entity-management-service/README.md)** | Handles all runtime entity data: players, NPCs, items, stats, and all inventories/containment (player inventory/equipment, containers, and items on the ground held in room-ground container entities keyed by room/instance ID) |
| **[World Management Service](./microservices/world-management-service/README.md)** | Owns maps, rooms, and tick region structure; provides geometry and static world snapshots (topology and ambient world state only, not live entities/items/inventories) |
| **[Game Logic Service](./microservices/game-logic-service/README.md)** | Executes gameplay mechanics; resolves actions deterministically, including movement/travel cost computation |
| **[Automation & Scripting Service](./microservices/automation-scripting-service/README.md)** | Triggers AI and scripted behaviors |
| **[Social & Groups Service](./microservices/social-groups-service/README.md)** | Manages chat, mail, guilds, and social features |
| **[Logging & Admin Service](./microservices/logging-admin-service/README.md)** | Provides admin tools, metrics dashboards, audit logs, and toggles runtime flags via the Game Session Service |
| **[Game Design Service](./microservices/game-design-service/README.md)** | Authoring tool for designing and publishing game data; defines feature flags; publishing workflow copies data to runtime services |

> 🔗 See [Microservices Documentation](./microservices/README.md) for the full list of responsibilities and APIs.

## Communication Flows

| Flow | Protocol |
| --- | --- |
| Web Clients → Spring Cloud Gateway | WebSocket (wss) / HTTP (https) |
| MUD Clients → TCP Proxy Service | Raw TCP (Telnet) |
| TCP Proxy Service → Spring Cloud Gateway | WebSocket (wss) |
| Spring Cloud Gateway → Game Session Service | WebSocket (wss) |
| Game Session Service → Other Microservices | gRPC (internal) |

✅ All internal communication from the Game Session Service onward uses **gRPC** with strict schema enforcement.

---

## Data and State Management

- **Persistent data** (accounts, entities, rooms) is stored in PostgreSQL by domain-aligned services
- **Volatile state** (sessions, command queues, timers) is stored in Redis and coordinated by the Game Session Service
- **Redis** is a **non-authoritative coordination buffer** — but **critical** for consistency, ticks, retries, and recovery
- **Tick regions** are shard-aligned in Redis to preserve atomicity
- **DMZ services (TCP Proxy Service and Spring Cloud Gateway)** remain stateless with respect to PostgreSQL; they may use **Cache/Rate-Limit Redis** and always emit logs/metrics, but do not own persistent domain tables.

> 🔗 See [Redis Architecture](./system-architecture-redis.md) for key structure and durability strategies.

---

## Game Loop / Tick Model

FireMUD uses a **Hybrid Tick Model** to balance responsiveness and fairness:

- **One action per entity per tick** (pulled from command queues)
- **Region-scoped ticks** execute independently for parallelism
- **Tick state** (locks, queues, timers) is stored and coordinated via Redis

> 🔗 See [Tick System and Runtime Design](./system-architecture-ticks.md) for tick execution, staging/rollback, retry policies, and crash recovery.

---

## Scaling Model

FireMUD’s gameplay services are designed to scale horizontally:

- **Game Session Service** scales out across nodes and shards work by tick region, using Redis keys and Lua scripts to coordinate region-local ticks without a single authoritative process.
- **Game Logic Service** is stateless and horizontally scalable; each instance resolves actions deterministically based on the input state it receives from Game Session and Entity Management.
- Other microservices (Account, Entity, World, Social, Logging & Admin) scale independently behind Kubernetes `Deployment` objects and shared PostgreSQL/Redis infrastructure.

This model avoids single-node bottlenecks for ticks or session handling; see [Tick System and Runtime Design](./system-architecture-ticks.md) and [System Architecture – Scaling Runbook](./system-architecture-scaling-runbook.md) for detailed guidance on region sizing, pod counts, and operational tuning.

### Session Sharding & Routing

Game Session Service instances are deployed as a **pool of identical workers**. Ownership of tick work and live sessions is partitioned by `<tenantId, regionId>`:

- A scheduler or consistent-hash layer maps each `<tenantId, regionId>` pair to a specific Game Session instance.
- That instance holds the region lease in Redis and owns tick execution, command queues, and timers for the region.
- Spring Cloud Gateway maintains **sticky WebSocket routing** for a given gameplay session to the Game Session shard that currently holds the region lease.
- On reconnect, Gateway uses the region/session mapping stored in Redis to route the WebSocket connection back to the correct shard before gameplay resumes.

This sharding model aligns with the tick-region ownership and lease rules described in [Tick System and Runtime Design](./system-architecture-ticks.md) and the tick topology guidance in `system-architecture-tick-concepts-and-invariants.md`.

---

## Authentication and Authorization Flow

Clients authenticate using the `LOGIN` command, processed by the **Game Session Service**.
On disconnect, clients must reauthenticate to resume gameplay.
Session state is stored in Redis and reused for recovery.

> 🔗 See [Authentication & Authorization](./system-architecture-authentication.md) for JWT format and session flow.

---

## Observability and Monitoring

See [Logging & Monitoring](./system-architecture-logging-monitoring.md) for the full pipeline, including Fluent Bit, Prometheus, and related dashboards.

> 🔗 See additional Redis metrics and SLO guidance in [Redis Operations & Migrations](./system-architecture-redis-operations.md).

---

## Deployment Layers

| Layer | Technology |
| --- | --- |
| Client Layer | Browser, Telnet MUD Clients |
| Proxy Layer | TCP Proxy Service (LoadBalancer Service) |
| API Gateway Layer | Spring Cloud Gateway (LoadBalancer Service) |
| Gameplay Session Layer | Game Session Service |
| Service Layer | Microservices (Account, Entity, World, Logic, etc.) |
| Infrastructure Layer | Kubernetes with IPVS, Docker Compose (for local development) |

Deployment health checks (readiness and liveness probes) for these layers are described in detail in [Deployment Environments](./infrastructure/deployment-environments.md).

Environment-specific routing is configured via Spring profiles defined in `application.yml` and selected by the `SPRING_PROFILES_ACTIVE` environment variable. See [Deployment Environments](./infrastructure/deployment-environments.md#🔁-spring-profile-configuration) for how the `dev` and `prod` profiles differ between Docker Compose and Kubernetes.

---

## Notes on Responsibility Alignment

- Functional responsibilities are defined in the [Service Responsibility Matrix](./service-responsibility-matrix.md)
- **Game Session Service** orchestrates tick lifecycles, retries, and session management
- **Game Logic Service** resolves individual actions deterministically based on input state
- **Redis** acts as a passive, high-speed execution substrate — storing volatile state and enabling atomic coordination via Lua scripts

**Movement/Travel** rules are part of **Game Logic Service**. World stores geometry and region metadata (e.g., `spacingMultiplier`), while **Game Logic** derives movement/travel costs at runtime.

🧠 **Why Game Session Service vs Game Logic Service?**
Game Logic Service is stateless and deterministic.
Game Session Service governs pacing, conflict handling, and orchestration across distributed tick regions.

### Authoritative Data Ownership (Examples)

The following examples illustrate where key concepts live; the full matrix remains canonical:

| Concept | Owning service | Notes |
| --- | --- | --- |
| Accounts, login credentials, JWT issuance | Account Service | Issues and validates JWTs; manages subscriptions and bans. |
| Characters, NPCs, items, inventories | Entity Management Service | Owns persistent entity state, inventories, and stats. |
| World topology (rooms, regions, maps) | World Management Service | Stores published room graphs, regions, and pathfinding metadata; Game Design Service is the design-time authoring tool and publishes topology versions into World Management. |
| Game assets (published content and exported artifacts) | Game Design Service | Owns game asset publishing to the S3-compatible object store; other services and clients consume published assets via configured URLs rather than writing to the store directly. |
| Gameplay mechanics (combat, movement, progression) | Game Logic Service | Implements deterministic rules; no persistent ownership. |
| Live sessions, ticks, command queues | Game Session Service | Owns Redis-backed coordination for active gameplay. |
| Chat, groups, social graph | Social & Groups Service | Manages chat channels, guilds, friends/blocks. |
| Moderation events, admin dashboards | Logging & Admin Service | Aggregates logs/metrics/traces and powers moderation tooling. |

---

## Related Documentation

### Diagrams

- [System Architecture Diagram](./system-architecture-diagram.md)
- [System Context Diagram](./system-context-diagram.md)

### Infrastructure & Deployment

- [Deployment Environments](./infrastructure/deployment-environments.md)
- [Gateway Architecture](./system-architecture-gateway.md)
- [Infrastructure Overview](./infrastructure/README.md)
- [Multi-Tenancy Architecture](./system-architecture-multi-tenancy.md)
- [Protocol Bridging](./system-architecture-protocol-bridging.md)

### Runtime & Security

- [Authentication & Authorization](./system-architecture-authentication.md)
- [Database Migrations](./system-architecture-database-migrations.md)
- [Logging & Monitoring](./system-architecture-logging-monitoring.md)
- [Reconnection Strategy](./system-architecture-reconnection.md)
- [Redis Architecture](./system-architecture-redis.md)
- [Security Architecture](./system-architecture-security.md)
- [Testing Strategy](./system-architecture-testing.md)
- [Tick System and Runtime Design](./system-architecture-ticks.md)

### Gameplay & Tools

- [Frontend Architecture](./system-architecture-frontend.md)
- [Mud Client Protocol (MCP) Support](./system-architecture-mud-client-protocol.md)
- [Procedural Generation](./system-architecture-procedural-generation.md)
- [Scripting & Automation Framework](./system-architecture-scripting.md)

### Responsibilities

- [Microservices Responsibility Matrix](./service-responsibility-matrix.md)
