# Social & Groups Service

## Overview

Provides chat, guild, and social networking features across games. Enables players to form groups and communicate in real time.

An OpenAPI specification for the REST endpoints is available at `src/main/resources/openapi.yaml` in the service repository.

### Responsibilities

- Deliver real-time chat and presence notifications
- Manage guild creation, membership, and roles
- Maintain friend lists and cross-game social graphs
- Feed chat logs to the Logging & Admin Service for moderation
- Send out-of-game notifications and bulk emails on behalf of game creators

## Architecture / Design Notes

- Uses WebSocket channels for chat delivery.
- Stores guild and friend relationships in PostgreSQL.
- Integrates with the Logging & Admin Service for moderation events.
- Chat profanity triggers a gRPC call to the Logging & Admin Service to record a
  moderation report.
- In-game chat commands such as say, tell, guild chat, and mail originate in the
  Game Logic Service and incorporate context from the World Management and
  Entity Management services. The Game Logic Service invokes this service to
  deliver messages, run profanity checks, and log all communications for audit
  and moderation.
- Messages are cached in Redis with type-specific TTLs so players can retrieve
  recent history:
  - Says: 2 hours or 50 messages per player
  - Tells: 48 hours or 50 messages per player
  - Guild/City chat: 48 hours or 50 messages per guild or city
  - Account messages: 48 hours or 50 messages
  Older messages remain in PostgreSQL for moderation and historical logs.
- Guild creation and membership changes participate in Saga workflows so other
  services remain consistent. See [Transaction Strategies](../system-architecture-transactions.md).
- Chat history and guild data are stored with a `tenantId` so conversations are
  isolated per game. Redis stream keys also include this prefix. See
  [Multi-Tenancy](../system-architecture-multi-tenancy.md).
- Cross-service calls always forward the `tenantId` so features remain isolated;
  see [Multi-Tenancy](../system-architecture-multi-tenancy.md) for details.
- APIs require authenticated JWTs from the Account Service for role checks.
  These tokens are exchanged only between services. All inter-service communication is encrypted via mutual TLS, following the [Security Architecture](../system-architecture-security.md).
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
- Broadcast email capability for game creators to reach all active players.

### Data Model

- `chat_message` table persists guild and private messages.
- `guild` and `guild_member` tables store group ownership and membership roles.
- `friend_link` table tracks account or character friendships and blocks.
- Per-game friendships are stored on character records in the Entity Management
  Service. When a game opts in, account-level friends from this service are
  mirrored as in-game friends automatically.
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

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health` probes. See [Deployment Environments](../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

The service follows the conventions from
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It relies on the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection).
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.

Chat history cache behaviour can be tuned with the following variables:

- `FIREMUD_CHAT_SAYS_TTL_SECONDS` / `FIREMUD_CHAT_SAYS_MAX_MESSAGES`
- `FIREMUD_CHAT_TELLS_TTL_SECONDS` / `FIREMUD_CHAT_TELLS_MAX_MESSAGES`
- `FIREMUD_CHAT_GUILD_TTL_SECONDS` / `FIREMUD_CHAT_GUILD_MAX_MESSAGES`
- `FIREMUD_CHAT_CITY_TTL_SECONDS` / `FIREMUD_CHAT_CITY_MAX_MESSAGES`
- `FIREMUD_CHAT_ACCOUNT_TTL_SECONDS` / `FIREMUD_CHAT_ACCOUNT_MAX_MESSAGES`

## Proto Files

The social APIs are defined in
[../../../../protos/social-groups/v1](../../../../protos/social-groups/v1).
Regenerate the service stubs with `./gradlew generateProto` whenever the proto
files change.

## 📚 Related Documentation

- [System Architecture Overview](../system-architecture-overview.md)
- [Multi-Tenancy](../system-architecture-multi-tenancy.md)
- [Service Responsibility Matrix](../service-responsibility-matrix.md)
- [User Journeys – Social Interaction](../user-journeys.md#7-social-interaction)
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

## Additional Details

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /friends` – create a friend link.
- `POST /mail` – send an asynchronous in-game mail message.
- `POST /guilds/storage` – add an item to guild storage.

```bash
curl http://localhost:8080/ping
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`social_groups_service.proto`](../../../protos/social-groups/v1/social_groups_service.proto).

```bash
grpcurl -plaintext localhost:6565 social_groups.v1.SocialGroupsService/Ping
```

- `POST /chat` – send a chat message filtered for profanity.

```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"senderAccountId":100,"content":"hello"}'
```

### Metrics & Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. OpenTelemetry spans are exported to the collector defined in the shared configuration. No additional setup is required when running `./gradlew bootRun`.

### Voice Chat

The service can optionally integrate with a WebRTC gateway to provide voice channels for guilds and parties. When enabled, the REST API issues short-lived WebRTC tokens and records connection events so moderation actions can be traced. This feature is disabled by default and is intended for games that wish to offer in-client voice without relying on external tools.

#### Example Request

```bash
curl -X POST http://localhost:8080/voice/token \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":1,"accountId":100,"channelId":"guild-10"}'
```

- [System Architecture Diagram](../system-architecture-diagram.md)
- [System Context Diagram](../system-context-diagram.md)

### Cross-Service Integration Test

An integration test under `src/test/java/crossservice` starts this service with
the Logging & Admin Service using **Testcontainers**. Execute it once dependent
images are available:

```bash
./gradlew :social-groups-service:test --tests "*CrossServiceIntegrationTest"
```

Refer to [System Architecture Testing](../system-architecture-testing.md) for
guidance.

## Future Enhancements

- Rich moderation tools for chat including profanity filtering and moderator dashboards.
- Optional voice chat integration via a WebRTC gateway.
