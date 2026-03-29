# Product Requirements Document (PRD): MUD Game Platform

## 1. Introduction

This document is the canonical product requirements summary and detailed requirements set for the FireMUD platform.

### 1.1 Purpose

The MUD Game Platform is a **multi-tenant system** that enables users to **create, host, and run multiple independent MUD games**. The platform provides a **scalable, modular, and extensible architecture**, supporting **game world management, player interactions, scripting, automation, and real-time networking**.

### 1.2 Scope

This document outlines the **core functional and non-functional requirements** for the MUD Game Platform, focusing on:

- Multi-tenancy support for **multiple hosted games**.
- A **microservices architecture** for modularity and scalability.
- A **customizable game framework** allowing different rulesets.
- **Real-time networking** and multiplayer interactions.
- **Administration, moderation, and logging tools** for game operators.
- **Scalability, persistence, and deployment considerations**.

### 1.3 Users & Stakeholders

- **Game Designers (Creators)**: Users who design and manage games using the platform's tools.
- **Players**: End users who join and play games on the platform.
- **Administrators & Moderators**: Users who oversee platform security, logging, and compliance.
- **Platform Developers**: Those extending or modifying the FireMUD platform itself.

---

## 2. Key Features & Functional Requirements

### 2.1 Multi-Tenancy & Game Hosting

- The platform supports **multiple hosted games**, each **isolated at the game level**. See [Multi-Tenancy Architecture](../architecture/system-architecture-multi-tenancy.md).
- Each hosted game has **separate world data, player characters, and configurations**.
- Players have a **single platform-wide account** that allows them to join multiple games, with **separate characters per game**.
- Game creators can **host multiple games** with independent settings.
- Hosted games may expose multiple player-addressable realms, including a default production realm and explicit non-production playtest forks used to validate changes against copied gameplay state without mutating production.

### 2.2 Game Design & Customization

- Provides **game editing tools** for modifying world layouts, NPCs, items, and abilities. See [Game Design Service](../architecture/microservices/game-design-service/README.md).
- Allows **game creators to configure rulesets and mechanics** without requiring code changes.
- Supports **game balancing, including experience curves, combat formulas, and economy adjustments**.
- Enables **scripted event design for quests, encounters, and world events**.
- **Procedural generation** supports **algorithm-driven world creation** (e.g., procedural room layouts) while allowing **manual overrides**.

### 2.3 User & Account Management

- The platform must provide **secure authentication and user management**.
- Gameplay login commands are fronted by the **Game Session Service**, which calls the **Account Service** to verify credentials and issue JWTs/tokens used by backend services.
- Role-based access control (RBAC) for **admins, moderators, and players**.
- Users should be able to **create and manage multiple characters per game**.
- Sessions should support **persistent logins and reconnection handling**.
- First-party web and mobile clients must support world, realm, and character selection before gameplay begins.
- **Expanded Account Features**:
  - Players should be able to **link external accounts** (Google, Discord, Steam) for login.
  - Profiles should include **game history, achievements, and social features**.
  - Persistent session tracking to **ensure seamless reconnection across devices**.
See [Account Service](../architecture/microservices/account-service/README.md) for implementation details.

### 2.4 Game World & Entity Management

- Support for **multi-room game worlds** with region-based navigation.
- **Instance-based game spaces** allow separate world states (e.g., public production realms, creator-managed playtest forks, private dungeons, event-based scenarios, or personalized player housing).
- Game creators can configure **instance rules, expiration, and persistence settings**.
- **World Persistence & Scheduled Events**:
  - The platform must support **persistent world states**, ensuring that world changes **persist beyond player sessions**.
  - **Scheduled events** (e.g., daily resets, seasonal world changes, NPC schedules) should be configurable.
  - NPC actions and environmental changes should **continue in a believable way even if no players are online**.
