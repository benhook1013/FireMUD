# Social & Groups Service

## Overview

Provides chat, guild, and social networking features across games. Basic REST and gRPC APIs are implemented for guilds, friends, chat, and mail. Real-time WebSocket delivery is available.

An OpenAPI specification for the REST endpoints is available at `src/main/resources/openapi.yaml` in the service repository.

### Responsibilities

- Deliver real-time chat notifications.
- Synchronize guild and friend lists in real time.
- Manage guild creation, membership, and roles
- Maintain friend lists and cross-game social graphs
- Store chat logs locally; profanity events generate moderation reports via the Logging & Admin Service

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
  services remain consistent. See [Transaction Strategies](../../system-architecture-transactions.md).
- Chat history and guild data are stored with a `tenantId` so conversations are
  isolated per game. Redis list keys also include this prefix. See
  [Multi-Tenancy](../../system-architecture-multi-tenancy.md).
- Cross-service calls always forward the `tenantId` so features remain isolated;
  see [Multi-Tenancy](../../system-architecture-multi-tenancy.md) for details.
- APIs require authenticated JWTs from the Account Service for role checks.
  These tokens are exchanged only between services. All inter-service communication is encrypted via mutual TLS, following the [Security Architecture](../../system-architecture-security.md).
- Utilizes the [Shared Libraries](../../system-architecture-shared-libraries.md) for DTO definitions, logging interceptors, and Micrometer metrics.

## Key Features

- Global and guild chat rooms.
- Private messaging between players.
- Asynchronous player-to-player mail.
- Guild creation and membership management.
- Shared guild storage and alliance system.
- Friend lists scoped both to individual games and to overall accounts. Account-level friends automatically appear in-game when enabled.
- In-game social chat plus account-to-account direct messaging.
- Presence indicators notify when friends come online.
- Game creators can broadcast announcements and send out-of-game emails.

### Data Model

- `chat_message` table persists guild and private messages.
- `guild` and `guild_member` tables store group ownership and membership roles.
- `friend_links` table stores per-game friendships scoped by `tenantId`.
- `account_friend_links` table stores account-to-account friendships shared across games.
- Games can mirror these links in their UI when the feature is enabled.
- `mail_message` table stores asynchronous player mail.
- `faction` and `faction_standing` tables are defined in the [Automation & Scripting Service](../automation-scripting-service/README.md) to track player reputation. Integration with this service for NPC behaviour is available.

### Redis Role and Prefixes

- **Coordination Redis**
  - Social & Groups does **not** own or modify Coordination Redis prefixes. It does not touch `tick:*`, `timer:*`, `retry:*`, `session:*`, or automation coordination keys; gameplay coordination and automation ticks remain the responsibility of the Game Session and Automation & Scripting services as described in [Redis Architecture](../../system-architecture-redis.md).
- **Cache/Rate-Limit Redis**
  - Uses **Cache/Rate-Limit Redis** for chat history buffers and similar transient social aggregates under prefixes such as:
    - `chat:say:<tenantId>:<playerId>`
    - `chat:tell:<tenantId>:<conversationId>`
    - `chat:guild:<tenantId>:<guildId>`
    - `chat:account:<tenantId>:<accountId>`
    - `chat:city:<tenantId>:<cityId>`
  - These lists mirror persisted history in PostgreSQL for quick retrieval and are subject to TTL and max-message limits configured via `FIREMUD_CHAT_*` variables, following the cache key and TTL guidance in [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md). They are treated as **best-effort TTL-only caches**: correctness comes from PostgreSQL, while Redis provides short-lived history windows bounded by the configured TTLs and message counts, consistent with the `chat:*` entries in the Cache/Rate-Limit Key Catalog.
