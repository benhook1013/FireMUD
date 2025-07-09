# Social & Groups Service

## Overview

Provides chat, guild, and social networking features across games. Enables players to form groups and communicate in real time.

### Responsibilities

- Deliver real-time chat and presence notifications
- Manage guild creation, membership, and roles
- Maintain friend lists and cross-game social graphs
- Feed chat logs to the Logging & Admin Service for moderation

## Architecture / Design Notes

- Uses WebSocket channels for chat delivery.
- Stores guild and friend relationships in PostgreSQL.
- Integrates with the Logging & Admin Service for moderation events.
- Messages are briefly cached in Redis streams to smooth bursts of activity and
  enable delivery retries.
- Guild creation and membership changes participate in Saga workflows so other
  services remain consistent. See [Transaction Strategies](../system-architecture-transactions.md).
- Chat history and guild data are stored with a `tenantId` so conversations are
  isolated per game. Redis stream keys also include this prefix. See
  [Multi-Tenancy](../system-architecture-multi-tenancy.md).
- APIs require authenticated JWTs from the Account Service and all inter-service
  communication is encrypted via mutual TLS, following the
  [Security Architecture](../system-architecture-security.md).
- Utilizes the [Shared Libraries](../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- Global and guild chat rooms.
- Private messaging and presence indicators.
- Guild creation and membership management.
- Friend lists scoped both to individual games and to overall accounts.
- Cross-game presence lets players know when friends are online in any hosted
  game.
- In-game social chat plus account-to-account direct messaging.

### Data Model

- `chat_message` table persists guild and private messages.
- `guild` and `guild_member` tables store group ownership and membership roles.
- `friend_link` table tracks account or character friendships and blocks.

### Chat Pipeline

- Messages are published to Redis streams and fanned out to WebSocket channels
  through the Spring Cloud Gateway.
- Guild and direct messages share a common persistence model for history.

### gRPC/REST APIs

- `SendMessage` – publishes a chat message to an in-game channel or player.
- `SendAccountMessage` – delivers a direct message between account holders.
- `CreateGuild` – establishes a new guild with an owner account.
- `AddFriend` – adds a friend relationship at the game or account level.

## Dependencies

- **Internal:**
  - Account Service for user identities.
  - Logging & Admin Service consumes chat logs for moderation.
- **External:** PostgreSQL for social data.

> See [**Gateway Architecture**](../../infrastructure/gateway-architecture.md),
[**Deployment Environments**](../../infrastructure/deployment-environments.md),
and [**Protocol Bridging**](../../infrastructure/protocol-bridging.md) for
details on shared infrastructure components.

## Operational Notes

- Runs as a Kubernetes Deployment. WebSocket chat traffic scales horizontally
  with multiple replicas.
- The service publishes metrics scraped by Prometheus and forwards logs through
  Fluent Bit to Elasticsearch with trace IDs from OpenTelemetry.
- Health is monitored via `/actuator/health`; see
  [Deployment Environments](../../infrastructure/deployment-environments.md) for
  differences between local Docker Compose and production.

## Proto Files

The social APIs are defined in
[../../../../protos/social-groups/v1](../../../../protos/social-groups/v1).
Regenerate the service stubs with `./gradlew generateProto` whenever the proto
files change.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys – Social Interaction](../user-journeys.md#6-social-interaction)
- [Redis Architecture](../system-architecture-redis.md)
- [gRPC API Style & Versioning Guidelines](../system-architecture-grpc.md)
- [Shared Libraries Overview](../system-architecture-shared-libraries.md)
- [Database Migrations](../system-architecture-database-migrations.md)
- [Backup & Disaster Recovery](../system-architecture-backup-recovery.md)
- [Logging & Monitoring](../system-architecture-logging-monitoring.md)
- [Authentication & Authorization](../system-architecture-authentication.md)
- [Security Architecture](../system-architecture-security.md)
- [Testing Strategy](../system-architecture-testing.md)
- [CI/CD Pipeline](../system-architecture-cicd.md)

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

## Future Enhancements

- Rich moderation tools for chat.
- Optional voice chat integration.