- Persistent storage for **player, NPC, and item data** across services:
  - The [Entity Management Service](../architecture/microservices/entity-management-service/README.md) owns all runtime entities and inventories, including player gear, containers, and items on the ground in rooms.
  - The [World Management Service](../architecture/microservices/world-management-service/README.md) owns world topology and ambient world state (rooms, regions, instances, environmental state) but does **not** store live items or inventory contents.

### 2.5 Game Logic & Automation

- Players interact with the game via **text-based command parsing** (e.g., `"move north"`, `"attack goblin"`).
- The platform must support **custom game logic per hosted game**.
- **Action processing** for combat, trading, crafting, and roleplay actions.
- **NPC AI Behaviors**:
  - NPCs react dynamically to the world using **event-driven** (trigger-based) and **state-driven** (persistent memory) behaviors.
  - NPCs maintain **awareness of past interactions**, allowing dynamic responses.
  - The system supports **world simulation**, enabling **autonomous NPC actions** even when no players are online.
- Uses a **hybrid tick model** with **one action per entity per tick** for deterministic processing across independently scaled regions.
See [Game Logic Service](../architecture/microservices/game-logic-service/README.md) and [Automation & Scripting Service](../architecture/microservices/automation-scripting-service/README.md) for design details.

### 2.6 Real-Time Multiplayer & Communication

- **WebSockets/TCP-based real-time networking** for player interactions.
- In-game **chat system, mail messaging, and guild/group communications**.
- **PvP & cooperative multiplayer support**.
- **One active session per character**; new logins immediately replace the existing connection to allow seamless device handoff.
See [Social & Groups Service](../architecture/microservices/social-groups-service/README.md) for chat and guild features.

### 2.7 Extensibility & Game Customization

- Games should support **custom game rules, abilities, and world data**.
- **Scripting API & Advanced AI Customization**:
  - The platform offers **AI & scripting tools** for creating deep, dynamic game interactions.
  - Games can define **unique AI behaviors, quest logic, and in-game events** without requiring custom code deployments.
  - AI behaviors should be flexible enough to allow **autonomous world simulation**, making the game feel persistent and alive.
  - Scripts are authored through a **component-based DSL** with a **visual editor**.
  - The Automation & Scripting Service executes scripts in a **sandbox** with **resource quotas** to prevent abuse.
- **Item & equipment balancing tools** to allow game creators to tweak in-game balance.

See [Game Design Service](../architecture/microservices/game-design-service/README.md) for authoring tools.

### 2.8 Moderation, Administration & Monetization

- **Admin dashboard** for monitoring and moderating hosted games.
- **In-game reporting & ban system** for handling violations.
- **Moderation policy definitions** including profanity filters.
- **Central analytics dashboards and logging** for tracking player activity and game performance.
- **Runtime feature flags** are defined in the **Game Design Service**, stored and managed by the **Game Session Service**, and can be toggled through the **Logging & Admin Service**. See [Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md) for details.
- **Monetization & Payment System**:
  - The platform integrates **Stripe or similar services** for in-game purchases.
  - Game creators can offer **subscriptions, one-time purchases, and donations**.
  - A **platform fee** applies to all transactions.
  - **External payment methods are not allowed** to ensure security and compliance.
  - **High-resource features** (e.g., AI, scripting) may be **premium hosting options**.
See [Logging & Admin Service](../architecture/microservices/logging-admin-service/README.md) for moderation features and [Account Service](../architecture/microservices/account-service/README.md) for payment processing.

---

### 2.9 Versioning & Runtime Configuration

- The **Game Design Service** publishes immutable game versions identified by a `version_id`.
- Domain services copy design data by `version_id` and do not query the design database at runtime.
- The **Game Session Service** activates the desired `version_id` when launching or cutting over the instance currently routed behind a specific realm.
- Runtime feature flags are stored with the session and edited via the **Logging & Admin Service**. See [Versioning & Runtime Configuration](../architecture/system-architecture-versioning-runtime.md).
- The Game Design Service maintains **patch notes** for each published version so administrators can track changes over time.
See [Game Design Service](../architecture/microservices/game-design-service/README.md) for publishing workflows.

---

## 3. Infrastructure & Scalability Considerations