- New chat/cache prefixes or changes to Redis usage should be validated against the [Redis Design Checklist](../../system-architecture-redis-design-checklist.md) so they remain aligned with the global key catalog and SLOs, and should be added to the Cache/Rate-Limit Redis key catalog maintained in the Redis cache design docs (Redis cheat sheet plus `system-architecture-redis-cache.md`) with documented size/complexity budgets.
  - Cache metrics for these prefixes should follow the `chat:*` recommendations in `system-architecture-redis-cache.md` (for example `cache.chat_hits_total` / `cache.chat_misses_total` with chat-type labels) so hit/miss behavior and key counts are observable.
  - Concrete TTL and max-message budgets for these prefixes (for example `FIREMUD_CHAT_SAYS_TTL_SECONDS` / `FIREMUD_CHAT_SAYS_MAX_MESSAGES`) are documented in this README’s Environment Variables section and must remain aligned with the size/complexity envelopes described in `system-architecture-redis-cache.md`.

> If you change Redis usage for this service, you must read and apply:
>
> - [Redis Architecture](../../system-architecture-redis.md)
> - [Redis Cache & Rate Limiting](../../system-architecture-redis-cache.md)
> - [Redis Operations & Migrations](../../system-architecture-redis-operations.md)

### Chat Pipeline

- Messages are cached in Redis lists and delivered to WebSocket channels
  through the Spring Cloud Gateway.
- Guild and direct messages share a common persistence model for history.

### Implementation status (chat slice)

- **Live:** `SendMessage` already writes chat messages to Redis history and persists them for moderation; the SAY slice now exercises this endpoint via the Game Logic `BroadcastSay` path so regression tests assert delivery metadata (recipient list, NPC echoes) before the payload reaches clients.
- **Stubbed:** The regression fixtures wire a lightweight Social & Groups stub that records `SendMessageRequest` payloads, returns success, and lets the Game Session/TCP proxy cross-service tests verify canonical transcripts without targeting the full production moderation pipeline.
- **Deferred:** Future work will layer in contextual features such as profanity enforcement heuristics, targeted NPC echoes, and channel-routing rules once the core SAY delivery path is stabilized by the automated regression suites.

### Voice Chat Integration

Voice chat is available as an optional feature built on top of a lightweight WebRTC gateway.
The gateway establishes peer-to-peer connections between players and relays
media streams when direct communication is not possible. The service issues
temporary WebRTC tokens via the REST endpoint `/voice/token` (see
`openapi.yaml` lines 499–520) and records voice activity for moderation.

### gRPC APIs

- `Ping` – simple connectivity check.
- `SendMessage` – publishes a chat message to an in-game channel or player.
- `CreateGuild` – establishes a new guild with an owner account.
- `AddFriend` – adds a friend relationship at the game or account level.
- `SendMail` – stores asynchronous player mail for later retrieval.

## Dependencies

- **Internal:**
  - Account Service for user identities.
  - Logging & Admin Service consumes chat logs for moderation.
- **External:** PostgreSQL for social data.

## Operational Notes

- Runs as a Kubernetes Deployment (Docker Compose for local dev) with `/actuator/health/readiness` and `/actuator/health/liveness` probes. See [Deployment Environments](../../infrastructure/deployment-environments.md).
- Logging, metrics, and tracing follow the standard [Logging & Monitoring](../../system-architecture-logging-monitoring.md) pipeline.

## Environment Variables

