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
- Chat profanity triggers a gRPC call to the Logging & Admin Service to record a
  moderation report.
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
- Asynchronous player-to-player mail.
- Guild creation and membership management.
- Shared guild storage and alliance system.
- Friend lists scoped both to individual games and to overall accounts.
- Cross-game presence lets players know when friends are online in any hosted
  game.
- In-game social chat plus account-to-account direct messaging.

### Data Model

- `chat_message` table persists guild and private messages.
- `guild` and `guild_member` tables store group ownership and membership roles.
- `friend_link` table tracks account or character friendships and blocks.
- `mail_message` table stores asynchronous player mail.
- `faction` and `faction_standing` tables maintain player reputation. The
  [Automation & Scripting Service](../automation-scripting-service/README.md)
  queries these standings to influence NPC behaviour.

### Chat Pipeline

- Messages are published to Redis streams and fanned out to WebSocket channels
  through the Spring Cloud Gateway.
- Guild and direct messages share a common persistence model for history.

### Voice Chat Integration

Voice chat is an optional feature built on top of a lightweight WebRTC gateway.
The gateway establishes peer-to-peer connections between players and relays
media streams when direct communication is not possible. The Social & Groups
Service issues temporary WebRTC tokens and records basic session metadata so the
Logging & Admin Service can audit voice activity. Voice chat is disabled by
default and can be enabled per tenant through configuration.

### gRPC/REST APIs

- `SendMessage` – publishes a chat message to an in-game channel or player.
- `SendAccountMessage` – delivers a direct message between account holders.
- `CreateGuild` – establishes a new guild with an owner account.
- `AddFriend` – adds a friend relationship at the game or account level.
- `SendMail` – stores asynchronous player mail for later retrieval.

## Dependencies

- **Internal:**
  - Account Service for user identities.
  - Logging & Admin Service consumes chat logs for moderation.
- **External:** PostgreSQL for social data.

### TenantId Handling & Cross-Service Dependencies

All data models include a `tenantId` column so chat history and guild
information remain isolated per game. Redis keys use the same prefix. The service
validates this context on every request and forwards the identifier to other
services, such as the Logging & Admin Service, to maintain isolation across the
platform.

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

## Environment Variables

The service uses the shared configuration conventions defined in
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
Important variables include:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `SPRING_PROFILES_ACTIVE` | Spring profile (`dev` or `prod`) | `dev` |
| `FIREMUD_POSTGRES_HOST` | PostgreSQL host | `postgres` |
| `FIREMUD_POSTGRES_PORT` | PostgreSQL port | `5432` |
| `FIREMUD_REDIS_HOST` | Redis host | `redis` |
| `FIREMUD_REDIS_PORT` | Redis port | `6379` |

See `DatabaseAutoConfiguration` in the
[Shared Libraries](../system-architecture-shared-libraries.md) for how these
variables are consumed.

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

- Rich moderation tools for chat including profanity filtering and moderator dashboards.
- Optional voice chat integration via a WebRTC gateway.
