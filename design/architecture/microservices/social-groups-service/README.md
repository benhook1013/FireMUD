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

## Dependencies

- **Internal:**
  - Account Service for user identities
  - Logging & Admin Service consumes chat logs for moderation
- **External:** PostgreSQL for social data

## Related Documentation

- [API Contracts](./api-contracts.md)
- [Runtime and Data](./runtime-and-data.md)
- [Operations](./operations.md)
- [Configuration](./configuration.md)
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