The service follows the conventions from
[Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md).
It relies on the [PostgreSQL credentials](../../infrastructure/environment-and-secrets.md#postgresql-credentials)
and [Redis connection](../../infrastructure/environment-and-secrets.md#redis-connection).
TLS certificates are supplied via [`FIREMUD_GRPC_CERT_CHAIN_PATH`, `FIREMUD_GRPC_PRIVATE_KEY_PATH`, `FIREMUD_GRPC_CA_CERT_PATH`](../../infrastructure/environment-and-secrets.md#grpc-tls-certificates). Peer services can be discovered using variables prefixed `FIREMUD_SERVICES_`.
The OpenTelemetry collector endpoint can be overridden via `OTEL_ENDPOINT` (see [Environment Variables & Secrets Management](../../infrastructure/environment-and-secrets.md)).

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_SERVICES_LOGGING_ADMIN_SERVICE` | `host:port` of the Logging Admin service | `logging-admin-service:6565` |
| `FIREMUD_VOICE_TOKEN_EXPIRATION_MS` | Expiration of voice chat tokens | `300000` |

Chat history cache behaviour can be tuned with the following variables:

| Variable | Purpose | Default |
| -------- | ------- | ------- |
| `FIREMUD_CHAT_SAYS_TTL_SECONDS` | Seconds to keep `say` messages per player | `7200` |
| `FIREMUD_CHAT_SAYS_MAX_MESSAGES` | Max cached `say` messages per player | `50` |
| `FIREMUD_CHAT_TELLS_TTL_SECONDS` | Seconds to keep direct tells/messages | `172800` |
| `FIREMUD_CHAT_TELLS_MAX_MESSAGES` | Max cached tells/messages per player | `50` |
| `FIREMUD_CHAT_GUILD_TTL_SECONDS` | Seconds to keep guild chat per guild | `172800` |
| `FIREMUD_CHAT_GUILD_MAX_MESSAGES` | Max cached guild chat messages | `50` |
| `FIREMUD_CHAT_CITY_TTL_SECONDS` | Seconds to keep city chat per city | `172800` |
| `FIREMUD_CHAT_CITY_MAX_MESSAGES` | Max cached city chat messages | `50` |
| `FIREMUD_CHAT_ACCOUNT_TTL_SECONDS` | Seconds to keep account-to-account messages | `172800` |
| `FIREMUD_CHAT_ACCOUNT_MAX_MESSAGES` | Max cached account messages | `50` |

## Proto Files

The social APIs are defined in
[../../../../protos/social-groups/v1](../../../../protos/social-groups/v1).
Regenerate the service stubs with `./gradlew generateProto` whenever the proto
files change.

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

## Additional Details

### REST & gRPC Endpoints

#### REST

- `GET /ping` – basic health check returning `"pong"`.
- `POST /friends` – create a friend link.
- `POST /mail` – send an asynchronous in-game mail message. Mail retrieval endpoints are available.
- `POST /guilds` – create a guild.
- `POST /guilds/storage` – add an item to guild storage.
- `POST /guilds/alliances` – create a guild alliance.
- `POST /guilds/members` – add a guild member.
- `POST /guilds/members/role` – update a guild member's role.
- `POST /guilds/members/remove` – remove a guild member.
- `POST /chat` – send a chat message filtered for profanity.
- `POST /voice/token` – issue a temporary WebRTC token for voice chat. The gateway relays media between participants.

```bash
curl http://localhost:8080/ping
```

#### gRPC

- `Ping(PingRequest) returns (PingResponse)` – connectivity check defined in [`social_groups_service.proto`](../../../../protos/social-groups/v1/social_groups_service.proto).

```bash
grpcurl -plaintext localhost:6565 social_groups.v1.SocialGroupsService/Ping
```

- `POST /chat` – send a chat message filtered for profanity.

```bash
curl -X POST http://localhost:8080/chat \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-abc","senderAccountId":100,"content":"hello"}'
```

### Metrics & Tracing

Prometheus scrapes metrics from `/actuator/prometheus`. OpenTelemetry spans are exported to the collector defined in the shared configuration. No additional setup is required when running `./gradlew bootRun`.

### Voice Chat

The service integrates with a WebRTC gateway to provide voice channels for guilds and parties. Tokens are issued via the `/voice/token` REST endpoint (see `openapi.yaml` lines 499–520), and connection events are recorded for moderation.

#### Example Request

```bash
curl -X POST http://localhost:8080/voice/token \
  -H 'Content-Type: application/json' \
  -d '{"tenantId":"tenant-abc","accountId":100,"channelId":"guild-10"}'
```

- [System Architecture Diagram](../../system-architecture-diagram.md)
- [System Context Diagram](../../system-context-diagram.md)

### Cross-Service Integration Test

An integration test under `src/test/java/crossservice` starts this service with
the Logging & Admin Service using **Testcontainers**. Execute it once dependent
images are available:

```bash
./gradlew :social-groups-service:test --tests "*CrossServiceIntegrationTest"
```

Refer to [System Architecture Testing](../../system-architecture-testing.md) for
guidance.
