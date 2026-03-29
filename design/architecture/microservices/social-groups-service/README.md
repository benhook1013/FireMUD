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

- The current gameplay-connected chat slice is intentionally narrow: it proves room-local `SAY`-family delivery from Game Session through Game Logic into Social & Groups.
- Future communication work is expected to broaden this into a richer communication model that distinguishes:
  - room-local speech,
  - directed/private speech such as `WHISPER` or `TELL`,
  - and wider propagation scopes such as area, map, region, continent, or tenant-level channels.
- Those broader scope and routing semantics should be modeled explicitly rather than treating all verbs as cosmetic aliases of one generic room broadcast.

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
