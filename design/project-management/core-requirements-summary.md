# Core Requirements Summary – FireMUD Platform

This summary highlights the **most important product and infrastructure requirements** from `core-requirements.md` and links to the detailed design documents.

## Top-Level Product Goals

- **Multi-tenant MUD hosting** – One platform hosts multiple independent games, each with isolated world data, player characters, and configuration.
- **Realm-based gameplay** – Hosted games may expose a publicly discoverable production realm plus explicit non-production playtest forks for creator-led validation. In v1, those non-production realms require explicit access grants.
- **Discovery-first player admission** – First-party clients discover visible worlds, realms, and characters before socket admission and then complete the same canonical lobby `PLAY` flow as text clients.
- **Powerful game design tools** – Game creators can shape worlds, NPCs, items, abilities, and rulesets without direct code changes.
- **Real-time multiplayer** – Low-latency, text-first gameplay with support for Web and Telnet clients.
- **Automation & scripting** – Designers can extend gameplay through scripts and automation, integrated with ticks and world events.
- **Moderation & administration** – Operators have tools for logging, monitoring, moderation, and administrative control across games.

## Core Functional Areas

- **Account & identity**
  - Secure authentication, RBAC roles, and support for linking external identities.
  - Single platform account with per-game characters.
  - See: `design/architecture/microservices/account-service/README.md`.
- **World & entity management**
  - Authoritative storage for worlds, entities, inventories, and progression.
  - Strong separation between design-time data and runtime state.
  - See: `design/architecture/microservices/world-management-service/README.md` and `design/architecture/microservices/entity-management-service/README.md`.
- **Game logic & automation**
  - Tick-driven command execution, deterministic rules, and replay-safe operations.
  - Integrated scripting framework for events and behaviors.
  - See: `design/architecture/system-architecture-ticks.md` and `design/architecture/system-architecture-scripting.md`.

## Infra & Scalability Summary

- **Networking & gateway**
  - Spring Cloud Gateway fronting gRPC microservices, with TCP Proxy bridging Telnet clients.
  - mTLS-secured internal gRPC communication.
  - See: `design/architecture/system-architecture-gateway.md`, `design/architecture/system-architecture-mud-client-protocol.md`, and `design/architecture/system-architecture-protocol-bridging.md`.
- **Persistence & caching**
  - PostgreSQL as the authoritative source of truth; Redis only for transient coordination and caching.
  - Strict separation between **Coordination Redis** and **Cache/Rate-Limit Redis**.
  - See: `design/architecture/system-architecture-redis.md` and `design/architecture/system-architecture-redis-cache.md`.
- **Deployment & operations**
  - Docker + Kubernetes deployment, with CI/CD pipelines and backup/restore flows.
- Centralized logging, metrics, and tracing for all services.
- See: `design/architecture/system-architecture-cicd.md`, `design/architecture/system-architecture-logging-monitoring.md`, and `design/architecture/system-architecture-backup-recovery.md`.

For detailed requirements, constraints, and non-functional goals, see `design/project-management/core-requirements.md`.