### 3.1 Networking & API Gateway

- **WebSocket/TCP-based real-time networking** for low-latency gameplay.
- **TCP Proxy Service** bridges legacy **Telnet** clients to WebSockets before reaching the Gateway.
- **API Gateway** manages requests between microservices and handles external integrations.
- **Gameplay login is handled by the Game Session Service**; any JWT on admin or REST endpoints is validated by the consuming service. For first-party `/ws/game/**` handshakes, Spring Cloud Gateway validates the short-lived connect token at the edge and Game Session validates the signed Gateway-issued connect context before gameplay admission. Gameplay credentials and admission still remain `LOGIN` / `PLAY`-driven rather than JWT-on-every-message.
- **Internal microservices communicate over gRPC**, secured by **mTLS** certificates issued via Kubernetes.
- **Cert-manager** provisions and rotates these certificates as **Kubernetes Secrets**.
- Multi-server support enables **scaling hosted games separately**.
See [Gateway Architecture](../architecture/system-architecture-gateway.md) and [Reconnection Strategy](../architecture/system-architecture-reconnection.md) for network flow details.

### 3.2 Persistence & Caching

- **PostgreSQL** is the primary database for game world, entity, and account storage.
- **Redis** stores transient gameplay and session state only; all authoritative data remains in PostgreSQL.
- **Database migrations are managed per service using [Flyway](../architecture/system-architecture-database-migrations.md).**
See [Redis Architecture](../architecture/system-architecture-redis.md) for key conventions and caching strategy.

### 3.3 Deployment Model

- The platform is designed for **cloud-native deployment**, using:
  - **Docker & Kubernetes** for containerization and scaling.
  - **Automated CI/CD pipelines** for service updates and maintenance (see [CI/CD Pipeline](../architecture/system-architecture-cicd.md)).
- Infrastructure should allow **horizontal scaling** for high-concurrency use cases.
- Supports **multi-region deployments** to provide better latency for global users.
- **Central logging and metrics** use the stack described in [Logging & Monitoring](../architecture/system-architecture-logging-monitoring.md).
- **Velero** backs up Kubernetes manifests only. PostgreSQL data is protected by a `pg_dump` CronJob. The production backup schedule is defined in [Backup & Disaster Recovery](../architecture/system-architecture-backup-recovery.md).
See the [CI/CD Pipeline](../architecture/system-architecture-cicd.md) for workflow details.

### 3.4 Gameplay Session Architecture

- **Game Session Service** orchestrates tick execution and runtime configuration.
- **Redis** stores volatile session state so gameplay sessions can be recovered cleanly after disruptions.
- Tick regions operate independently for scalability but rely on Redis for atomic coordination.
- Redis runs with **AOF persistence**; replication remains asynchronous, and tick state is treated as volatile coordination data that can be reconstructed via idempotent replay after failover.
- Lua scripts in Redis ensure atomic, shard-local tick updates on the primary; correctness and recovery rely on AOF plus idempotent replays against PostgreSQL rather than synchronous replica acknowledgments.
- A layered reconnection model—**TCP Proxy Service → Spring Cloud Gateway → Game Session Service**—must provide a clear, reliable recovery path for all clients. Third-party clients recover through the documented reconnect flow, while first-party clients may automate that same flow to reduce user-visible friction.
See [Tick System](../architecture/system-architecture-ticks.md) and [Reconnection Strategy](../architecture/system-architecture-reconnection.md) for implementation details.

---

## 4. Non-Functional Requirements

| **Category** | **Requirement** |
| --- | --- |
| **Performance** | Must support **hundreds to thousands of concurrent players** per game instance. |
| **Scalability** | Must support **horizontal scaling of services independently**. |
| **Reliability** | **Automated failover and redundancy** for high availability. |
| **Security** | Enforce **OAuth2/JWT authentication, RBAC, and request validation**. |
| **Extensibility** | Provide **modular game design tools** for content creators. |
| **Compliance** | Ensure **GDPR-compliant data handling** for user accounts. |

---
