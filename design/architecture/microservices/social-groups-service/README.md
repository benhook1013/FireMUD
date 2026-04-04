# Social & Groups Service

## Overview

Provides chat, guild, and social networking features across games. Basic REST and gRPC APIs are implemented for guilds, friends, chat, and mail. Real-time WebSocket delivery is available.

### Responsibilities

- Deliver real-time chat notifications
- Synchronize guild and friend lists in real time
- Manage guild creation, membership, and roles
- Maintain friend lists and cross-game social graphs
- Store chat logs locally; profanity events generate moderation reports via the Logging & Admin Service

## Key Features

- Global and guild chat rooms
- Private messaging between players
- Asynchronous player-to-player mail
- Guild creation and membership management
- Shared guild storage and alliance system
- Friend lists scoped both to individual games and to overall accounts; account-level friends automatically appear in-game when enabled
- In-game social chat plus account-to-account direct messaging
- Presence indicators notify when friends come online
- Game creators can broadcast announcements and send out-of-game emails

## Current Scope Notes

- The current gameplay-connected communication slice is intentionally narrow in implementation: it proves room-local `say` delivery from Game Session through Game Logic into Social & Groups while establishing the broader shared communication model.
- Future communication work is expected to broaden this into a richer communication model that distinguishes:
  - the communication act/type (`say`, `whisper`, `tell`, `shout`, guild/system/game-defined variants),
  - the target or propagation scope (room, area, region, map, continent, guild/group, account-directed, and other configured channels),
  - recipient resolution owned by that target/scope,
  - and per-recipient presentation for ordinary listeners versus observer/interceptor roles.
- Those broader scope and routing semantics should be modeled explicitly rather than treating all verbs as cosmetic aliases of one generic room broadcast.
- In particular, in-world communication should usually target a room, area, or other scope object and let that scope resolve listeners, overhearers, spies, magical observers, and similar mechanics.
- The target-state observer model should be layered: communication type sets baseline observability, the target/scope resolves qualified listeners and observers, and recipient capabilities/effects determine whether a qualified observer gets full content, partial content, or only metadata.
- Even for communication types that obviously need Social & Groups for membership, history, moderation, or fanout, the action should still enter through Game Logic first so gameplay interception, surveillance, magical listening, or similar mechanics can participate consistently.

## Document Map

- [API Contracts](./api-contracts.md)
  - chat/guild/friends/mail API surfaces, delivery contracts, and proto/OpenAPI ownership.
- [Runtime and Data](./runtime-and-data.md)
  - social-data ownership, Redis/cache behavior, and real-time delivery invariants.
- [Operations](./operations.md)
  - readiness/liveness, moderation/delivery operational notes, and local verification guidance.
- [Configuration](./configuration.md)
  - environment variables, service discovery, TLS, and service-local configuration locations.

## Dependencies

- **Internal:**
  - Account Service for user identities
  - Logging & Admin Service consumes chat logs for moderation
- **External:** PostgreSQL for social data

## Related Documentation

- [System Architecture Overview](../../system-architecture-overview.md)
- [Multi-Tenancy](../../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../../service-responsibility-matrix.md)
- [User Journeys – Social Interaction](../../user-journeys-players.md#4-social-interaction)
- [Redis Architecture](../../system-architecture-redis.md)
- [gRPC API Style & Versioning Guidelines](../../system-architecture-grpc.md)
- [Shared Libraries Overview](../../system-architecture-shared-libraries.md)
- [Database Migrations](../../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../../system-architecture-logging-monitoring.md)
- [Authentication & Authorization](../../system-architecture-authentication.md)
- [Security Architecture](../../system-architecture-security.md)
- [Testing Strategy](../../system-architecture-testing.md)
- [CI/CD Pipeline](../../system-architecture-cicd.md)
- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)
